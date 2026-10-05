package com.paicode.agent.PlanAndExecute.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicode.agent.PlanAndExecute.entity.PlanRunOutcome;
import com.paicode.agent.PlanAndExecute.entity.TaskRunResult;
import com.paicode.context.TokenUsageFormatter;
import com.paicode.image.service.ImageReferenceParser;
import com.paicode.llm.entity.*;
import com.paicode.llm.service.log.LlmTraceLogger;
import com.paicode.llm.service.model.LlmClient;
import com.paicode.llm.service.stream.impl.TaskStreamRender;
import com.paicode.lsp.entity.LspDiagnosticReport;
import com.paicode.memory.service.compress.ConversationHistoryCompactor;
import com.paicode.memory.service.manager.MemoryManager;
import com.paicode.agent.PlanAndExecute.entity.PlanReviewDecision;
import com.paicode.agent.PlanAndExecute.entity.TaskExecutionResult;
import com.paicode.plan.entity.ExecutionPlan;
import com.paicode.plan.service.Planner;
import com.paicode.plan.entity.Task;
import com.paicode.agent.PlanAndExecute.constant.PlanReviewAction;
import com.paicode.prompt.constant.PromptMode;
import com.paicode.prompt.entity.PromptContext;
import com.paicode.prompt.service.PromptAssembler;
import com.paicode.runtime.service.cancel.CancellationContext;
import com.paicode.skill.service.buffer.SkillContextBuffer;
import com.paicode.skill.service.manage.SkillRegistry;
import com.paicode.skill.service.parser.SkillIndexFormatter;
import com.paicode.tool.entity.ToolExecutionResult;
import com.paicode.tool.entity.ToolInvocation;
import com.paicode.tool.service.register.ToolRegistry;
import com.paicode.utils.AnsiStyle;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * @Author beaker
 * @Date 2026/9/10 23:13
 * @Description planAndExecute 模式 Agent
 */
@Setter
public class PlanAndExecuteAgent {

    private final static ObjectMapper mapper = new ObjectMapper();
    private final static Logger log = LoggerFactory.getLogger(PlanAndExecuteAgent.class);

    private final LlmClient llmClient;
    private final Planner planner;
    private final PlanReviewHandler reviewHandler;
    private Supplier<String> externalContextSupplier = () -> "";
    private final PrintStream out;

    private final ToolRegistry toolRegistry;
    private SkillRegistry skillRegistry;
    private SkillContextBuffer skillContextBuffer;

    private final ConversationHistoryCompactor historyCompactor;
    private final MemoryManager memoryManager;

    // 最大迭代轮数
    private static final int MAX_TASK_ITERATIONS = 5;

    private final static PromptAssembler promptAssembler = PromptAssembler.createDefault();

    public PlanAndExecuteAgent(LlmClient llmClient) {
        this(llmClient, ((goal, plan) -> PlanReviewDecision.execute()));
    }

    public PlanAndExecuteAgent(LlmClient llmClient, PlanReviewHandler reviewHandler) {
        this(llmClient, new ToolRegistry(), reviewHandler, null, null);
    }

    public PlanAndExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                               MemoryManager memoryManager, PlanReviewHandler reviewHandler) {
        this(llmClient, toolRegistry, reviewHandler, memoryManager, null);
    }

    public PlanAndExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                               PlanReviewHandler reviewHandler, MemoryManager memoryManager, PrintStream out) {
        this(llmClient, toolRegistry, null, reviewHandler, memoryManager, out);
    }


    public PlanAndExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, Planner planner,
                               PlanReviewHandler reviewHandler, MemoryManager memoryManager, PrintStream out) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry != null ? toolRegistry : new ToolRegistry();
        this.planner = planner != null ? planner : new Planner(llmClient);
        this.reviewHandler = reviewHandler != null ? reviewHandler : ((goal, plan) -> PlanReviewDecision.execute());
        this.memoryManager = memoryManager != null ? memoryManager : new MemoryManager(llmClient);
        this.historyCompactor = new ConversationHistoryCompactor(llmClient);
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
        this.toolRegistry.setMemorySaver(memoryManager::storeFact);
        this.out = out == null ? deferredSystemOut() : out;
    }

    private static PrintStream deferredSystemOut() {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                System.out.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                System.out.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                System.out.flush();
            }
        }, true, StandardCharsets.UTF_8);
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
    }

    public String run(String userInput) {
        log.info("Plan run started: inputLength={}", userInput == null ? 0 : userInput.length());
        memoryManager.addUserMessage(userInput);
        if (CancellationContext.isCancelled()) {
            return "⏹️ 已取消当前计划执行。";
        }

        StreamState streamState = new StreamState();
        try {
            // 根据计划执行结果保存记忆和压缩
            PlanRunOutcome outcome = runWithPlan(userInput, streamState);
            if (outcome.persistAssistantMessage() && outcome.result() != null && !outcome.result().isBlank()) {
                memoryManager.addAssistantMessage("[计划结果]" + outcome.result());
            }

            if (streamState.hasStreamedOutput() && (outcome.result() == null || outcome.result().isBlank())) {
                return "";
            }
            return outcome.result();
        } catch (Exception e) {
            log.error("Plan run failed", e);
            String errorMessage = "计划执行失败: " + e.getMessage();
            memoryManager.addAssistantMessage(errorMessage);
            return errorMessage;
        }
    }

    private PlanRunOutcome runWithPlan(String goal, StreamState streamState) throws IOException {
        // 创建计划
        ExecutionPlan plan = planner.createPlan(goal);
        // 执行计划
        return reviewAndExecutePlan(plan, streamState);
    }

    private PlanRunOutcome reviewAndExecutePlan(ExecutionPlan plan, StreamState streamState) throws IOException {
        while (true) {
            PlanReviewDecision decision = reviewHandler.review(plan.getGoal(), plan);
            if (decision == null || decision.action() == PlanReviewAction.EXECUTE) {
                return PlanRunOutcome.executed(executePlan(plan, streamState));
            }

            if (decision.action() == PlanReviewAction.CANCEL) {
                return PlanRunOutcome.canceled("已取消本次计划执行");
            }

            String feedback = decision.feedback() == null ? "" : decision.feedback().trim();
            if (feedback.isBlank()) {
                return PlanRunOutcome.executed(executePlan(plan, streamState));
            }

            out.println("已收集到补充要求, 正在重新规划...\n");
            plan = planner.createPlan(plan.getGoal() + "\n补充要求: " + feedback);
        }
    }

    private String executePlan(ExecutionPlan plan, StreamState streamState) throws IOException {
        log.info("Executing plan: goal='{}, taskCount={}'", plan.getGoal(), plan.getAllTasks().size());
        out.println("开始执行计划...\n");

        plan.markStarted();
        StringBuilder finalResult = new StringBuilder();
        Map<String, Boolean> streamedTaskOutputs = new HashMap<>();

        // 任务分批次并行执行
        while (true) {
            if (CancellationContext.isCancelled()) {
                return "⏹️ 已取消当前计划执行。";
            }

            // 按顺序获取可执行计划
            List<Task> executableTasks = getExecutableTasksInOrder(plan);
            if (executableTasks.isEmpty()) {
                break;
            }

            List<TaskExecutionResult> results = executeTasksBatch(plan, executableTasks, streamState);
            for (TaskExecutionResult result : results) {
                Task task = result.task();

                // 任务成功执行, 继续处理
                if (!result.isFailed()) {
                    task.markCompleted(result.result());
                    streamedTaskOutputs.put(task.getId(), result.streamedOutput());

                    log.info("Task completed: {}, status={}, resultChars={}",
                            task.getId(), task.getTaskStatus(), result.result() == null ? 0 : result.result().length());

                    // 输出任务执行结果
                    if (result.streamedOutput() || result.result() == null || result.result().isBlank()) {
                        out.println("完成 [" + task.getId() + "]\n");
                    } else {
                        out.println("完成 [" + task.getId() + "]:" +
                                result.result().substring(0, Math.min(100, result.result().length())) + "\n");
                    }

                    continue;
                }

                // 任务失败, 处理异常
                Exception error = result.error();
                task.markFailed(error.getMessage());
                log.warn("Task failed: {}, error={}", task.getId(), error.getMessage());
                out.println("任务失败 [" + task.getId() + "]: " + error.getMessage() + "\n");

                // 任务进度 < 0.5 则重新规划并执行
                if (plan.getProgress() < 0.5) {
                    out.println("尝试重新规划...\n");
                    ExecutionPlan replan = planner.replan(plan, error.getMessage());
                    return reviewAndExecutePlan(replan, streamState).result();
                }

                if (!finalResult.isEmpty()) {
                    finalResult.append("\n");
                }
                finalResult.append("任务: ").append(task.getId()).append(" 失败: ").append(error.getMessage());
            }
        }

        // 计划执行失败
        if (!plan.isAllCompleted() && !plan.hasFailed()) {
            plan.markFailed();
            return "计划未能正常执行, 存在未满足依赖的任务";
        }

        String planSummary = finalResult.isEmpty() ? buildFinalResult(plan, streamedTaskOutputs) : finalResult.toString();

        // 完成计划
        if (plan.hasFailed()) {
            plan.markFailed();
            if (planSummary.isBlank()) {
                return "计划部分完成, 有任务失败";
            }
            return "计划部分完成, 有任务失败\n" + planSummary;
        } else {
            plan.markCompleted();
            if (planSummary.isBlank()) {
                return "计划执行完成";
            }
            return "计划执行完成! \n" + planSummary;
        }
    }

    private List<Task> getExecutableTasksInOrder(ExecutionPlan plan) {
        Set<String> executableIds = plan.getExecutableTasks().stream()
                .map(Task::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return plan.getExecutionOrder().stream()
                .filter(executableIds::contains)
                .map(plan::getTask)
                .toList();
    }

    private List<TaskExecutionResult> executeTasksBatch(ExecutionPlan plan, List<Task> executableTasks, StreamState streamState) {
        // 一个任务直接执行
        if (executableTasks.size() == 1) {
            Task task = executableTasks.get(0);
            log.info("Executing single task: {} type={}", task.getId(), task.getTaskType());
            out.println("> 执行任务 [" + task.getId() + "]: " + task.getDescription());
            task.markStarted();

            try {
                return List.of(TaskExecutionResult.success(task, executeTask(plan.getGoal(), plan, task, streamState, out)));
            } catch (Exception e) {
                return List.of(TaskExecutionResult.failure(task, e));
            }
        }

        // 多个任务并行执行
        String parallelTaskIds = executableTasks.stream()
                .map(Task::getId)
                .collect(Collectors.joining(", "));
        log.info("Executing parallel batch: {}", parallelTaskIds);
        out.println("> 本轮并行执行: " + executableTasks.size() + " 个任务: " + parallelTaskIds);

        // 创建线程池, 并行执行任务
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(executableTasks.size(), 4), r -> {
            Thread t = new Thread(r, "paicode-plan-executor");
            t.setDaemon(true);
            return t;
        });
        try {
            Map<String, ByteArrayOutputStream> buffers = new LinkedHashMap<>();
            List<Future<TaskExecutionResult>> futures = new ArrayList<>();
            for (Task task : executableTasks) {
                out.println("> 并行任务 [" + task.getId() + "]: " + task.getDescription());
                task.markStarted();

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                buffers.put(task.getId(), baos);
                PrintStream taskOut = new PrintStream(baos, true, StandardCharsets.UTF_8);
                futures.add(executor.submit(() -> {
                    try {
                        return TaskExecutionResult.success(task, executeTask(plan.getGoal(), plan, task, streamState, taskOut));
                    } catch (Exception e) {
                        return TaskExecutionResult.failure(task, e);
                    }
                }));
            }

            // 获取任务执行结果
            List<TaskExecutionResult> results = new ArrayList<>();
            for (Future<TaskExecutionResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(TaskExecutionResult.failure(executableTasks.get(results.size()), e));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    Exception error = cause instanceof Exception exception ? exception : new RuntimeException(cause);
                    results.add(TaskExecutionResult.failure(executableTasks.get(results.size()), error));
                }
            }

            // 按任务顺序 flush 缓冲区到 stdout, 避免出错
            for (Task task : executableTasks) {
                ByteArrayOutputStream buf = buffers.get(task.getId());
                if (buf != null && buf.size() > 0) {
                    out.println(buf.toString(StandardCharsets.UTF_8));
                    out.flush();
                }
            }

            return results;
        } finally {
            executor.shutdown();
        }
    }

    // 执行任务, 支持 ReAct 模式
    private TaskRunResult executeTask(String goal, ExecutionPlan plan, Task task, StreamState streamState, PrintStream out) throws IOException {
        // 构建提示词
        String prompt = promptAssembler.assemble(PromptMode.PLAN, PromptContext.builder()
                .variable("taskType", task.getTaskType())
                .variable("taskDescription", task.getDescription())
                .externalContext(buildExternalContext())
                .skillIndex(buildSkillIndex())
                .build());

        // 注入记忆上下文
        String memoryContext = memoryManager.buildContextForQuery(
                task.getDescription(),
                memoryManager.getContextProfile().memoryContextTokens());
        String taskInput = buildTaskContext(goal, plan, task);
        if (!memoryContext.isBlank()) {
            taskInput = taskInput + "\n\n" + memoryContext;
        }
        taskInput = prependSkillBodies(taskInput);

        List<Message> messages = Arrays.asList(
                Message.system(prompt),
                ImageReferenceParser.userMessage(
                        taskInput,
                        Path.of(toolRegistry.getProjectPath()))
        );

        StringBuilder allResults = new StringBuilder();
        int iteration = 0;
        TaskStreamRender streamRender = new TaskStreamRender(task.getId(), streamState, out);

        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        int totalCachedInputTokens = 0;

        // 支持 ReAct 模式
        while (iteration < MAX_TASK_ITERATIONS) {
            if (CancellationContext.isCancelled()) {
                streamRender.finish();
                return TaskRunResult.of("⏹️ 已取消任务 [" + task.getId() + "]。", streamRender.hasStreamedOutput());
            }
            iteration ++;

            // 进行诊断
            injectPendingLspDiagnostics(messages, out);

            // 调用 LLM 前先评估是否需要压缩历史
            maybeCompactHistory(messages, out);

            // 调用 LLM
            ChatResponse response = llmClient.chat(messages, toolRegistry.getTools(), streamRender);
            LlmTraceLogger.logReasoning(log,
                    "plan-task task=" + task.getId() + " iteration=" + iteration,
                    llmClient,
                    response.reasoningContent());

            if (CancellationContext.isCancelled()) {
                streamRender.finish();
                return TaskRunResult.of("⏹️ 已取消任务 [" + task.getId() + "]。", streamRender.hasStreamedOutput());
            }


            log.info("Task {} iteration {} response: toolCalls={}, reasoningChars={}, contentChars={}",
                    task.getId(),
                    iteration,
                    response.toolCalls() == null ? 0 : response.toolCalls().size(),
                    response.reasoningContent() == null ? 0 : response.reasoningContent().length(),
                    response.content() == null ? 0 : response.content().length());

            totalInputTokens += response.inputTokens();
            totalOutputTokens += response.outputTokens();
            totalCachedInputTokens += response.cachedInputTokens();

            // 没有工具调用, 直接返回结果
            if (!response.hasToolCalls()) {
                memoryManager.recordTokenUsage(totalInputTokens, totalOutputTokens, totalCachedInputTokens);

                // 如果最后一轮工具调用结果为空就使用之前的记录
                if (!allResults.isEmpty() && (response.content() == null || response.content().isBlank())) {
                    String toolResult = allResults.toString().trim();
                    if (!toolResult.isBlank()) {
                        memoryManager.addAssistantMessage("[计划任务 " + task.getId() + "]" + toolResult);
                    }

                    streamRender.finish();
                    return TaskRunResult.of(toolResult, streamRender.hasStreamedOutput());
                }

                // 将最后一轮调用工具的结果存入记忆
                if (response.content() != null && !response.content().isBlank()) {
                    memoryManager.addAssistantMessage("[计划任务 " + task.getId() + "]" + response.content());
                }

                streamRender.finish();
                return TaskRunResult.of(response.content(), streamRender.hasStreamedOutput());
            }

            // 调用工具, 将 toolCalls 和 toolResult 写入历史
            printToolCalls(out, response.toolCalls());
            messages.add(Message.assistant(response.reasoningContent(), response.content(), response.toolCalls()));

            // 重置渲染器状态
            streamRender.resetBetweenIterations();

            List<ToolExecutionResult> toolResults = executeToolCalls(task.getId(), response.toolCalls());
            for (ToolExecutionResult toolResult : toolResults) {
                memoryManager.addToolResult(toolResult.name(), toolResult.result());
                allResults.append(toolResult.result()).append("\n");
                messages.add(Message.tool(toolResult.id(), toolResult.result()));
            }
            appendImageToolMessages(messages, toolResults);
        }

        // 超过最大迭代轮数
        String fallbackResult = allResults.toString().trim();
        if (!fallbackResult.isBlank()) {
            memoryManager.addAssistantMessage("[计划任务 " + task.getId() + "]" + fallbackResult);
        }

        streamRender.finish();
        return TaskRunResult.of(fallbackResult, streamRender.hasStreamedOutput());
    }

    private void appendImageToolMessages(List<Message> messages, List<ToolExecutionResult> toolResults) {
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
            messages.add(Message.user(parts));
        }
    }

    private void injectPendingLspDiagnostics(List<Message> messages, PrintStream out) {
        LspDiagnosticReport report = toolRegistry.flushPendingLspDiagnostics();
        if (report == null || report.isEmpty()) {
            return;
        }
        messages.add(Message.user(report.promptText()));
        out.println(report.displayText());
        log.info("Injected LSP diagnostics into plan task conversation");
    }

    private void maybeCompactHistory(List<Message> messages, PrintStream out) {
        if (historyCompactor == null) return;
        int trigger = memoryManager.getContextProfile().compressionTriggerTokens();
        try {
            boolean compacted = historyCompactor.compactIfNeeded(messages, trigger);
            if (compacted && out != null) {
                out.println("📦 上下文接近窗口上限，已把早期对话压缩为摘要后继续。");
            }
        } catch (Exception e) {
            log.warn("conversationHistory compaction failed", e);
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

    private String prependSkillBodies(String content) {
        if (skillContextBuffer == null || skillContextBuffer.isEmpty()) {
            return content;
        }
        String drained = skillContextBuffer.drain();
        if (drained.isEmpty()) return content;
        return drained + "\n" + content;
    }

    private String buildExternalContext() {
        if (!memoryManager.getContextProfile().mcpResourceIndexEnabled()) {
            return "";
        }

        try {
            String context = externalContextSupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("Failed to build external context for plan task", e);
            return "";
        }
    }

    private List<ToolExecutionResult> executeToolCalls(String taskId, List<ToolCall> toolCalls) {
        List<ToolInvocation> invocations = new ArrayList<>();
        for (ToolCall toolCall : toolCalls) {
            String toolName = toolCall.function().name();
            String toolArgs = toolCall.function().arguments();
            log.info("Task {} scheduling tool {}", taskId, toolName);
            log.debug("Task {} tool args [{}]: {}", taskId, toolName, toolArgs);
            invocations.add(new ToolInvocation(toolCall.id(), toolName, toolArgs));
        }

        if (invocations.size() > 1) {
            log.info("Task {} executing {} tool calls in parallel", taskId, invocations.size());
        }
        List<ToolExecutionResult> results = toolRegistry.executeTools(invocations);
        for (ToolExecutionResult result : results) {
            log.debug("Task {} tool result preview [{}]: {}", taskId, result.name(), preview(result.result(), 300));
        }
        return results;
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


    private String buildTaskContext(String goal, ExecutionPlan plan, Task task) {
        StringBuilder context = new StringBuilder();
        context.append("总目标: ").append(goal).append("\n");
        context.append("当前任务: ").append(task.getDescription()).append("\n");

        if (task.getDependencies().isEmpty()) {
            context.append("依赖任务: 无").append("\n");
        } else {
            context.append("依赖任务结果: \n");

            for (String depId : task.getDependencies()) {
                Task dep = plan.getTask(depId);
                if (dep == null) {
                    continue;
                }
                context.append("- ").append(dep.getId())
                        .append(" / ").append(dep.getDescription())
                        .append(" / 状态=").append(dep.getTaskStatus())
                        .append("\n");
                if (dep.getResult() != null && !dep.getResult().isBlank()) {
                    context.append(dep.getResult()).append("\n");
                }
            }
        }

        context.append("请执行此任务。如果是 ANALYSIS 或 VERIFICATION 类型，请基于以上上下文直接给出结果。");
        return context.toString();
    }

    private String buildFinalResult(ExecutionPlan plan, Map<String, Boolean> streamedTaskOutputs) {
        StringBuilder result = new StringBuilder();
        List<Task> leafTasks = plan.getAllTasks().stream()
                .filter(task -> task.getDependents().isEmpty())
                .toList();

        // 返回所有的叶子任务
        for (Task task : leafTasks) {
            if (streamedTaskOutputs.get(task.getId())) {
                continue;
            }
            if (task.getResult() == null || task.getResult().isBlank()) {
                continue;
            }

            if (!result.isEmpty()) {
                result.append("\n");
            }
            result.append("[").append(task.getId()).append("] ").append(task.getResult());
        }

        if (!result.isEmpty()) {
            return result.toString();
        }

        // 兜底逻辑
        return plan.getAllTasks().stream()
                .filter(task -> !streamedTaskOutputs.get(task.getId()))
                .filter(task -> task.getResult() != null && !task.getResult().isBlank())
                .reduce((first, second) -> second)
                .map(Task::getResult)
                .orElse("");
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
                case "save_memory" -> "text";
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
}
