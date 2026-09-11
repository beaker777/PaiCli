package com.paicode.agent;

import com.paicode.llm.DTO.ChatResponse;
import com.paicode.llm.DTO.Message;
import com.paicode.llm.DTO.ToolCall;
import com.paicode.llm.DeepSeekClient;
import com.paicode.plan.ExecutionPlan;
import com.paicode.plan.Planner;
import com.paicode.plan.Task;
import com.paicode.tool.ToolRegistry;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @Author beaker
 * @Date 2026/9/10 23:13
 * @Description planAndExecute 模式 Agent
 */
public class PlanAndExecuteAgent {

    private final DeepSeekClient llmClient;
    private final ToolRegistry toolRegistry;
    private final Planner planner;

    // 执行提示词
    private static final String EXECUTION_PROMPT = """
            你是一个任务执行专家。请根据当前任务和上下文，选择合适的工具或生成回复。

            当前任务类型：%s
            任务描述：%s

            可用工具：
            1. read_file - 读取文件内容，参数：{"path": "文件路径"}
            2. write_file - 写入文件内容，参数：{"path": "文件路径", "content": "内容"}
            3. execute_command - 执行命令，参数：{"command": "命令"}
            4. create_project - 创建项目，参数：{"name": "名称", "type": "java|python|node"}

            如果是ANALYSIS或VERIFICATION类型任务，请直接输出分析结果，不需要调用工具。

            请用中文回复。
            """;

    public PlanAndExecuteAgent(String apiKey) {
        llmClient = new DeepSeekClient(apiKey);
        toolRegistry = new ToolRegistry();
        planner = new Planner(llmClient);
    }

    public String run(String userInput) throws IOException {
        try {
            // 判断是否需要 plan
            if (shouldPlan(userInput)) {
                return runWithPlan(userInput);
            } else {
                return runSimple(userInput);
            }
        } catch (Exception e) {
            return "执行失败: " + e.getMessage();
        }
    }

    private boolean shouldPlan(String input) {
        // 启发式判断
        String lower = input.toLowerCase();
        int actionCount = 0;
        String[] actionKeywords = {"创建", "写", "读", "执行", "编译", "运行", "修改", "删除", "然后", "接着", "再", "最后"};

        for (String keyword : actionKeywords) {
            if (lower.contains(keyword)) actionCount++;
        }

        return actionCount >= 3 || input.length() > 50;
    }

    private String runWithPlan(String goal) throws IOException {
        // 创建计划
        ExecutionPlan plan = planner.createPlan(goal);

        return executePlan(goal, plan);
    }

    private String executePlan(String goal, ExecutionPlan plan) throws IOException {
        System.out.println(plan.visualize());
        System.out.println("开始执行计划...\n");

        // 执行计划
        plan.markStarted();
        StringBuilder finalResult = new StringBuilder();

        List<String> executionOrder = plan.getExecutionOrder();
        for (String taskId : executionOrder) {
            Task task = plan.getTask(taskId);

            // 检查依赖
            if (!task.isExecutable(
                    plan.getAllTasks().stream().collect(Collectors.toMap(Task::getId, t -> t)))) {
                System.out.println("依赖未完成, 跳过任务: " + taskId);
                task.markSkipped();
                continue;
            }

            // 执行任务
            System.out.println("执行任务");
            task.markStarted();

            try {
                String result = executeTask(goal, plan, task);
                task.markCompleted(result);

                System.out.println("任务完成: " + result.substring(0, Math.min(100, result.length())) + "\n");
            } catch (Exception e) {
                task.markFailed(e.getMessage());
                System.out.println("任务执行失败: " + e.getMessage() + "\n");

                // 尝试重新构建
                if (plan.getProgress() < 0.5) {
                    System.out.println("尝试重新规划...\n");

                    ExecutionPlan replan = planner.replan(plan, e.getMessage());
                    return executePlan(goal, replan);
                } else {
                    finalResult.append("任务 ").append(taskId).append(" 失败: ").append(e.getMessage());
                }
            }
        }

        if (finalResult.isEmpty()) {
            finalResult.append(buildFinalResult(plan));
        }

        // 完成任务
        if (plan.hasFailed()) {
            plan.markFailed();
            return "计划未完成, 有任务失败\n" + finalResult;
        } else {
            plan.markCompleted();
            return "计划执行完成\n" + finalResult;
        }
    }

    private String executeTask(String goal, ExecutionPlan plan, Task task) throws IOException {
        // 构建提示词
        String prompt = String.format(EXECUTION_PROMPT, task.getTaskType(), task.getDescription());
        List<Message> messages = Arrays.asList(
                Message.system(prompt),
                Message.user(buildTaskContext(goal, plan, task))
        );

        // 调用 LLM
        ChatResponse response = llmClient.chat(messages, toolRegistry.getTools());

        // 调用工具
        if (response.hasToolCalls()) {
            StringBuilder results = new StringBuilder();

            for (ToolCall toolCall : response.toolCalls()) {
                String name = toolCall.function().name();
                String arguments = toolCall.function().arguments();

                System.out.println("调用工具: " + name);

                String result = toolRegistry.executeTool(name, arguments);
                results.append(results).append("\n");
            }

            return results.toString().trim();
        } else {
            return response.content();
        }
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

    private String buildFinalResult(ExecutionPlan plan) {
        StringBuilder result = new StringBuilder();
        List<Task> leafTasks = plan.getAllTasks().stream()
                .filter(task -> task.getDependents().isEmpty())
                .toList();

        // 返回所有的叶子任务
        for (Task task : leafTasks) {
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
                .filter(task -> task.getResult() != null && !task.getResult().isBlank())
                .reduce((first, second) -> second)
                .map(Task::getResult)
                .orElse("");
    }

    public String runSimple(String userInput) throws IOException {
        System.out.println("简单任务, 直接执行");

        List<Message> messages = Arrays.asList(
                Message.system("你是一个智能 agent, 可以调用工具完成任务"),
                Message.user(userInput)
        );

        ChatResponse response = llmClient.chat(messages, toolRegistry.getTools());

        if (response.hasToolCalls()) {
            StringBuilder results = new StringBuilder();

            for (ToolCall toolCall : response.toolCalls()) {
                String name = toolCall.function().name();
                String arguments = toolCall.function().arguments();

                String result = toolRegistry.executeTool(name, arguments);

                results.append(result).append("\n");
            }

            return results.toString().trim();
        } else {
            return response.content();
        }
    }
}
