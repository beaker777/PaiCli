package com.paicode.plan;

import com.paicode.plan.constant.TaskStatus;
import com.paicode.plan.constant.TaskType;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @Author beaker
 * @Date 2026/9/9 21:36
 * @Description 任务类
 */
@Getter
@Setter
public class Task {

    private final String id;
    private final String description;
    private final TaskType taskType;
    private TaskStatus taskStatus;
    private String result;
    private String error;
    private final List<String> dependencies; // 依赖的任务 id
    private final List<String> dependents; // 依赖此任务的其他任务 id
    private Long startTime;
    private Long endTime;

    public Task(String id, String description, TaskType taskType) {
        this.id = id;
        this.description = description;
        this.taskType = taskType;

        this.dependencies = new ArrayList<>();
        this.dependents = new ArrayList<>();

        taskStatus = TaskStatus.PENDING;
    }

    public Task(String id, String description, TaskType type, List<String> dependencies) {
        this(id, description, type);
        this.dependencies.addAll(dependencies);
    }

    public void addDependency(String taskId) {
        if (!dependencies.contains(taskId)) {
            dependencies.add(taskId);
        }
    }

    public void addDependent(String taskId) {
        if (!dependents.contains(taskId)) {
            dependents.add(taskId);
        }
    }

    public void markStarted() {
        this.taskStatus = TaskStatus.RUNNING;
        this.startTime = System.currentTimeMillis();
    }

    public void markCompleted(String result) {
        this.taskStatus = TaskStatus.COMPLETED;
        this.result = result;
        this.endTime = System.currentTimeMillis();
    }

    public void markFailed(String error) {
        this.taskStatus = TaskStatus.FAILED;
        this.error = error;
        this.endTime = System.currentTimeMillis();
    }

    public void markSkipped() {
        this.taskStatus = TaskStatus.SKIPPED;
        this.endTime = System.currentTimeMillis();
    }

    // 获取执行耗时
    public long getDuration() {
        if (startTime == 0) return 0;
        if (endTime == 0) return System.currentTimeMillis() - startTime;

        return endTime - startTime;
    }

    // 检测是否可以执行
    public boolean isExecutable(Map<String, Task> allTasks) {
        if (taskStatus != TaskStatus.PENDING) return false;

        for (String dependency : dependencies) {
            Task dep = allTasks.get(dependency);
            if (dep == null || dep.getTaskStatus() != TaskStatus.COMPLETED) {
                return false;
            }
        }

        return true;
    }

    @Override
    public String toString() {
        return String.format("Task[%s: %s] (%s)", id, description, taskStatus);
    }
}
