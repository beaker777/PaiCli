package com.paicode.agent.ReAct;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicode.llm.entity.ChatResponse;
import com.paicode.llm.entity.Message;
import com.paicode.llm.entity.ToolCall;
import com.paicode.llm.service.model.LlmClient;
import com.paicode.llm.service.model.impl.DeepSeekClient;
import com.paicode.llm.service.stream.impl.AgentStreamListener;
import com.paicode.memory.service.manager.MemoryManager;
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

    // 最大迭代次数
    private static final int MAX_ITERATIONS = 10;

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

            当需要操作文件、执行命令或创建项目时，请使用工具调用。
            使用工具后，根据工具返回的结果继续思考下一步行动。
            对于当前项目内的文件和代码, 请优先使用 read_file, list_dir, search_code.
            execute_command 只适合在当前项目目录执行短时间的命令, (如 git status, mvn test), 不要用它扫描整个文件系统.
            同一轮返回多个工具调用时，系统会并行执行这些工具；如果工具之间有依赖关系，请分多轮调用。
            如果需要同时检查多个已知且互不依赖的文件或目录（例如同时读取 pom.xml、README.md、ROADMAP.md，
            或同时列出 src/main/java、src/test/java、src/main/resources），请在同一轮返回多个 read_file/list_dir 工具调用。
            
            如果用户询问和代码库相关的问题 (如"这个类是干什么的", "哪里用了某个功能").
            请优先使用 search_code 工具检索相关代码, 再基于检索结果回答.

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

        conversationHistory.add(Message.system(SYSTEM_PROMPT));
    }

    // 运行
    public String run(String userInput) {
        log.info("ReAct run started: inputLength={}", userInput == null ? 0 : userInput.length());

        // 存入短期记忆
        memoryManager.addUserMessage(userInput);

        // 检索相关长期记忆, 注入到 system prompt
        String memoryContext = memoryManager.buildContextForQuery(userInput, 500);
        updateSystemPromptWithMemory(memoryContext);

        // 将用户输入添加到历史
        AgentStreamListener streamListener = new AgentStreamListener();
        conversationHistory.add(Message.user(userInput));
        StringBuilder reasoningTranscript = new StringBuilder();

        long startNanos = System.nanoTime();
        int totalInputTokens = 0;
        int totalOutputTokens = 0;

        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration ++;

            try {
                // 调用模型
                ChatResponse response = llmClient.chat(conversationHistory, toolRegistry.getTools(), streamListener);

                totalInputTokens += response.inputTokens();
                totalOutputTokens += response.outputTokens();

                // 重置渲染器的状态, 避免内容错位
                streamListener.resetBetweenTwoIterations();

                // 如果存在调用工具
                if (response.hasToolCalls()) {
                    log.info("LLM requested {} tool call(s) in iteration {}", response.toolCalls().size(), iteration);
                    appendReasoning(reasoningTranscript, response.reasoningContent());

                    // 输出 toolCall 内容
                    printToolCalls(System.out, response.toolCalls());

                    // 添加信息
                    conversationHistory.add(Message.assistant(response.reasoningContent(), response.content(), response.toolCalls()));

                    // 调用工具
                    List<ToolExecutionResult> results = executeToolCalls(response.toolCalls(), iteration);
                    for (ToolExecutionResult result : results) {
                        memoryManager.addToolResult(result.name(), result.result());
                        conversationHistory.add(Message.tool(result.id(), result.result()));
                    }
                } else {
                    appendReasoning(reasoningTranscript, response.reasoningContent());

                    // 不调用工具, 结束迭代
                    conversationHistory.add(Message.assistant(response.reasoningContent(), response.content()));

                    // 存入记忆
                    memoryManager.addAssistantMessage(response.content());

                    // 记录 token 使用情况
                    memoryManager.recordTokenUsage(totalInputTokens, totalOutputTokens);
                    log.info("ReAct run finished: inputTokens={}, outputTokens={}, reasoningChars={}, answerChars={}",
                            totalInputTokens,
                            totalOutputTokens,
                            response.reasoningContent() == null ? 0 : response.reasoningContent().length(),
                            response.content() == null ? 0 : response.content().length());
                    if (log.isDebugEnabled()) {
                        log.debug("Assistant answer preview: {}", preview(response.content(), 500));
                    }

                    String statsLine = formatTokenStats(totalInputTokens, totalOutputTokens, startNanos);
                    if (streamListener.hasStreamedOutput()) {
                        streamListener.finish();
                        System.out.println(statsLine);
                        return "";
                    }

                    return formatUserFacingResponse(reasoningTranscript.toString(), response.content())
                            + "\n\n" + statsLine;
                }
            } catch (Exception e) {
                log.error("LLM call failed in ReAct loop", e);
                return "模型调用失败: " + e.getMessage();
            }
        }

        String stasLine = formatTokenStats(totalInputTokens, totalOutputTokens, startNanos);
        log.warn("ReAct run reached max iterations: {}", MAX_ITERATIONS);
        return "超过最大迭代次数\n\n" + stasLine;
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
        if (memoryContext == null || memoryContext.isBlank()) {
            // 没有记忆的时候重置回默认的 system prompt, 避免上下文污染
            conversationHistory.set(0, Message.system(SYSTEM_PROMPT));
        } else {
            String enrichedPrompt = SYSTEM_PROMPT + "\n" + memoryContext;
            conversationHistory.set(0, Message.system(enrichedPrompt));
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
            default -> "🔧 " + toolName + " × " + count;
        };
    }

    private static String extractKeyParam(String toolName, String argsJson) {
        try {
            JsonNode node = mapper.readTree(argsJson);
            String key = switch (toolName) {
                case "read_file", "write_file", "list_dir" -> "path";
                case "execute_command" -> "command";
                case "create_project" -> "name";
                case "search_code" -> "query";
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

    private static String formatTokenStats(int inputTokens, int outputTokens, long startNanos) {
        double elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        return AnsiStyle.subtle(String.format(
                "📊 Token: %d 输入 / %d 输出 / %d 合计 | ⏱ %.1fs",
                inputTokens, outputTokens, inputTokens + outputTokens, elapsedSeconds));
    }

    public String getContextStatus() {
        int systemCount = 0, userCount = 0, assistantCount = 0, toolCount = 0;
        int totalChars = 0;
        for (Message msg : conversationHistory) {
            totalChars += msg.content() == null ? 0 : msg.content().length();
            switch (msg.role()) {
                case "system" -> systemCount++;
                case "user" -> userCount++;
                case "assistant" -> assistantCount++;
                case "tool" -> toolCount++;
            }
        }
        int totalMessages = conversationHistory.size();
        int rounds = userCount;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("对话上下文: %d 条消息, %d 轮对话, ~%d 字符\n", totalMessages, rounds, totalChars));
        sb.append(String.format("   system: %d / user: %d / assistant: %d / tool: %d\n", systemCount, userCount, assistantCount, toolCount));
        sb.append(memoryManager.getSystemStatus());
        return sb.toString();
    }
}
