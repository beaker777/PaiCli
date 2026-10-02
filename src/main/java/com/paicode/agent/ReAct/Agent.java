package com.paicode.agent.ReAct;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicode.agent.constant.ExitReason;
import com.paicode.agent.service.AgentBudget;
import com.paicode.context.ContextProfile;
import com.paicode.context.TokenUsageFormatter;
import com.paicode.llm.entity.ChatResponse;
import com.paicode.llm.entity.Message;
import com.paicode.llm.entity.ToolCall;
import com.paicode.llm.service.model.LlmClient;
import com.paicode.llm.service.stream.impl.AgentStreamRenderer;
import com.paicode.memory.entity.MemoryEntry;
import com.paicode.memory.service.compress.ConversationHistoryCompactor;
import com.paicode.memory.service.compress.TokenBudget;
import com.paicode.memory.service.hint.ExplicitMemoryHints;
import com.paicode.memory.service.manager.MemoryManager;
import com.paicode.renderer.service.manage.Renderer;
import com.paicode.renderer.service.manage.impl.PlainRenderer;
import com.paicode.runtime.CancellationContext;
import com.paicode.skill.service.buffer.SkillContextBuffer;
import com.paicode.skill.service.manage.SkillRegistry;
import com.paicode.skill.service.parser.SkillIndexFormatter;
import com.paicode.tool.entity.ToolExecutionResult;
import com.paicode.tool.entity.ToolInvocation;
import com.paicode.tool.service.register.ToolRegistry;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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

    // 系统提示词
    private static final String SYSTEM_PROMPT = """
            你是一个智能编程 PaiCode Agent，可以帮助用户完成各种任务。

            你可以使用以下工具来完成任务：
            1. read_file - 读取文件内容
            2. write_file - 写入文件内容
            3. list_dir - 列出目录内容
            4. execute_command - 执行Shell命令
            5. create_project - 创建新项目结构
            6. search_code - 语义检索代码库, 参数: {"query": "自然语言描述", "top_k": 5}
            7. web_search - 搜索互联网获取实时信息（最新版本、官方文档、技术资讯等），参数：{"query": "搜索关键词", "top_k": 5}
            8. web_fetch - 抓取已知 URL 并返回正文 Markdown，参数：{"url": "https://...", "max_chars": 8000}
            9. save_memory - 在用户明确要求“记一下/记住/以后记得”时保存长期记忆，参数：{"fact": "精炼稳定事实"}
            10. mcp__{server}__{tool} - MCP server 动态提供的外部工具，具体参数以工具 schema 为准

            当需要操作文件、执行命令或创建项目时，请使用工具调用。
            使用工具后，根据工具返回的结果继续思考下一步行动。
            当用户明确说“记一下”“记住”“以后记得”或要求保存长期偏好/稳定事实时，必须调用 save_memory；
            只保存跨会话仍成立的精炼事实，不保存一次性任务请求、临时文件名、模型猜测或当前轮执行计划。
            对于当前项目内的文件和代码, 请优先使用 read_file, list_dir, search_code.
            execute_command 只适合在当前项目目录执行短时间的命令, (如 git status, mvn test), 不要用它扫描整个文件系统.
            安全策略硬规则（HITL 之外的兜底，无法绕过，请提前规避）：
            - read_file / write_file / list_dir / create_project 的路径必须在项目根之内，绝对路径或 .. 越界会被拒绝
            - write_file 单文件 5MB 上限
            - execute_command 禁止 sudo、rm -rf 全盘或用户目录、mkfs、dd 写裸设备、fork bomb、curl|sh、find /、chmod 777 /、shutdown
            - 若调用被策略拒绝（结果以 "🛡️ 策略拒绝" 开头），不要原样重试，改用项目内相对路径或更安全的方式
            - MCP 工具来自外部 server，默认会触发 HITL 审批与审计；除非任务确实需要该 server 能力，否则优先使用内置工具
            - 长上下文模式下，system prompt 可能包含 MCP resources 索引（仅 URI / 描述，不含正文）；需要正文时再读取对应 resource
            同一轮返回多个工具调用时，系统会并行执行这些工具；如果工具之间有依赖关系，请分多轮调用。
            如果需要同时检查多个已知且互不依赖的文件或目录（例如同时读取 pom.xml、README.md、ROADMAP.md，
            或同时列出 src/main/java、src/test/java、src/main/resources），请在同一轮返回多个 read_file/list_dir 工具调用。
            
            工具选择优先级：
            - 代码库相关问题（"这个类是干什么的"、"哪里用了某个功能"）→ search_code，不要走 web_search
            - 训练数据已知的稳定知识（语法、稳定 API、基础概念）→ 直接回答，不要联网
            - 时效性 / 最新信息 / 不确定的事实 → web_search 找入口，找到 URL 后再 web_fetch 拿全文
            - 已经有具体 URL → 直接 web_fetch，不要再 web_search 一次
            - web_fetch 拿到空正文（提示 SPA / 防爬墙）→ 这是已知边界，告知用户即可，不要反复重试

            工具选择 - 网页内容获取：
            - 静态 / SSR 页面（博客、官方文档、wiki、GitHub README）→ web_fetch
            - SPA / React / Vue / 客户端渲染、需要 JS 才有内容 → 浏览器 MCP（mcp__chrome-devtools__navigate_page + take_snapshot）
            - 防爬墙、需要登录态、需要表单交互（点击/输入/提交）→ 浏览器 MCP
            - 微信公众号文章 (mp.weixin.qq.com)、知乎专栏、推特、小红书等 → 浏览器 MCP（这些站点 web_fetch 通常拿不到正文）
            - 已知 URL → 直接 web_fetch 试一次，失败再用浏览器 MCP

            工具选择 - 浏览器操作：
            - 优先 mcp__chrome-devtools__take_snapshot（结构化 DOM 文本，LLM 能直接理解）
            - 不要默认使用 take_screenshot，除非用户明确要看页面截图或做 UI 验收
            - 表单填写优先 mcp__chrome-devtools__fill_form，一次性填多字段
            - 等待异步加载使用 mcp__chrome-devtools__wait_for（指定文本或选择器出现）
            - 如果浏览器 MCP 返回登录页、权限不足、需要认证，或用户明确要求访问登录后页面，先调用 browser_connect 自动连接已允许远程调试的本机 Chrome，再重新打开原 URL；不要让用户先手动切换
            - 公开页面（如微信公众号文章、普通文档、新闻页面）不需要登录态时，不要提前调用 browser_connect，直接用 isolated 浏览器 MCP 即可
            - shared 模式下敏感页面的点击、填写、脚本执行等改写操作会强制单步 HITL；close_page 只能关闭 PaiCLI 自己创建的 tab

            如果提供了相关记忆, 请参考记忆来辅助决策.

            请用中文回复用户。
            """;

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

        conversationHistory.add(Message.system(SYSTEM_PROMPT));
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

        // 存入短期记忆
        memoryManager.addUserMessage(userInput);
        storeExplicitBrowserMemoryHint(userInput);

        // 检索相关长期记忆, 注入到 system prompt
        ContextProfile contextProfile = memoryManager.getContextProfile();
        String memoryContext = memoryManager.buildContextForQuery(userInput, contextProfile.memoryContextTokens());
        updateSystemPromptWithMemory(memoryContext);

        // 将用户输入添加到历史
        String userMessage = prependSkillBodies(userInput);
        conversationHistory.add(Message.user(userMessage));
        StringBuilder reasoningTranscript = new StringBuilder();
        AgentStreamRenderer streamRenderer = new AgentStreamRenderer(renderer.stream());

        long startNanos = System.nanoTime();
        AgentBudget budget = AgentBudget.fromLlmClient(llmClient);

        while (true) {
            if (CancellationContext.isCancelled()) {
                log.info("ReAct run cancelled before iteration");
                return "⏹️ 已取消当前任务。";
            }

            // 判断是否需要压缩对话
            maybeCompactHistory();
            ExitReason exitReason = budget.check();
            if (exitReason != ExitReason.WITHIN_BUDGET) {
                String statsLine = formatTokenStats(budget, startNanos);
                String description = budget.describeExit(exitReason);

                log.warn("ReAct run exhausted budget: reason={}, iteration={}, tokens={}/{}",
                        exitReason, budget.iteration(),
                        budget.totalInputTokens() + budget.totalOutputTokens(), budget.tokenBudget());
                return "❌ " + description + "\n\n" + statsLine;
            }

            int iteration = budget.beginIteration();
            try {
                ChatResponse response = llmClient.chat(conversationHistory, toolRegistry.getTools(), streamRenderer);
                if (CancellationContext.isCancelled()) {
                    log.info("ReAct run cancelled after LLM response");
                    return "⏹️ 已取消当前任务。";
                }

                // 记录 Token 消耗
                budget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());

                // 重置渲染器的状态, 避免内容错位
                streamRenderer.resetBetweenTwoIterations();

                // 如果存在调用工具
                if (response.hasToolCalls()) {
                    log.info("LLM requested {} tool call(s) in iteration {}", response.toolCalls().size(), iteration);
                    appendReasoning(reasoningTranscript, response.reasoningContent());

                    // 输出 toolCall 内容
                    renderer.appendToolCalls(response.toolCalls());

                    // 记录 toolCall
                    budget.recordToolCalls(response.toolCalls());

                    // 添加信息
                    conversationHistory.add(Message.assistant(response.reasoningContent(), response.content(), response.toolCalls()));

                    // 调用工具
                    List<ToolExecutionResult> results = executeToolCalls(response.toolCalls(), iteration);
                    for (ToolExecutionResult result : results) {
                        memoryManager.addToolResult(result.name(), result.result());
                        conversationHistory.add(Message.tool(result.id(), result.result()));
                    }

                    continue;
                }

                // 不调用工具, 结束迭代
                appendReasoning(reasoningTranscript, response.reasoningContent());
                conversationHistory.add(Message.assistant(response.reasoningContent(), response.content()));

                // 存入记忆
                memoryManager.addAssistantMessage(response.content());

                // 记录 token 使用情况
                memoryManager.recordTokenUsage(budget.totalInputTokens(), budget.totalOutputTokens(), budget.totalCachedInputTokens());
                log.info("ReAct run finished: inputTokens={}, outputTokens={}, reasoningChars={}, answerChars={}",
                        budget.totalInputTokens(),
                        budget.totalOutputTokens(),
                        response.reasoningContent() == null ? 0 : response.reasoningContent().length(),
                        response.content() == null ? 0 : response.content().length());
                if (log.isDebugEnabled()) {
                    log.debug("Assistant answer preview: {}", preview(response.content(), 500));
                }

                String statsLine = formatTokenStats(budget, startNanos);
                if (streamRenderer.hasStreamedOutput()) {
                    streamRenderer.finish();
                    renderer.stream().println(statsLine);
                    return "";
                }

                return formatUserFacingResponse(reasoningTranscript.toString(), response.content())
                        + "\n\n" + statsLine;
            } catch (Exception e) {
                log.error("LLM call failed in ReAct loop", e);
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
        }
        return results;
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
                System.out.println("📦 上下文接近窗口上限，已把早期对话压缩为摘要后继续。");
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
            memoryManager.storeFact(fact);
        }
    }

    public String getSystemStatus() {
        return memoryManager.getSystemStatus();
    }

    /**
     * 将记忆注入到 system prompt 替换 conversationHistory[0]
     */
    private void updateSystemPromptWithMemory(String memoryContext) {
        String externalContext = buildExternalContext();
        String skillIndex = buildSkillIndex();

        boolean hasMemory = memoryContext != null && !memoryContext.isEmpty();
        boolean hasExternal = !externalContext.isEmpty();
        boolean hasSkill = !skillIndex.isEmpty();
        if (!hasMemory && !hasExternal && !hasSkill) {
            // 恢复原始 system prompt
            conversationHistory.set(0, Message.system(SYSTEM_PROMPT));
            return;
        }
        StringBuilder enrichedPrompt = new StringBuilder(SYSTEM_PROMPT);
        if (hasMemory) {
            enrichedPrompt.append("\n").append(memoryContext);
        }
        if (hasExternal) {
            enrichedPrompt.append("\n").append(externalContext);
        }
        if (hasSkill) {
            enrichedPrompt.append("\n").append(skillIndex);
        }
        conversationHistory.set(0, Message.system(enrichedPrompt.toString()));
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

    // 清空历史 (保留系统提示词), 不影响长期记忆
    public void clearHistory() {
        Message systemPrompt = conversationHistory.get(0);
        conversationHistory.clear();
        conversationHistory.add(systemPrompt);

        // 清空短期记忆
        memoryManager.clearShortTerm();
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
}
