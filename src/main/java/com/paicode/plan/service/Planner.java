package com.paicode.plan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicode.llm.entity.ChatResponse;
import com.paicode.llm.entity.Message;
import com.paicode.llm.service.DeepSeekClient;
import com.paicode.llm.service.stream.impl.PlanningStreamRender;
import com.paicode.plan.constant.TaskStatus;
import com.paicode.plan.constant.TaskType;
import com.paicode.plan.entity.ExecutionPlan;
import com.paicode.plan.entity.Task;

import java.io.IOException;
import java.util.*;

/**
 * @Author beaker
 * @Date 2026/9/10 22:21
 * @Description 规划器
 */
public class Planner {

    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper mapper = new ObjectMapper();

    // 规划提示词
    private static final String PLANNING_PROMPT = """
            你是一个任务规划专家。请将用户的复杂任务分解为一系列可执行的子任务。

            可用任务类型：
            - FILE_READ: 读取文件内容
            - FILE_WRITE: 写入文件内容
            - COMMAND: 执行Shell命令
            - ANALYSIS: 分析结果并做出决策
            - VERIFICATION: 验证结果是否正确

            请按以下JSON格式输出执行计划：
            {
                "summary": "任务摘要",
                "tasks": [
                    {
                        "id": "task_1",
                        "description": "任务描述",
                        "type": "FILE_READ",
                        "dependencies": []
                    },
                    {
                        "id": "task_2",
                        "description": "任务描述",
                        "type": "FILE_WRITE",
                        "dependencies": ["task_1"]
                    }
                ]
            }

            规则：
            1. 每个任务必须有唯一的id (如 task_1, task_2)
            2. dependencies列出依赖的任务id
            3. 任务应该按执行顺序排列
            4. 任务描述要具体明确
            5. 简单任务 (如列出目录, 读取单个文件), 允许生成 1-3 个任务
            6. 复杂任务再拆分为 5-10 个子任务
            7. 不要为了保存中间结果而额外创建 FILE_WRITE / FILE_READ, 除非用户提出相关需求
            8. 如果一个任务一步就能完成, 那么就保持最短计划

            只输出JSON，不要有其他内容。
            """;

    public Planner(DeepSeekClient deepSeekClient) {
        this.deepSeekClient = deepSeekClient;
    }

    public ExecutionPlan createPlan(String goal) throws IOException {
        System.out.println("正在规划任务: " + goal + "\n");

        // 创建简单任务
        if (isSimpleGoal(goal)) {
            return createMinimalPlan(goal);
        }

        // 构建 plan 请求
        List<Message> messages = Arrays.asList(
                Message.system(PLANNING_PROMPT),
                Message.user("为以下内容创建计划: " + goal)
        );

        // 调用 LLM
        PlanningStreamRender streamRender = new PlanningStreamRender();
        ChatResponse response = deepSeekClient.chat(messages, null, streamRender);
        streamRender.finish();
        String planJson = response.content();

        // 解析 planJson
        return parsePlan(goal, planJson);
    }

    private ExecutionPlan createMinimalPlan(String goal) {
        ExecutionPlan plan = new ExecutionPlan(generatePlanId(), goal);
        plan.setSummary(buildMinimalSummary(goal));
        plan.addTask(new Task("task_1", goal.trim(), inferSimpleTaskType(goal)));

        if (!plan.computeExecutionOrder()) {
            throw new IllegalStateException("简单计划不应出现循环依赖");
        }
        return plan;
    }

    private ExecutionPlan parsePlan(String goal, String planJson) throws IOException {
        // 清理可能的 markdown 代码块
        String cleaned = planJson.replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();

        JsonNode root = mapper.readTree(cleaned);
        String summary = root.path("summary").asText();
        JsonNode tasks = root.path("tasks");

        ExecutionPlan plan = new ExecutionPlan(generatePlanId(), goal);
        plan.setSummary(summary);

        // 解析任务
        Map<String, String> idMapping = new HashMap<>();
        int taskIndex = 1;
        for (JsonNode taskNode : tasks) {
            String originalId = taskNode.path("id").asText();
            String newId = "task_" + taskIndex ++;
            idMapping.put(originalId, newId);

            String description = taskNode.path("description").asText();
            String typeStr = taskNode.path("type").asText();
            TaskType taskType = parseTaskType(typeStr);

            // 创建任务 (本轮不处理依赖)
            plan.addTask(new Task(newId, description, taskType));
        }

        // 处理依赖
        taskIndex = 1;
        for (JsonNode taskNode : tasks) {
            String newId = "task_" + taskIndex ++;
            Task task = plan.getTask(newId);

            JsonNode depsNode = taskNode.path("dependencies");
            if (depsNode.isArray()) {
                for (JsonNode depNode : depsNode) {
                    String originalDepId = depNode.asText();
                    String newDepId = idMapping.getOrDefault(originalDepId, originalDepId);

                    Task dep = plan.getTask(newDepId);
                    if (dep != null) {
                        task.addDependency(newDepId);
                        dep.addDependent(task.getId());
                    }
                }
            }
        }

        // 计算执行顺序
        if (!plan.computeExecutionOrder()) {
            throw new IOException("计划中存在循环依赖");
        }

        return plan;
    }

    private String generatePlanId() {
        return "plan_" + System.currentTimeMillis();
    }

    private TaskType parseTaskType(String typeStr) {
        return switch (typeStr.toUpperCase()) {
            case "FILE_READ" -> TaskType.FILE_READ;
            case "FILE_WRITE" -> TaskType.FILE_WRITE;
            case "COMMAND" -> TaskType.COMMAND;
            case "ANALYSIS" -> TaskType.ANALYSIS;
            case "VERIFICATION" -> TaskType.VERIFICATION;
            default -> TaskType.ANALYSIS;
        };
    }

    public ExecutionPlan replan(ExecutionPlan failedPlan, String failureReason) throws IOException {
        System.out.println("重新规划, 原因: " + failureReason + "\n");

        StringBuilder context = new StringBuilder();
        context.append("原任务: ").append(failedPlan.getGoal()).append("\n");
        context.append("失败原因: ").append(failureReason).append("\n");

        context.append("已完成的任务:\n");
        for (Task task : failedPlan.getAllTasks()) {
            if (task.getTaskStatus() == TaskStatus.COMPLETED) {
                context.append("- ").append(task.getId())
                        .append(": ").append(task.getDescription())
                        .append("\n");
            }
        }
        context.append("\n请制定新的执行计划，避开之前的问题。");

        return createPlan(context.toString());
    }

    /**
     *  判断当前目标是否为简单任务
     */
    private boolean isSimpleGoal(String goal) {
        if (goal == null) {
            return false;
        }

        String normalized = goal.trim();
        if (normalized.isEmpty()) {
            return false;
        }

        boolean hasMultiStepCue = normalized.contains("然后")
                || normalized.contains("并且")
                || normalized.contains("并")
                || normalized.contains("再")
                || normalized.contains("最后")
                || normalized.contains("同时")
                || normalized.contains("先")
                || normalized.contains("之后")
                || normalized.contains("接着")
                || normalized.contains("以及");
        if (hasMultiStepCue) {
            return false;
        }

        if (normalized.length() > 30) {
            return false;
        }

        return normalized.contains("列出")
                || normalized.contains("查看")
                || normalized.contains("读取")
                || normalized.contains("显示")
                || normalized.contains("执行")
                || normalized.contains("运行")
                || normalized.contains("搜索")
                || normalized.contains("当前目录")
                || normalized.contains("文件");
    }

    /**
     * 为简单任务创建 summary
     */
    private String buildMinimalSummary(String goal) {
        String normalized = goal == null ? "" : goal.trim();
        if (normalized.isEmpty()) {
            return "执行简单任务";
        }
        return "直接执行简单任务：" + normalized;
    }

    /**
     * 推断简单任务类型
     */
    private TaskType inferSimpleTaskType(String goal) {
        String normalized = goal == null ? "" : goal.trim();
        if (normalized.contains("读取") || normalized.contains("打开") || normalized.contains("查看")
                && normalized.contains("文件")) {
            return TaskType.FILE_READ;
        }
        if (normalized.contains("写入") || normalized.contains("修改") || normalized.contains("创建文件")) {
            return TaskType.FILE_WRITE;
        }
        if (normalized.contains("分析") || normalized.contains("总结") || normalized.contains("解释")) {
            return TaskType.ANALYSIS;
        }
        if (normalized.contains("验证") || normalized.contains("检查")) {
            return TaskType.VERIFICATION;
        }
        return TaskType.COMMAND;
    }
}
