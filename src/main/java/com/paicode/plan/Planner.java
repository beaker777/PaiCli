package com.paicode.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicode.llm.DTO.ChatResponse;
import com.paicode.llm.DTO.Message;
import com.paicode.llm.DeepSeekClient;
import com.paicode.plan.constant.TaskStatus;
import com.paicode.plan.constant.TaskType;

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
            1. 每个任务必须有唯一的id（如 task_1, task_2）
            2. dependencies列出依赖的任务id
            3. 任务应该按执行顺序排列
            4. 任务描述要具体明确
            5. 复杂任务拆分为5-10个子任务

            只输出JSON，不要有其他内容。
            """;

    public Planner(DeepSeekClient deepSeekClient) {
        this.deepSeekClient = deepSeekClient;
    }

    public ExecutionPlan createPlan(String goal) throws IOException {
        System.out.println("正在规划任务: " + goal + "\n");

        List<Message> messages = Arrays.asList(
                Message.system(PLANNING_PROMPT),
                Message.user("为以下内容创建计划: " + goal)
        );

        ChatResponse response = deepSeekClient.chat(messages, null);
        String planJson = response.content();

        // 解析 planJson
        return parsePlan(goal, planJson);
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
}
