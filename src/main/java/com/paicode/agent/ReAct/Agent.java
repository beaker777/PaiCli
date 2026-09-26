package com.paicode.agent.ReAct;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicode.agent.constant.ExitReason;
import com.paicode.agent.service.AgentBudget;
import com.paicode.context.ContextProfile;
import com.paicode.context.TokenUsageFormatter;
import com.paicode.llm.entity.ChatResponse;
import com.paicode.llm.entity.Message;
import com.paicode.llm.entity.ToolCall;
import com.paicode.llm.service.model.LlmClient;
import com.paicode.llm.service.model.impl.DeepSeekClient;
import com.paicode.llm.service.stream.impl.AgentStreamListener;
import com.paicode.memory.entity.MemoryEntry;
import com.paicode.memory.service.compress.TokenBudget;
import com.paicode.memory.service.manager.MemoryManager;
import com.paicode.runtime.CancellationContext;
import com.paicode.tool.entity.ToolExecutionResult;
import com.paicode.tool.entity.ToolInvocation;
import com.paicode.tool.service.register.ToolRegistry;
import com.paicode.utils.AnsiStyle;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * @Author beaker
 * @Date 2026/9/9 19:27
 * @Description Agent
 */
@Getter
@Setter
public class Agent {

    private final static ObjectMapper mapper = new ObjectMapper();
    private final static Logger log = LoggerFactory.getLogger(Agent.class);

    private LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final List<Message> conversationHistory;
    private final MemoryManager memoryManager;
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
            9. mcp__{server}__{tool} - MCP server 动态提供的外部工具，具体参数以工具 schema 为准

            当需要操作文件、执行命令或创建项目时，请使用工具调用。
            使用工具后，根据工具返回的结果继续思考下一步行动。
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
            - 控制台错误排查使用 list_console_messages；网络请求查看使用 list_network_requests + get_network_request

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
        memoryManager = new MemoryManager(llmClient);
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());

        conversationHistory.add(Message.system(SYSTEM_PROMPT));
    }

    // 运行
    public String run(String userInput) {
        log.info("ReAct run started: inputLength={}", userInput == null ? 0 : userInput.length());

        // 存入短期记忆
        memoryManager.addUserMessage(userInput);

        // 检索相关长期记忆, 注入到 system prompt
        ContextProfile contextProfile = memoryManager.getContextProfile();
        String memoryContext = memoryManager.buildContextForQuery(userInput, 500);
        updateSystemPromptWithMemory(memoryContext);

        // 将用户输入添加到历史
        AgentStreamListener streamListener = new AgentStreamListener();
        conversationHistory.add(Message.user(userInput));
        StringBuilder reasoningTranscript = new StringBuilder();

        long startNanos = System.nanoTime();
        AgentBudget budget = AgentBudget.fromLlmClient(llmClient);

        while (true) {
            if (CancellationContext.isCancelled()) {
                log.info("ReAct run cancelled before iteration");
                return "⏹️ 已取消当前任务。";
            }

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
                ChatResponse response = llmClient.chat(conversationHistory, toolRegistry.getTools(), streamListener);
                if (CancellationContext.isCancelled()) {
                    log.info("ReAct run cancelled after LLM response");
                    return "⏹️ 已取消当前任务。";
                }

                // 记录 Token 消耗
                budget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());

                // 重置渲染器的状态, 避免内容错位
                streamListener.resetBetweenTwoIterations();

                // 如果存在调用工具
                if (response.hasToolCalls()) {
                    log.info("LLM requested {} tool call(s) in iteration {}", response.toolCalls().size(), iteration);
                    appendReasoning(reasoningTranscript, response.reasoningContent());

                    // 输出 toolCall 内容
                    printToolCalls(System.out, response.toolCalls());

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
                if (streamListener.hasStreamedOutput()) {
                    streamListener.finish();
                    System.out.println(statsLine);
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

    // 清空历史 (保留系统提示词), 不影响长期记忆
    public void clearHistory() {
        Message systemPrompt = conversationHistory.get(0);
        conversationHistory.clear();
        conversationHistory.add(systemPrompt);

        // 清空短期记忆
        memoryManager.clearShortTerm();
    }

    public String getSystemStatus() {
        return memoryManager.getSystemStatus();
    }

    /**
     * 将记忆注入到 system prompt 替换 conversationHistory[0]
     */
    private void updateSystemPromptWithMemory(String memoryContext) {
        String externalContext = buildExternalContext();
        if ((memoryContext == null || memoryContext.isBlank()) && externalContext.isBlank()) {
            // 没有记忆的时候重置回默认的 system prompt, 避免上下文污染
            conversationHistory.set(0, Message.system(SYSTEM_PROMPT));
        } else {
            StringBuilder enrichedPrompt = new StringBuilder(SYSTEM_PROMPT);
            if (memoryContext != null && !memoryContext.isBlank()) {
                enrichedPrompt.append("\n").append(memoryContext);
            }
            if (!externalContext.isEmpty()) {
                enrichedPrompt.append("\n").append(externalContext);
            }
            conversationHistory.set(0, Message.system(enrichedPrompt.toString()));
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

    /**
     * 按照工具类型分组输出 ToolCall 内容
     */
    private static void printToolCalls(PrintStream out, List<ToolCall> toolCalls) {
        Map<String, List<ToolCall>> grouped = new LinkedHashMap<>();
        for (ToolCall toolCall : toolCalls) {
            grouped.computeIfAbsent(toolCall.function().name(), k -> new ArrayList<>()).add(toolCall);
        }

        for (Map.Entry<String, List<ToolCall>> group : grouped.entrySet()) {
            String toolName = group.getKey();
            List<ToolCall> calls = group.getValue();
            out.println(AnsiStyle.subtle("  " + toolLabel(toolName, calls.size())));

            for (ToolCall call : calls) {
                String detail = extractKeyParam(toolName, call.function().arguments());
                if (!detail.isEmpty()) {
                    out.println(AnsiStyle.subtle("    └ " + detail));
                }
            }
        }
    }

    private static String toolLabel(String toolName, int count) {
        return switch (toolName) {
            case "read_file" -> "📖 读取 " + count + " 个文件";
            case "write_file" -> "✏️ 写入 " + count + " 个文件";
            case "list_dir" -> "📂 列出 " + count + " 个目录";
            case "execute_command" -> "⚡ 执行 " + count + " 条命令";
            case "create_project" -> "🏗️ 创建 " + count + " 个项目";
            case "search_code" -> "🔍 搜索代码 " + count + " 次";
            case "web_search" -> "🌐 联网搜索 " + count + " 次";
            case "web_fetch" -> "📰 抓取 " + count + " 个网页";
            default -> toolName.startsWith("mcp__") ? formatMcpLabel(toolName, count)
                    : "🔧 " + toolName + " × " + count;
        };
    }

    private static String formatMcpLabel(String toolName, int count) {
        String[] parts = toolName.split("__", 3);
        String display = parts.length == 3 ? parts[1] + "." + parts[2] : toolName;
        return count == 1
                ? "🔌 调用 MCP 工具 " + display
                : "🔌 调用 MCP 工具 " + display + " × " + count;
    }

    private static String extractKeyParam(String toolName, String argsJson) {
        try {
            JsonNode node = mapper.readTree(argsJson);
            String key = switch (toolName) {
                case "read_file", "write_file", "list_dir" -> "path";
                case "execute_command" -> "command";
                case "create_project" -> "name";
                case "search_code", "web_search" -> "query";
                case "web_fetch" -> "url";
                default -> null;
            };

            if (key == null) {
                return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
            }

            String value = node.path(key).asText("");
            if (value.length() > 80) {
                value = value.substring(0, 77) + "...";
            }
            return value;
        } catch (Exception e) {
            return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
        }
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


    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
        this.memoryManager.setLlmClient(llmClient);
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
    }
}
