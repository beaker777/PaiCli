package com.paicode.agent.ReAct;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicode.agent.constant.ExitReason;
import com.paicode.agent.service.AgentBudget;
import com.paicode.context.ContextProfile;
import com.paicode.context.TokenUsageFormatter;
import com.paicode.image.service.ImageReferenceParser;
import com.paicode.llm.entity.*;
import com.paicode.llm.service.log.LlmTraceLogger;
import com.paicode.llm.service.model.LlmClient;
import com.paicode.llm.service.stream.impl.AgentStreamRenderer;
import com.paicode.lsp.entity.LspDiagnosticReport;
import com.paicode.memory.entity.MemoryEntry;
import com.paicode.memory.service.compress.ConversationHistoryCompactor;
import com.paicode.memory.service.compress.TokenBudget;
import com.paicode.memory.service.hint.ExplicitMemoryHints;
import com.paicode.memory.service.manager.MemoryManager;
import com.paicode.prompt.constant.PromptMode;
import com.paicode.prompt.entity.PromptContext;
import com.paicode.prompt.service.PromptAssembler;
import com.paicode.renderer.entity.StatusInfo;
import com.paicode.renderer.service.manage.Renderer;
import com.paicode.renderer.service.manage.impl.PlainRenderer;
import com.paicode.runtime.service.cancel.CancellationContext;
import com.paicode.skill.service.buffer.SkillContextBuffer;
import com.paicode.skill.service.manage.SkillRegistry;
import com.paicode.skill.service.parser.SkillIndexFormatter;
import com.paicode.tool.entity.ToolExecutionResult;
import com.paicode.tool.entity.ToolInvocation;
import com.paicode.tool.service.register.ToolRegistry;
import com.paicode.utils.AnsiStyle;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

/**
 * @Author beaker
 * @Date 2026/9/9 19:27
 * @Description Agent
 */
@Getter
@Setter
public class Agent {

    private final static Logger log = LoggerFactory.getLogger(Agent.class);

    private LlmClient llmClient;

    private final ToolRegistry toolRegistry;
    private SkillRegistry skillRegistry;
    private SkillContextBuffer skillContextBuffer;

    private final List<Message> conversationHistory;
    private final ConversationHistoryCompactor historyCompactor;
    private final MemoryManager memoryManager;

    private Renderer renderer;

    private Supplier<String> externalContextSupplier = () -> "";
    private Supplier<Boolean> hitlEnabledSupplier = () -> false;

    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();

    public Agent(LlmClient llmClient) {
        this(llmClient, new ToolRegistry());
    }

    /**
     * 外部提供 toolRegistry
     */
    public Agent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        conversationHistory = new ArrayList<>();
        historyCompactor = new ConversationHistoryCompactor(llmClient);
        memoryManager = new MemoryManager(llmClient);
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
        this.toolRegistry.setMemorySaver(memoryManager::storeFact);
        this.memoryManager.setProjectPath(this.toolRegistry.getProjectPath());
        this.toolRegistry.setScopedMemorySaver(memoryManager::storeFact);
        conversationHistory.add(Message.system(buildSystemPrompt("")));
    }

    /**
     * 获取渲染器, 如果未设置懒加载一个 plain
     */
    private Renderer renderer() {
        if (renderer == null) {
            renderer = new PlainRenderer();
        }
        return renderer;
    }

    // 运行
    public String run(String userInput) {
        log.info("ReAct run started: inputLength={}", userInput == null ? 0 : userInput.length());
        // 清除历史消息中的图片
        pruneHistoricalImagePayloads();

        // 存入短期记忆
        memoryManager.addUserMessage(userInput);
        storeExplicitBrowserMemoryHint(userInput);

        // 检索相关长期记忆, 注入到 system prompt
        ContextProfile contextProfile = memoryManager.getContextProfile();
        String memoryContext = memoryManager.buildContextForQuery(userInput, contextProfile.memoryContextTokens());
        updateSystemPromptWithMemory(memoryContext);

        // 将 input 前拼接 skill 内容
        String userMessage = prependSkillBodies(userInput);

        // 处理 input 中引用的图片
        conversationHistory.add(ImageReferenceParser.userMessage(
                userMessage,
                Path.of(toolRegistry.getProjectPath())
        ));
        StringBuilder reasoningTranscript = new StringBuilder();
        AgentStreamRenderer streamRenderer = new AgentStreamRenderer(renderer());

        long startNanos = System.nanoTime();
        AgentBudget budget = AgentBudget.fromLlmClient(llmClient);
        pushStatus(budget, startNanos, "running");

        while (true) {
            if (CancellationContext.isCancelled()) {
                log.info("ReAct run cancelled before iteration");
                pushStatus(budget, startNanos, "idle");
                return "⏹️ 已取消当前任务。";
            }

            // 对代码进行诊断
            injectPendingLspDiagnostics();

            // 判断是否需要压缩对话
            maybeCompactHistory();

            // 判断是否超过预算
            ExitReason exitReason = budget.check();
            if (exitReason != ExitReason.WITHIN_BUDGET) {
                String description = budget.describeExit(exitReason);

                log.warn("ReAct run exhausted budget: reason={}, iteration={}, tokens={}/{}",
                        exitReason, budget.iteration(),
                        budget.totalInputTokens() + budget.totalOutputTokens(), budget.tokenBudget());
                pushStatus(budget, startNanos, "idle");
                return "❌ " + description;
            }

            int iteration = budget.beginIteration();
            try {
                List<Tool> tools = toolRegistry.getTools();
                logRequestContext("react iteration=" + iteration, tools);
                streamRenderer.beginThinking();

                // 调用 LLM
                ChatResponse response = llmClient.chat(conversationHistory, tools, streamRenderer);
                LlmTraceLogger.logReasoning(log, "react interation=" + iteration, llmClient, response.reasoningContent());
                if (CancellationContext.isCancelled()) {
                    log.info("ReAct run cancelled after LLM response");
                    streamRenderer.finish();
                    pushStatus(budget, startNanos, "idle");
                    return "⏹️ 已取消当前任务。";
                }

                // 记录 Token 消耗
                budget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());

                // 如果存在调用工具
                if (response.hasToolCalls()) {
                    log.info("LLM requested {} tool call(s) in iteration {}", response.toolCalls().size(), iteration);
                    appendReasoning(reasoningTranscript, response.reasoningContent());

                    // 记录 toolCall
                    budget.recordToolCalls(response.toolCalls());

                    // 添加信息
                    conversationHistory.add(Message.assistant(response.reasoningContent(), response.content(), response.toolCalls()));

                    // 在工具执行前先 flush 渲染器
                    streamRenderer.resetBetweenTwoIterations();
                    renderer.appendToolCalls(response.toolCalls());

                    // 调用工具
                    List<ToolExecutionResult> results = executeToolCalls(response.toolCalls(), iteration);
                    for (ToolExecutionResult result : results) {
                        memoryManager.addToolResult(result.name(), result.result());
                        conversationHistory.add(Message.tool(result.id(), result.result()));
                    }

                    // 如果工具调用结果包含图片, 加入历史
                    appendImageToolMessages(results);
                    pushStatus(budget, startNanos, "running");

                    continue;
                }

                // 不调用工具, 结束迭代
                appendReasoning(reasoningTranscript, response.reasoningContent());
                conversationHistory.add(Message.assistant(response.content()));

                // 存入记忆
                memoryManager.addAssistantMessage(response.content());

                // 记录 token 使用情况
                memoryManager.recordTokenUsage(budget.totalInputTokens(), budget.totalOutputTokens(), budget.totalCachedInputTokens());
                pushStatus(budget, startNanos, "idle");
                log.info("ReAct run finished: inputTokens={}, outputTokens={}, reasoningChars={}, answerChars={}",
                        budget.totalInputTokens(),
                        budget.totalOutputTokens(),
                        response.reasoningContent() == null ? 0 : response.reasoningContent().length(),
                        response.content() == null ? 0 : response.content().length());
                if (log.isDebugEnabled()) {
                    log.debug("Assistant answer preview: {}", preview(response.content(), 500));
                }

                if (streamRenderer.hasStreamedOutput()) {
                    streamRenderer.finish();
                    return "";
                }
                streamRenderer.clearThinkingPanel();
                return formatUserFacingResponse(reasoningTranscript.toString(), response.content());
            } catch (Exception e) {
                log.error("LLM call failed in ReAct loop", e);
                streamRenderer.finish();
                return "模型调用失败: " + e.getMessage();
            }
        }
    }

    private List<ToolExecutionResult> executeToolCalls(List<ToolCall> toolCalls, int iteration) {
        List<ToolInvocation> invocations = new ArrayList<>();
        for (ToolCall toolCall : toolCalls) {
            String toolName = toolCall.function().name();
            String toolArgs = toolCall.function().arguments();
            log.info("Scheduling tool: {} (iteration={})", toolName, iteration);
            log.debug("Tool args [{}]: {}", toolName, toolArgs);
            invocations.add(new ToolInvocation(toolCall.id(), toolName, toolArgs));
        }

        if (invocations.size() > 1) {
            log.info("Executing {} tool calls in parallel (iteration={})", invocations.size(), iteration);
        }
        List<ToolExecutionResult> results = toolRegistry.executeTools(invocations);
        for (ToolExecutionResult result : results) {
            log.debug("Tool result preview [{}]: {}", result.name(), preview(result.result(), 300));
            emitToolResultSummary(result);
        }
        return results;
    }

    private void emitToolResultSummary(ToolExecutionResult result) {
        if (result == null || result.name() == null) {
            return;
        }
        String summary = switch (result.name()) {
            case "web_search" -> webSearchSummary(result);
            case "web_fetch" -> webFetchSummary(result);
            default -> "";
        };
        if (!summary.isBlank()) {
            renderer().stream().println(AnsiStyle.subtle("  → " + summary));
        }
    }

    private String webSearchSummary(ToolExecutionResult result) {
        String text = result.result() == null ? "" : result.result();
        if (text.startsWith("搜索失败") || text.startsWith("⚠️") || text.contains("未找到相关结果")) {
            return compactOneLine(text, 120);
        }
        long count = text.lines().filter(line -> line.matches("^\\d+\\.\\s+.*")).count();
        String query = extractJsonArg(result.argumentsJson(), "query");
        String label = query.isBlank() ? "搜索结果" : "搜索 \"" + query + "\"";
        return count > 0
                ? label + " 返回 " + count + " 条结果"
                : label + " 已返回结果";
    }

    private String webFetchSummary(ToolExecutionResult result) {
        String text = result.result() == null ? "" : result.result();
        String url = extractJsonArg(result.argumentsJson(), "url");
        String target = url.isBlank() ? "页面" : compactOneLine(url.replaceFirst("^https?://", ""), 80);
        if (text.startsWith("抓取失败") || text.startsWith("❌")) {
            return "抓取 " + target + " 失败: " + compactOneLine(text, 100);
        }
        String title = text.lines()
                .filter(line -> line.startsWith("📄 标题:"))
                .map(line -> line.substring("📄 标题:".length()).trim())
                .findFirst()
                .orElse("");
        String length = text.lines()
                .filter(line -> line.startsWith("📏 正文"))
                .findFirst()
                .orElse("");
        if (!title.isBlank() && !length.isBlank()) {
            return "抓取 " + target + " 完成: " + title + " · " + length.replace("📏 ", "");
        }
        if (!title.isBlank()) {
            return "抓取 " + target + " 完成: " + title;
        }
        return "抓取 " + target + " 完成";
    }

    private String extractJsonArg(String json, String key) {
        if (json == null || json.isBlank() || key == null || key.isBlank()) {
            return "";
        }
        try {
            return new ObjectMapper().readTree(json).path(key).asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String compactOneLine(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String value = text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("")
                .replaceAll("\\s+", " ");
        return value.length() > maxLength ? value.substring(0, Math.max(0, maxLength - 3)) + "..." : value;
    }

    private void appendImageToolMessages(List<ToolExecutionResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return;
        }

        for (ToolExecutionResult result : toolResults) {
            if (!result.hasImageParts()) {
                continue;
            }
            List<ContentPart> parts = new ArrayList<>();
            parts.add(ContentPart.text("工具 " + result.name() + " 返回了图片内容，请结合上面的工具文本结果分析。"));
            parts.addAll(result.imageParts());
            conversationHistory.add(Message.user(parts));
        }
    }


    private void injectPendingLspDiagnostics() {
        LspDiagnosticReport report = toolRegistry.flushPendingLspDiagnostics();
        if (report == null || report.isEmpty()) {
            return;
        }

        conversationHistory.add(Message.user(report.promptText()));
        renderer.stream().println(report.displayText());
        log.info("Injected LSP diagnostics into ReAct conversation");
    }

    /** 把当前预算/耗时/HITL 状态推送给 renderer 状态栏。 */
    private void pushStatus(AgentBudget budget, long startNanos, String phase) {
        try {
            String model = llmClient == null ? "—" : llmClient.getModelName();
            long totalTokens = budget == null ? 0L
                    : (long) (budget.totalInputTokens() + budget.totalOutputTokens());
            long contextWindow = llmClient == null ? 0L : llmClient.maxContextWindow();
            boolean hitl = Boolean.TRUE.equals(hitlEnabledSupplier.get());
            long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
            String cost = budget == null ? null : TokenUsageFormatter.estimatedCostCny(
                    llmClient,
                    budget.totalInputTokens(),
                    budget.totalOutputTokens(),
                    budget.totalCachedInputTokens());

            renderer().updateStatus(StatusInfo.tokens(
                    model,
                    contextWindow,
                    estimateCurrentContextTokens(),
                    budget == null ? 0L : budget.totalInputTokens(),
                    budget == null ? 0L : budget.totalOutputTokens(),
                    budget == null ? 0L : budget.totalCachedInputTokens(),
                    cost,
                    hitl,
                    elapsed,
                    phase == null || phase.isBlank()
                            ? (totalTokens > 0 || elapsed > 0 ? "running" : "idle")
                            : phase));
        } catch (Exception e) {
            log.debug("status push failed", e);
        }
    }

    private String prependSkillBodies(String userInput) {
        if (skillContextBuffer == null || skillContextBuffer.isEmpty()) {
            return userInput;
        }

        String drained = skillContextBuffer.drain();
        if (drained.isEmpty()) return userInput;
        return drained + "\n用户输入：\n" + userInput;
    }

    private void maybeCompactHistory() {
        if (historyCompactor == null) return;

        int trigger = memoryManager.getContextProfile().compressionTriggerTokens();
        try {
            boolean compacted = historyCompactor.compactIfNeeded(conversationHistory, trigger);
            if (compacted) {
                renderer.stream().println("📦 上下文接近窗口上限，已把早期对话压缩为摘要后继续。");
            }
        } catch (Exception e) {
            log.warn("conversationHistory compaction failed", e);
        }
    }

    private void storeExplicitBrowserMemoryHint(String userInput) {
        List<String> recentTexts = conversationHistory.stream()
                .map(Message::content)
                .filter(content -> content != null && !content.isBlank())
                .toList();

        String fact = ExplicitMemoryHints.browserLoginFact(userInput, recentTexts);
        if (fact != null && !fact.isBlank()) {
            memoryManager.storeFact(fact, "global");
        }
    }

    public String getSystemStatus() {
        return memoryManager.getSystemStatus();
    }

    /**
     * 将记忆注入到 system prompt 替换 conversationHistory[0]
     */
    private void updateSystemPromptWithMemory(String memoryContext) {
        conversationHistory.set(0, Message.system(buildSystemPrompt(memoryContext)));
    }

    private String buildSystemPrompt(String memoryContext) {
        return promptAssembler.assemble(PromptMode.AGENT, PromptContext.builder()
                .memoryContext(memoryContext)
                .externalContext(buildExternalContext())
                .skillIndex(buildSkillIndex())
                .build());
    }

    private void pruneHistoricalImagePayloads() {
        int messageCount = 0;
        int imageCount = 0;
        for (int i = 0; i < conversationHistory.size(); i++) {
            Message message = conversationHistory.get(i);
            int images = message.imagePartCount();
            if (images <= 0) {
                continue;
            }

            conversationHistory.set(i, message.withoutImageContent());
            messageCount++;
            imageCount += images;
        }
        if (imageCount > 0) {
            log.info("Pruned historical image payloads before new ReAct turn: messages={}, images={}",
                    messageCount, imageCount);
        }
    }


    private String buildSkillIndex() {
        if (skillRegistry == null) return "";
        try {
            return SkillIndexFormatter.format(skillRegistry.enabledSkills());
        } catch (Exception e) {
            log.warn("Failed to build skill index", e);
            return "";
        }
    }

    private String buildExternalContext() {
        if (!memoryManager.getContextProfile().mcpResourceIndexEnabled()) {
            return "";
        }

        try {
            String context = externalContextSupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("Failed to build external context", e);
            return "";
        }
    }

    private void appendReasoning(StringBuilder reasoningTranscript, String reasoningContent) {
        if (reasoningContent == null || reasoningContent.isBlank()) {
            return;
        }
        if (!reasoningTranscript.isEmpty()) {
            reasoningTranscript.append("\n\n");
        }

        reasoningTranscript.append(reasoningContent);
    }

    private String formatUserFacingResponse(String reasoningContent, String answer) {
        String normalizedReasoning = reasoningContent == null ? "" : reasoningContent.trim();
        String normalizedAnswer = answer == null ? "" : answer.trim();

        if (normalizedReasoning.isEmpty()) {
            return normalizedAnswer;
        }
        if (normalizedAnswer.isEmpty()) {
            return "思考过程:\n" + normalizedReasoning;
        }
        return "思考过程:\n" + normalizedReasoning + "\n\n回复:\n" + normalizedAnswer;
    }

    private String preview(String content, int maxLength) {
        if (content == null) {
            return "";
        }

        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.length() <= maxLength) {
            return normalized;
        }

        return normalized.substring(0, maxLength) + "...";
    }

    private String formatTokenStats(AgentBudget budget, long startNanos) {
        return TokenUsageFormatter.format(
                llmClient,
                budget.totalInputTokens(),
                budget.totalOutputTokens(),
                budget.totalCachedInputTokens(),
                startNanos);
    }

    public String getContextStatus() {
        ContextProfile profile = memoryManager.getContextProfile();
        int window = profile.maxContextWindow();
        int triggerTokens = profile.compressionTriggerTokens();

        // 分类估算 token 占用
        int systemTokens = 0, userTokens = 0, assistantTokens = 0, toolTokens = 0;
        int systemCount = 0, userCount = 0, assistantCount = 0, toolCount = 0;
        for (Message msg : conversationHistory) {
            int t = TokenBudget.estimateMessagesTokens(List.of(msg));
            switch (msg.role()) {
                case "system" -> { systemTokens += t; systemCount++; }
                case "user" -> { userTokens += t; userCount++; }
                case "assistant" -> { assistantTokens += t; assistantCount++; }
                case "tool" -> { toolTokens += t; toolCount++; }
            }
        }

        int messagesTokens = userTokens + assistantTokens + toolTokens;
        int toolsSchemaTokens = estimateToolsSchemaTokens();
        int total = systemTokens + messagesTokens + toolsSchemaTokens;
        double ratio = window > 0 ? (double) total / window : 0;
        int triggerRemaining = Math.max(0, triggerTokens - total);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 Context Usage   %s   window: %s%n",
                modelLabel(), formatTokens(window)));
        sb.append("\n  ").append(progressBar(ratio, 30))
                .append(String.format("  %d%%  (%s / %s)%n",
                        (int) Math.round(ratio * 100), formatTokens(total), formatTokens(window)));
        sb.append("\n  当前占用细分:\n");
        sb.append(formatLine("System prompt",      systemTokens,    window, systemCount));
        sb.append(formatLine("Tools schema",       toolsSchemaTokens, window, -1));
        sb.append(formatLine("Conversation",       messagesTokens, window,
                userCount + assistantCount + toolCount));
        sb.append("    ─────────────────────────────────\n");
        sb.append(String.format("    合计:              %8s  (%4.1f%%)%n",
                formatTokens(total), ratio * 100));
        sb.append(String.format("%n  压缩阈值: %s (%d%%)   距压缩还有: %s%n",
                formatTokens(triggerTokens),
                (int) (profile.compressionTriggerRatio() * 100),
                formatTokens(triggerRemaining)));
        sb.append("  MCP resources 自动索引: ")
                .append(profile.mcpResourceIndexEnabled() ? "开启" : "关闭（window 不足 32k）")
                .append("\n");
        sb.append("  prompt cache: ").append(profile.promptCacheMode()).append("\n");
        sb.append("\n");
        sb.append(memoryManager.getSystemStatus());
        return sb.toString();
    }

    private String modelLabel() {
        if (llmClient == null) return "(no model)";
        return llmClient.getModelName() + " (" + llmClient.getProviderName() + ")";
    }

    private int estimateToolsSchemaTokens() {
        try {
            return MemoryEntry.estimateTokens(
                    new ObjectMapper().writeValueAsString(toolRegistry.getToolDefinitions()));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String formatLine(String label, int tokens, int window, int count) {
        double pct = window > 0 ? (double) tokens / window * 100 : 0;
        String countLabel = count >= 0 ? String.format("  [%d 条]", count) : "";
        return String.format("    %-18s %8s  (%4.1f%%)%s%n",
                label + ":", formatTokens(tokens), pct, countLabel);
    }

    private static String progressBar(double ratio, int width) {
        ratio = Math.max(0, Math.min(1, ratio));
        int filled = (int) Math.round(ratio * width);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < width; i++) {
            bar.append(i < filled ? '█' : '░');
        }
        bar.append("]");
        return bar.toString();
    }

    private static String formatTokens(int tokens) {
        if (tokens >= 1_000_000) return String.format("%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1_000)     return String.format("%.1fk", tokens / 1_000.0);
        return String.valueOf(tokens);
    }

    private void logRequestContext(String scope, List<Tool> tools) {
        if (!log.isInfoEnabled()) {
            return;
        }

        int systemTokens = 0;
        int userTokens = 0;
        int assistantTokens = 0;
        int toolMessageTokens = 0;
        int imageParts = 0;
        int messages = 0;
        StringBuilder imageDetails = new StringBuilder();
        for (int messageIndex = 0; messageIndex < conversationHistory.size(); messageIndex++) {
            Message msg = conversationHistory.get(messageIndex);
            messages++;
            int tokens = TokenBudget.estimateMessagesTokens(List.of(msg));
            imageParts += msg.imagePartCount();
            appendImageDetails(imageDetails, msg, messageIndex);
            switch (msg.role()) {
                case "system" -> systemTokens += tokens;
                case "user" -> userTokens += tokens;
                case "assistant" -> assistantTokens += tokens;
                case "tool" -> toolMessageTokens += tokens;
                default -> {
                }
            }
        }

        int toolsSchemaTokens = 0;
        int toolCount = tools == null ? 0 : tools.size();
        if (tools != null && !tools.isEmpty()) {
            try {
                toolsSchemaTokens = MemoryEntry.estimateTokens(new ObjectMapper().writeValueAsString(tools));
            } catch (Exception e) {
                log.debug("Failed to estimate tools schema tokens", e);
            }
        }
        int estimatedTotal = systemTokens + userTokens + assistantTokens + toolMessageTokens + toolsSchemaTokens;
        log.info("LLM request context [{}]: messages={}, images={}, systemTokens={}, userTokens={}, assistantTokens={}, toolMessageTokens={}, tools={}, toolsSchemaTokens={}, estimatedTotal={}",
                scope, messages, imageParts, systemTokens, userTokens, assistantTokens, toolMessageTokens,
                toolCount, toolsSchemaTokens, estimatedTotal);
        if (!imageDetails.isEmpty()) {
            log.info("LLM request images [{}]: {}", scope, imageDetails);
        }
    }

    private void appendImageDetails(StringBuilder sb, Message msg, int messageIndex) {
        if (msg == null || !msg.hasContentParts()) {
            return;
        }

        for (int partIndex = 0; partIndex < msg.contentParts().size(); partIndex++) {
            ContentPart part = msg.contentParts().get(partIndex);
            if (part == null || !part.isImage()) {
                continue;
            }

            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            String payload = "image_url".equals(part.type()) ? part.imageUrl() : part.imageBase64();
            sb.append("#").append(messageIndex)
                    .append(".").append(partIndex)
                    .append(" role=").append(msg.role())
                    .append(" type=").append(part.type())
                    .append(" mime=").append(part.mimeType() == null ? "-" : part.mimeType())
                    .append(" payloadChars=").append(payload == null ? 0 : payload.length())
                    .append(" sha256=").append(shortSha256(payload));
        }
    }

    private String shortSha256(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }

    // 清空历史 (保留系统提示词), 不影响长期记忆
    public void clearHistory() {
        conversationHistory.clear();
        conversationHistory.add(Message.system(buildSystemPrompt("")));

        // 清空短期记忆
        memoryManager.clearShortTerm();
        if (skillContextBuffer != null) {
            skillContextBuffer.clear();
        }
    }

    /** 当前状态栏快照：ctx 表示下一轮请求仍会携带的上下文估算，不含累计 in/out 用量。 */
    public StatusInfo currentStatus(String phase) {
        String normalizedPhase = phase == null || phase.isBlank() ? "idle" : phase;
        String model = llmClient == null ? "—" : llmClient.getModelName();
        long contextWindow = llmClient == null ? 0L : llmClient.maxContextWindow();
        boolean hitl = Boolean.TRUE.equals(hitlEnabledSupplier.get());
        long contextTokens = estimateCurrentContextTokens();
        if ("idle".equals(normalizedPhase)) {
            return StatusInfo.idle(model, contextWindow, contextTokens, hitl);
        }
        return StatusInfo.active(model, contextWindow, contextTokens, hitl, normalizedPhase);
    }

    private long estimateCurrentContextTokens() {
        long messageTokens = TokenBudget.estimateMessagesTokens(conversationHistory);
        return Math.max(0L, messageTokens + estimateToolsSchemaTokens());
    }

        public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
        this.historyCompactor.setLlmClient(llmClient);
        this.memoryManager.setLlmClient(llmClient);
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
    }

    public void setHitlEnabledSupplier(Supplier<Boolean> supplier) {
        this.hitlEnabledSupplier = supplier == null ? () -> false : supplier;
    }
}
