package com.paicode.plan;

import com.paicode.plan.constant.PlanStatus;
import com.paicode.plan.constant.TaskStatus;
import lombok.Getter;
import lombok.Setter;

import java.util.*;

/**
 * @Author beaker
 * @Date 2026/9/10 17:21
 * @Description 计划 (由多个 Task 组成)
 */
@Getter
@Setter
public class ExecutionPlan {

    private final String id;
    private final String goal;
    private final Map<String, Task> tasks;
    private final List<String> executionOrder;
    private PlanStatus status;
    private String summary;
    private Long startTime;
    private Long endTime;

    public ExecutionPlan(String id, String goal) {
        this.id = id;
        this.goal = goal;

        tasks = new LinkedHashMap<>();
        executionOrder = new ArrayList<>();

        status = PlanStatus.CREATED;
    }

    public void addTask(Task task) {
        tasks.put(task.getId(), task);

        // 更新依赖关系
        for (String depId : task.getDependencies()) {
            Task dep = tasks.get(depId);
            if (dep != null) {
                dep.addDependent(task.getId());
            }
        }
    }

    public Task getTask(String taskId) {
        return tasks.get(taskId);
    }

    public Collection<Task> getAllTasks() {
        return tasks.values();
    }

    public List<Task> getRootTasks() {
        return tasks.values().stream()
                .filter(t -> t.getDependencies().isEmpty())
                .toList();
    }

    public List<Task> getExecutableTasks() {
        return tasks.values().stream()
                .filter(t -> t.isExecutable(tasks))
                .toList();
    }

    public boolean computeExecutionOrder() {
        executionOrder.clear();

        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (Task task : tasks.values()) {
            if (!visited.contains(task.getId())) {
                if (!topoSort(task, visited, visiting)) {
                    return false;
                }
            }
        }

        return true;
    }

    // 拓扑排序
    private boolean topoSort(Task task, Set<String> visited, Set<String> visiting) {
        String id = task.getId();

        if (visiting.contains(id)) {
            return false;
        }

        if (visited.contains(id)) {
            return true;
        }

        visiting.add(id);

        for (String depId : task.getDependencies()) {
            Task dep = tasks.get(depId);

            if (dep != null) {
                if (!topoSort(dep, visited, visiting)) {
                    return false;
                }
            }
        }

        visiting.remove(id);
        visited.add(id);
        executionOrder.add(id);

        return true;
    }

    public List<String> getExecutionOrder() {
        if (executionOrder.isEmpty()) {
            computeExecutionOrder();
        }

        return new ArrayList<>(executionOrder);
    }

    // 获取进度
    public double getProgress() {
        if (tasks.isEmpty()) return 1.0;

        long completed = tasks.values().stream()
                .filter(t -> t.getTaskStatus() == TaskStatus.COMPLETED)
                .count();
        return (double) completed / tasks.size();
    }

    public boolean isAllCompleted() {
        return tasks.values().stream()
                .allMatch(t -> t.getTaskStatus() == TaskStatus.COMPLETED);
    }

    public boolean hasFailed() {
        return tasks.values().stream()
                .anyMatch(t -> t.getTaskStatus() == TaskStatus.FAILED);
    }

    public void markStarted() {
        this.status = PlanStatus.RUNNING;
        this.startTime = System.currentTimeMillis();
    }

    public void markCompleted() {
        this.status = PlanStatus.COMPLETED;
        this.endTime = System.currentTimeMillis();
    }

    public void markFailed() {
        this.status = PlanStatus.FAILED;
        this.endTime = System.currentTimeMillis();
    }

    public long getDuration() {
        if (startTime == 0) return 0;
        if (endTime == 0) return System.currentTimeMillis() - startTime;
        return endTime - startTime;
    }

    /**
     * 可视化计划
     */
    public String visualize() {
        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════════════════════╗\n");
        sb.append(String.format("║  执行计划: %-46s║%n", goal.length() > 46 ? goal.substring(0, 43) + "..." : goal));
        sb.append("╠══════════════════════════════════════════════════════════╣\n");

        List<String> order = getExecutionOrder();
        for (int i = 0; i < order.size(); i++) {
            String taskId = order.get(i);
            Task task = tasks.get(taskId);

            String statusIcon = getStatusIcon(task.getTaskStatus());
            String deps = task.getDependencies().isEmpty() ?
                    "无" : String.join(",", task.getDependencies());

            sb.append(String.format("║  %d. %s %-20s ", i + 1, statusIcon, task.getId()));
            sb.append(String.format("[%-10s] 依赖: %-15s║%n", task.getTaskType(), deps));
            sb.append(String.format("║     %s%n",
                    task.getDescription().length() > 50 ?
                            task.getDescription().substring(0, 47) + "..." :
                            task.getDescription()));
        }

        sb.append("╚══════════════════════════════════════════════════════════╝\n");
        sb.append(String.format("   进度: %.0f%% | 状态: %s%n", getProgress() * 100, status));

        return sb.toString();
    }

    private String getStatusIcon(TaskStatus status) {
        return switch (status) {
            case PENDING -> "⏳";
            case RUNNING -> "▶️";
            case COMPLETED -> "✅";
            case FAILED -> "❌";
            case SKIPPED -> "⏭️";
        };
    }

    @Override
    public String toString() {
        return String.format("ExecutionPlan[%s: %s] (%d tasks, %s)",
                id, goal, tasks.size(), status);
    }
}
