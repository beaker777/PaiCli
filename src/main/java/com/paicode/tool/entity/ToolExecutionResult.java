package com.paicode.tool.entity;

/**
 * @Author beaker
 * @Date 2026/9/23 18:55
 * @Description 工具执行结果
 */
public record ToolExecutionResult(String id, String name, String argumentsJson,
                                  String result, long elapsedMillis, boolean timedOut) {

    public static ToolExecutionResult completed(ToolInvocation invocation, String result, long elapsedMillis) {
        return new ToolExecutionResult(invocation.id(), invocation.name(), invocation.argumentsJson(),
                result, elapsedMillis, false);
    }

    public static ToolExecutionResult failed(ToolInvocation invocation, String message) {
        return completed(invocation, "工具执行失败: " + message, 0);
    }

    public static ToolExecutionResult timedOut(ToolInvocation invocation, long timeoutSeconds) {
        return new ToolExecutionResult(
                invocation.id(),
                invocation.name(),
                invocation.argumentsJson(),
                "工具执行超时（" + timeoutSeconds + "秒），已取消",
                timeoutSeconds * 1000,
                true
        );
    }
}
