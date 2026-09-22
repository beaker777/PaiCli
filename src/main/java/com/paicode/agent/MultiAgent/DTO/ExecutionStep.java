package com.paicode.agent.MultiAgent.DTO;

import com.paicode.agent.MultiAgent.constant.StepStatus;

import java.util.List;

/**
 * @Author beaker
 * @Date 2026/9/21 19:00
 * @Description 执行步骤的数据结构
 */
public record ExecutionStep(String id, String description, String type, List<String> dependencies, String result, StepStatus status) {

    public static ExecutionStep pending(String id, String description, String type, List<String> dependencies) {
        return new ExecutionStep(id, description, type, dependencies, null, StepStatus.PENDING);
    }

    public ExecutionStep withResult(String result) {
        return new ExecutionStep(id, description, type, dependencies, result, StepStatus.COMPLETED);
    }

    public ExecutionStep withFailed(String result) {
        return new ExecutionStep(id, description, type, dependencies, result, StepStatus.FAILED);
    }

    public ExecutionStep started() {
        return new ExecutionStep(id, description, type, dependencies, result, StepStatus.RUNNING);
    }
}
