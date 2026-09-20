package com.paicode.agent.PlanAndExecute;

import com.paicode.agent.PlanAndExecute.DTO.PlanRunOutcome;
import com.paicode.agent.PlanAndExecute.service.task.DTO.TaskRunResult;
import com.paicode.llm.DTO.ChatResponse;
import com.paicode.llm.DTO.Message;
import com.paicode.llm.DTO.ToolCall;
import com.paicode.llm.DeepSeekClient;
import com.paicode.llm.stream.TaskStreamRender;
import com.paicode.llm.stream.entity.StreamState;
import com.paicode.memory.MemoryManager;
import com.paicode.agent.PlanAndExecute.service.review.DTO.PlanReviewDecision;
import com.paicode.agent.PlanAndExecute.service.task.DTO.TaskExecutionResult;
import com.paicode.plan.ExecutionPlan;
import com.paicode.agent.PlanAndExecute.service.review.PlanReviewHandler;
import com.paicode.plan.Planner;
import com.paicode.plan.Task;
import com.paicode.agent.PlanAndExecute.service.review.constant.PlanReviewAction;
import com.paicode.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * @Author beaker
 * @Date 2026/9/10 23:13
 * @Description planAndExecute 模式 Agent
 */
public class PlanAndExecuteAgent {

    private final static Logger log = LoggerFactory.getLogger(PlanAndExecuteAgent.class);
    private final DeepSeekClient llmClient;
    private final ToolRegistry toolRegistry;
    private final Planner planner;
    private final PlanReviewHandler reviewHandler;
    private final MemoryManager memoryManager;

    // 最大迭代轮数
    private static final int MAX_TASK_ITERATIONS = 5;

    // 执行提示词
    private static final String EXECUTION_PROMPT = """
            你是一个任务执行专家。请根据当前任务和上下文，选择合适的工具或生成回复。

            当前任务类型：%s
            任务描述：%s

            可用工具：
            1. read_file - 读取文件内容，参数：{"path": "文件路径"}
            2. write_file - 写入文件内容，参数：{"path": "文件路径", "content": "内容"}
            3. list_dir - 列出目录内容, 参数: {"path": "文件路径"}
            4. execute_command - 执行命令，参数：{"command": "命令"}
            5. create_project - 创建项目，参数：{"name": "名称", "type": "java|python|node"}
            6. search_code - 语义检索代码库, 参数: {"query": "自然语言描述", "top_k": 5}

            如果任务涉及到理解代码 (如分析项目结构, 查找实现位置), 请优先使用 search_code 工具.
            如果是ANALYSIS或VERIFICATION类型任务，请直接输出分析结果，不需要调用工具。

            请用中文回复。
            """;

    public PlanAndExecuteAgent(String apiKey) {
        this(apiKey, ((goal, plan) -> PlanReviewDecision.execute()));
    }

    public PlanAndExecuteAgent(String apiKey, PlanReviewHandler reviewHandler) {
        this(new DeepSeekClient(apiKey), new ToolRegistry(), null, reviewHandler, null);
    }

    public PlanAndExecuteAgent(DeepSeekClient llmClient, ToolRegistry toolRegistry, Planner planner,
                               PlanReviewHandler reviewHandler, MemoryManager memoryManager) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry != null ? toolRegistry : new ToolRegistry();
        this.planner = planner != null ? planner : new Planner(llmClient);
        this.reviewHandler = reviewHandler != null ? reviewHandler : ((goal, plan) -> PlanReviewDecision.execute());
        this.memoryManager = memoryManager != null ? memoryManager : new MemoryManager(llmClient);
    }

    public String run(String userInput) {
        log.info("Plan run started: inputLength={}", userInput == null ? 0 : userInput.length());
        memoryManager.addUserMessage(userInput);

        StreamState streamState = new StreamState();
        try {
            // 根据计划执行结果保存记忆和压缩
            PlanRunOutcome outcome = runWithPlan(userInput, streamState);
            if (outcome.persistAssistantMessage() && outcome.result() != null && !outcome.result().isBlank()) {
                memoryManager.addAssistantMessage("[计划结果]" + outcome.result());
            }
            if (outcome.extractFacts()) {
                memoryManager.extractAndSaveFacts();
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

            System.out.println("已收集到补充要求, 正在重新规划...\n");
            plan = planner.createPlan(plan.getGoal() + "\n补充要求: " + feedback);
        }
    }

    private String executePlan(ExecutionPlan plan, StreamState streamState) throws IOException {
        log.info("Executing plan: goal='{}, taskCount={}'", plan.getGoal(), plan.getAllTasks().size());
        System.out.println("开始执行计划...\n");

        plan.markStarted();
        StringBuilder finalResult = new StringBuilder();
        Map<String, Boolean> streamedTaskOutputs = new HashMap<>();

        // 任务分批次并行执行
        while (true) {
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
                        System.out.println("完成 [" + task.getId() + "]\n");
                    } else {
                        System.out.println("完成 [" + task.getId() + "]:" +
                                result.result().substring(0, Math.min(100, result.result().length())) + "\n");
                    }

                    continue;
                }

                // 任务失败, 处理异常
                Exception error = result.error();
                task.markFailed(error.getMessage());
                log.warn("Task failed: {}, error={}", task.getId(), error.getMessage());
                System.out.println("任务失败 [" + task.getId() + "]: " + error.getMessage() + "\n");

                // 任务进度 < 0.5 则重新规划并执行
                if (plan.getProgress() < 0.5) {
                    System.out.println("尝试重新规划...\n");
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
            System.out.println("> 执行任务 [" + task.getId() + "]: " + task.getDescription());
            task.markStarted();

            try {
                return List.of(TaskExecutionResult.success(task, executeTask(plan.getGoal(), plan, task, streamState)));
            } catch (Exception e) {
                return List.of(TaskExecutionResult.failure(task, e));
            }
        }

        // 多个任务并行执行
        String parallelTaskIds = executableTasks.stream()
                .map(Task::getId)
                .collect(Collectors.joining(", "));
        log.info("Executing parallel batch: {}", parallelTaskIds);
        System.out.println("> 本轮并行执行: " + executableTasks.size() + " 个任务: " + parallelTaskIds);

        // 创建线程池, 并行执行任务
        ExecutorService executor = Executors.newFixedThreadPool(executableTasks.size());
        try {
            List<Future<TaskExecutionResult>> futures = new ArrayList<>();
            for (Task task : executableTasks) {
                System.out.println("> 并行任务 [" + task.getId() + "]: " + task.getDescription());
                task.markStarted();

                futures.add(executor.submit(() -> {
                    try {
                        return TaskExecutionResult.success(task, executeTask(plan.getGoal(), plan, task, streamState));
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
            return results;
        } finally {
            executor.shutdown();
        }
    }

    // 执行任务, 支持 ReAct 模式
    private TaskRunResult executeTask(String goal, ExecutionPlan plan, Task task, StreamState streamState) throws IOException {
        // 构建提示词
        String prompt = String.format(EXECUTION_PROMPT, task.getTaskType(), task.getDescription());

        // 注入记忆上下文
        String memoryContext = memoryManager.buildContextForQuery(task.getDescription(), 300);
        String taskInput = buildTaskContext(goal, plan, task);
        if (!memoryContext.isBlank()) {
            taskInput = taskInput + "\n\n" + memoryContext;
        }

        List<Message> messages = Arrays.asList(
                Message.system(prompt),
                Message.user(taskInput)
        );

        StringBuilder allResults = new StringBuilder();
        int iteration = 0;
        TaskStreamRender streamRender = new TaskStreamRender(task.getId(), streamState);

        // 支持 ReAct 模式
        while (iteration < MAX_TASK_ITERATIONS) {
            iteration ++;

            // 调用 LLM
            ChatResponse response = llmClient.chat(messages, toolRegistry.getTools(), streamRender);
            log.info("Task {} iteration {} response: toolCalls={}, reasoningChars={}, contentChars={}",
                    task.getId(),
                    iteration,
                    response.toolCalls() == null ? 0 : response.toolCalls().size(),
                    response.reasoningContent() == null ? 0 : response.reasoningContent().length(),
                    response.content() == null ? 0 : response.content().length());

            // 没有工具调用, 直接返回结果
            if (!response.hasToolCalls()) {
                memoryManager.recordTokenUsage(response.inputTokens(), response.outputTokens());

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
            messages.add(Message.assistant(response.reasoningContent(), response.content(), response.toolCalls()));

            for (ToolCall toolCall : response.toolCalls()) {
                String name = toolCall.function().name();
                String arguments = toolCall.function().arguments();
                log.info("Task {} calling tool {}", task.getId(), name);
                log.debug("Task {} tool args [{}]: {}", task.getId(), name, arguments);

                String result = toolRegistry.executeTool(name, arguments);
                log.debug("Task {} tool result preview [{}]: {}", task.getId(), name, preview(result, 300));
                memoryManager.addToolResult(name, result);
                allResults.append(result).append("\n");
                messages.add(Message.tool(toolCall.id(), result));
            }
        }

        // 超过最大迭代轮数
        String fallbackResult = allResults.toString().trim();
        if (!fallbackResult.isBlank()) {
            memoryManager.addAssistantMessage("[计划任务 " + task.getId() + "]" + fallbackResult);
        }
        streamRender.finish();
        return TaskRunResult.of(fallbackResult, streamRender.hasStreamedOutput());
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
}
