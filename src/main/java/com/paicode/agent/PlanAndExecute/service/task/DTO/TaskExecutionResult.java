package com.paicode.agent.PlanAndExecute.service.task.DTO;

import com.paicode.plan.Task;

/**
 * @Author beaker
 * @Date 2026/9/12 22:24
 * @Description 任务执行结果
 */
public record TaskExecutionResult(Task task, String result, boolean streamedOutput, Exception error) {

    public static TaskExecutionResult success(Task task, TaskRunResult taskRunResult) {
        return new TaskExecutionResult(task, taskRunResult.result(), taskRunResult.streamedOutput(), null);
    }

    public static TaskExecutionResult failure(Task task, Exception error) {
        return new TaskExecutionResult(task, null, false, error);
    }

    public boolean isFailed() {
        return error != null;
    }
}
