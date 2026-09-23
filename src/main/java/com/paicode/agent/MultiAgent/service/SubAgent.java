package com.paicode.agent.MultiAgent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicode.agent.MultiAgent.entity.AgentMessage;
import com.paicode.agent.MultiAgent.constant.AgentRole;
import com.paicode.llm.entity.ChatResponse;
import com.paicode.llm.entity.Message;
import com.paicode.llm.entity.ToolCall;
import com.paicode.llm.service.DeepSeekClient;
import com.paicode.llm.service.stream.impl.SubAgentStreamRenderer;
import com.paicode.tool.entity.ToolExecutionResult;
import com.paicode.tool.entity.ToolInvocation;
import com.paicode.tool.service.register.ToolRegistry;
import com.paicode.utils.AnsiStyle;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Author beaker
 * @Date 2026/9/21 17:36
 * @Description 子代理
 */
@Getter
public class SubAgent {

    private static final Logger log = LoggerFactory.getLogger(SubAgent.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_ITERATIONS = 10;

    private final String name;
    private final AgentRole role;
    private final DeepSeekClient llmClient;
    private final ToolRegistry toolRegistry;
    private final List<Message> conversationHistory;

    // 各角色的系统提示词
    private static final String PLANNER_PROMPT = """
            你是一个任务规划专家。你的职责是分析用户的需求，将其拆解为清晰的执行步骤。

            请按以下 JSON 格式输出执行计划：
            {
                "summary": "任务摘要",
                "steps": [
                    {
                        "id": "step_1",
                        "description": "步骤描述，要具体明确",
                        "type": "FILE_READ | FILE_WRITE | COMMAND | ANALYSIS | VERIFICATION",
                        "dependencies": []
                    }
                ]
            }

            规则：
            1. 每个步骤必须有唯一的 id（如 step_1, step_2）
            2. dependencies 列出依赖的步骤 id
            3. 步骤描述要具体，让执行者能直接理解要做什么
            4. 简单任务可以只拆成 1-3 步
            5. 复杂任务拆成 5-10 步
            6. 不要为了凑步数引入无关操作
            7. 如果多个步骤可以独立完成，不要给它们添加依赖；保持 dependencies 为空，让编排器能并行分配给多个 Worker。
               例如同时读取 pom.xml、README.md、ROADMAP.md 时，应拆成 3 个无依赖 FILE_READ 步骤。
            8. 只有后一步确实需要前一步结果时，才写 dependencies。

            只输出 JSON，不要有其他内容。
            请用中文回复。
            """;

    private static final String WORKER_PROMPT = """
            你是一个任务执行专家。你的职责是根据给定的任务步骤，调用工具完成具体操作。

            可用工具：
            1. read_file - 读取文件内容，参数：{"path": "文件路径"}
            2. write_file - 写入文件内容，参数：{"path": "文件路径", "content": "内容"}
            3. list_dir - 列出目录内容，参数：{"path": "目录路径"}
            4. execute_command - 执行命令，参数：{"command": "命令"}
            5. create_project - 创建项目，参数：{"name": "名称", "type": "java|python|node"}
            6. search_code - 语义检索代码库，参数：{"query": "自然语言描述", "top_k": 5}

            如果任务涉及理解代码库，请优先使用 search_code 工具。
            对于当前项目内的文件，请优先使用 read_file 或 list_dir，不要用 execute_command 扫描 /、~ 或整个文件系统。
            execute_command 只适合在当前项目目录执行短时命令。
            同一轮返回多个工具调用时，系统会并行执行这些工具；如果工具之间有依赖关系，请分多轮调用。
            如果需要同时检查多个已知且互不依赖的文件或目录（例如同时读取 pom.xml、README.md、ROADMAP.md，
            或同时列出 src/main/java、src/test/java、src/main/resources），请在同一轮返回多个 read_file/list_dir 工具调用。
            如果是 ANALYSIS 或 VERIFICATION 类型任务，请直接输出分析结果。

            请用中文回复。
            """;

    private static final String REVIEWER_PROMPT = """
            你是一个质量检查专家。你的职责是检查执行结果是否正确、完整和高质量。

            检查要点：
            1. 任务是否按要求完成
            2. 结果是否正确，有无明显错误
            3. 是否遗漏了重要步骤或细节
            4. 输出格式是否规范

            请以 JSON 格式输出检查结果：
            {
                "approved": true 或 false,
                "summary": "检查摘要",
                "issues": ["问题1", "问题2"],
                "suggestions": ["建议1", "建议2"]
            }

            如果 approved 为 true，issues 为空即可。
            如果 approved 为 false，请详细说明问题并给出改进建议。
            只输出 JSON，不要有其他内容。
            请用中文回复。
            """;

    public SubAgent(String name, AgentRole role, DeepSeekClient llmClient, ToolRegistry toolRegistry) {
        this.name = name;
        this.role = role;
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;

        this.conversationHistory = new ArrayList<>();
        this.conversationHistory.add(Message.system(getSystemPrompt(role)));
    }

    /**
     * 获取系统提示词
     */
    private String getSystemPrompt(AgentRole role) {
        return switch (role) {
            case PLANNER -> PLANNER_PROMPT;
            case WORKER -> WORKER_PROMPT;
            case REVIEWER -> REVIEWER_PROMPT;
        };
    }

    /**
     * 执行任务, 返回消息, 默认输出到 System.out
     */
    public AgentMessage execute(AgentMessage task) {
        return execute(task, System.out);
    }

    /**
     * 执行任务, 将流式输出写入指定的 PrintStream, 并发执行时每个任务传入独立的 PrintStream
     * 避免多个 Agent 同时写入 System.out 造成交错
     */
    public AgentMessage execute(AgentMessage task, PrintStream out) {
        log.info("[{}] executing task from {}: type={}", name, task.fromAgent(), task.type());
        String taskContent = task.content();

        // 将 task 注入历史
        conversationHistory.add(Message.user(taskContent));

        SubAgentStreamRenderer streamRenderer = new SubAgentStreamRenderer(name , role, out);

        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration ++;

            try {
                ChatResponse response = llmClient.chat(
                        conversationHistory,
                        shouldUseTools(role) ? toolRegistry.getTools() : null,
                        streamRenderer
                );

                // 执行工具调用, 将结果加入记忆
                if (response.hasToolCalls()) {
                    conversationHistory.add(Message.assistant(
                            response.reasoningContent(),
                            response.content(),
                            response.toolCalls()
                    ));

                    printToolCalls(out ,response.toolCalls());

                    streamRenderer.resetBetweenTwoIterations();

                    List<ToolExecutionResult> results = executeToolCalls(response.toolCalls());
                    for (ToolExecutionResult result : results) {
                        conversationHistory.add(Message.tool(result.id(), result.result()));
                    }

                    continue;
                }

                // 没有工具调用, 返回最终结果
                conversationHistory.add(Message.assistant(response.reasoningContent(), response.content()));
                streamRenderer.finish();

                return AgentMessage.result(name, role, response.content());
            } catch (Exception e) {
                log.error("[{}] LLM call failed", name, e);
                streamRenderer.finish();
                return AgentMessage.error(name, role, "LLM 调用失败: " + e.getMessage());
            }
        }

        streamRenderer.finish();
        return AgentMessage.error(name, role, "达到最大迭代次数限制, 任务未完成");
    }



    /**
     * 执行任务 (带上下文), 用于 worker 额外接收上下文
     */
    public AgentMessage executeWithContext(AgentMessage task, String context) {
        return executeWithContext(task, context, System.out);
    }

    public AgentMessage executeWithContext(AgentMessage task, String context, PrintStream out) {
        String enrichedContent = task.content();
        if (context != null && !context.isEmpty()) {
            enrichedContent = context + "\n\n当前任务：" + task.content();
        }
        AgentMessage enrichedTask = new AgentMessage(task.fromAgent(), task.fromRole(), enrichedContent, task.type());

        return execute(enrichedTask, out);
    }

    /**
     * 检查结果 (Reviewer 专用)
     */
    public AgentMessage review(String originalTask, String executionResult) {
        return review(originalTask, executionResult, System.out);
    }

    public AgentMessage review(String originalTask, String executionResult, PrintStream out) {
        String reviewInput = "原始任务：" + originalTask + "\n\n执行结果：\n" + executionResult;
        AgentMessage reviewTask = AgentMessage.task("orchestrator", reviewInput);

        return execute(reviewTask, out);
    }

    private List<ToolExecutionResult> executeToolCalls(List<ToolCall> toolCalls) {
        List<ToolInvocation> invocations = new ArrayList<>();
        for (ToolCall toolCall : toolCalls) {
            String toolName = toolCall.function().name();
            String toolArgs = toolCall.function().arguments();
            log.info("[{}] scheduling tool: {}", name, toolName);
            log.debug("[{}] tool args [{}]: {}", name, toolName, toolArgs);
            invocations.add(new ToolInvocation(toolCall.id(), toolName, toolArgs));
        }

        if (invocations.size() > 1) {
            log.info("[{}] executing {} tool calls in parallel", name, invocations.size());
        }
        return toolRegistry.executeTools(invocations);
    }

    private static void printToolCalls(PrintStream out, List<ToolCall> toolCalls) {
        Map<String, List<ToolCall>> grouped = new LinkedHashMap<>();
        for (ToolCall tc : toolCalls) {
            grouped.computeIfAbsent(tc.function().name(), k -> new ArrayList<>()).add(tc);
        }
        for (var group : grouped.entrySet()) {
            String toolName = group.getKey();
            List<ToolCall> calls = group.getValue();
            out.println(AnsiStyle.subtle("  " + toolLabel(toolName, calls.size())));
            for (ToolCall tc : calls) {
                String detail = extractKeyParam(toolName, tc.function().arguments());
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

    /**
     * 清空对话历史, 用于处理下一个独立任务
     */
    public void clearHistory() {
        Message systemMsg = conversationHistory.get(0);
        conversationHistory.clear();
        conversationHistory.add(systemMsg);
    }

    /**
     * 只有工作者需要调用工具
     */
    private boolean shouldUseTools(AgentRole role) {
        return role == AgentRole.WORKER;
    }
}
