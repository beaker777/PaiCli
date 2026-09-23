package com.paicode.agent.PlanAndExecute.entity;

/**
 * @Author beaker
 * @Date 2026/9/19 22:08
 * @Description 任务运行结果
 */
public record TaskRunResult(String result, boolean streamedOutput) {

    public static TaskRunResult of(String result, boolean streamedOutput) {
        return new TaskRunResult(result, streamedOutput);
    }
}
