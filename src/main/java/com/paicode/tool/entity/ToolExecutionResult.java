package com.paicode.tool.entity;

import com.paicode.llm.entity.ContentPart;

import java.util.List;

/**
 * @Author beaker
 * @Date 2026/9/23 18:55
 * @Description 工具执行结果
 */
public record ToolExecutionResult(String id, String name, String argumentsJson,
                                  String result, long elapsedMillis, boolean timedOut,
                                  List<ContentPart> imageParts) {

    public static ToolExecutionResult completed(ToolInvocation invocation, ToolOutput output, long elapsedMillis) {
        return new ToolExecutionResult(
                invocation.id(),
                invocation.name(),
                invocation.argumentsJson(),
                output == null ? "" : output.text(),
                elapsedMillis,
                false,
                output == null ? List.of() : output.imageParts());
    }

    public static ToolExecutionResult completed(ToolInvocation invocation, String result, long elapsedMillis) {
        return completed(invocation, ToolOutput.text(result), elapsedMillis);
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
                true,
                List.of()
        );
    }

    public boolean hasImageParts() {
        return imageParts != null && !imageParts.isEmpty();
    }
}
