package com.paicode.agent.MultiAgent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicode.agent.MultiAgent.entity.AgentMessage;
import com.paicode.agent.MultiAgent.constant.AgentRole;
import com.paicode.agent.constant.ExitReason;
import com.paicode.agent.service.AgentBudget;
import com.paicode.context.ContextProfile;
import com.paicode.context.TokenUsageFormatter;
import com.paicode.image.service.ImageReferenceParser;
import com.paicode.llm.entity.ChatResponse;
import com.paicode.llm.entity.ContentPart;
import com.paicode.llm.entity.Message;
import com.paicode.llm.entity.ToolCall;
import com.paicode.llm.service.log.LlmTraceLogger;
import com.paicode.llm.service.model.LlmClient;
import com.paicode.llm.service.model.impl.DeepSeekClient;
import com.paicode.llm.service.stream.impl.SubAgentStreamRenderer;
import com.paicode.lsp.entity.LspDiagnosticReport;
import com.paicode.memory.service.compress.ConversationHistoryCompactor;
import com.paicode.prompt.constant.PromptMode;
import com.paicode.prompt.entity.PromptContext;
import com.paicode.prompt.service.PromptAssembler;
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

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * @Author beaker
 * @Date 2026/9/21 17:36
 * @Description 子代理
 */
@Getter
@Setter
public class SubAgent {

    private static final Logger log = LoggerFactory.getLogger(SubAgent.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String name;
    private final AgentRole role;
    private final LlmClient llmClient;
    private Supplier<String> externalContextSupplier = () -> "";

    private final ToolRegistry toolRegistry;
    private SkillRegistry skillRegistry;
    private SkillContextBuffer skillContextBuffer;

    private final List<Message> conversationHistory;
    private final ConversationHistoryCompactor historyCompactor;

    private static final PromptAssembler promptAssembler = PromptAssembler.createDefault();

    public SubAgent(String name, AgentRole role, LlmClient llmClient, ToolRegistry toolRegistry) {
        this.name = name;
        this.role = role;
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;

        this.conversationHistory = new ArrayList<>();
        this.historyCompactor = new ConversationHistoryCompactor(llmClient);
        this.conversationHistory.add(Message.system(getSystemPrompt()));
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
        refreshSystemPrompt();
    }

    /**
     * 获取系统提示词
     */
    private String getSystemPrompt() {
        return promptAssembler.assemble(promptMode(), PromptContext.builder()
                .externalContext(buildExternalContext())
                .skillIndex(buildSkillIndex())
                .build());
    }

    private PromptMode promptMode() {
        return switch (role) {
            case PLANNER -> PromptMode.TEAM_PLANNER;
            case WORKER -> PromptMode.TEAM_WORKER;
            case REVIEWER -> PromptMode.TEAM_REVIEWER;
        };
    }

    private void maybeCompactHistory(PrintStream out) {
        if (historyCompactor == null) return;
        ContextProfile profile = toolRegistry == null ? null : toolRegistry.getContextProfile();
        if (profile == null) return;

        try {
            boolean compacted = historyCompactor.compactIfNeeded(conversationHistory, profile.compressionTriggerTokens());
            if (compacted && out != null) {
                out.println("📦 [" + name + "] 上下文接近窗口上限，已把早期对话压缩为摘要后继续。");
            }
        } catch (Exception e) {
            log.warn("[{}] conversationHistory compaction failed", name, e);
        }
    }

    private String buildSkillIndex() {
        if (skillRegistry == null) return "";
        try {
            return SkillIndexFormatter.format(skillRegistry.enabledSkills());
        } catch (Exception e) {
            log.warn("[{}] failed to build skill index", name, e);
            return "";
        }
    }

    private String prependSkillBodies(String content) {
        if (skillContextBuffer == null || skillContextBuffer.isEmpty()) {
            return content;
        }
        String drained = skillContextBuffer.drain();
        if (drained.isEmpty()) return content;
        return drained + "\n" + content;
    }


    private void refreshSystemPrompt() {
        if (!conversationHistory.isEmpty()) {
            conversationHistory.set(0, Message.system(getSystemPrompt()));
        }
    }

    private String buildExternalContext() {
        if (!toolRegistry.getContextProfile().mcpResourceIndexEnabled()) {
            return "";
        }

        try {
            String context = externalContextSupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("[{}] failed to build external context", name, e);
            return "";
        }
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
        pruneHistoricalImagePayloads();

        refreshSystemPrompt();
        String taskContent = prependSkillBodies(task.content());

        // 将 task 注入历史
        conversationHistory.add(ImageReferenceParser.userMessage(
                taskContent,
                Path.of(toolRegistry.getProjectPath())));


        SubAgentStreamRenderer streamRenderer = new SubAgentStreamRenderer(name , role, out);

        AgentBudget budget = AgentBudget.fromLlmClient(llmClient);

        while (true) {
            ExitReason exitReason = budget.check();
            if (exitReason != ExitReason.WITHIN_BUDGET) {
                streamRenderer.finish();
                String description = budget.describeExit(exitReason);

                log.warn("[{}] run exhausted budget: reason={}, iteration={}, tokens={}/{}",
                        name, exitReason, budget.iteration(),
                        budget.totalInputTokens() + budget.totalOutputTokens(), budget.tokenBudget());
                return AgentMessage.error(name, role, description);
            }

            budget.beginIteration();

            injectPendingLspDiagnostics(out);
            maybeCompactHistory(out);
            try {
                ChatResponse response = llmClient.chat(
                        conversationHistory,
                        shouldUseTools(role) ? toolRegistry.getTools() : null,
                        streamRenderer
                );
                LlmTraceLogger.logReasoning(log,
                        "sub-agent name=" + name + " role=" + role + " iteration=" + budget.iteration(),
                        llmClient,
                        response.reasoningContent());

                budget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());

                // 执行工具调用, 将结果加入记忆
                if (response.hasToolCalls()) {
                    conversationHistory.add(Message.assistant(
                            response.reasoningContent(),
                            response.content(),
                            response.toolCalls()
                    ));

                    printToolCalls(out ,response.toolCalls());

                    budget.recordToolCalls(response.toolCalls());

                    streamRenderer.resetBetweenTwoIterations();

                    List<ToolExecutionResult> results = executeToolCalls(response.toolCalls());
                    for (ToolExecutionResult result : results) {
                        conversationHistory.add(Message.tool(result.id(), result.result()));
                    }
                    appendImageToolMessages(results);

                    continue;
                }

                // 没有工具调用, 返回最终结果
                conversationHistory.add(Message.assistant(response.content()));

                streamRenderer.finish();
                return AgentMessage.result(name, role, response.content());
            } catch (Exception e) {
                log.error("[{}] LLM call failed", name, e);

                streamRenderer.finish();
                return AgentMessage.error(name, role, "LLM 调用失败: " + e.getMessage());
            }
        }
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

    private void injectPendingLspDiagnostics(PrintStream out) {
        LspDiagnosticReport report = toolRegistry.flushPendingLspDiagnostics();
        if (report == null || report.isEmpty()) {
            return;
        }
        conversationHistory.add(Message.user(report.promptText()));
        out.println(report.displayText());
        log.info("[{}] injected LSP diagnostics into sub-agent conversation", name);
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
            log.info("[{}] pruned historical image payloads before sub-agent turn: messages={}, images={}",
                    name, messageCount, imageCount);
        }
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
            case "web_search" -> "🌐 联网搜索 " + count + " 次";
            case "web_fetch" -> "📰 抓取 " + count + " 个网页";
            case "save_memory" -> "💾 保存长期记忆 " + count + " 条";
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
                case "save_memory" -> "fact";
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
