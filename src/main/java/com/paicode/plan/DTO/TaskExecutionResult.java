package com.paicode.plan.DTO;

import com.paicode.plan.Task;

/**
 * @Author beaker
 * @Date 2026/9/12 22:24
 * @Description 任务执行结果
 */
public record TaskExecutionResult(Task task, String result, Exception error) {

    public static TaskExecutionResult success(Task task, String result) {
        return new TaskExecutionResult(task, result, null);
    }

    public static TaskExecutionResult failure(Task task, Exception error) {
        return new TaskExecutionResult(task, null, error);
    }

    public boolean isFailed() {
        return error != null;
    }
}
