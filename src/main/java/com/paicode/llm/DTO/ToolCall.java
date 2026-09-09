package com.paicode.llm.DTO;

/**
 * @Author beaker
 * @Date 2026/9/7 21:07
 * @Description 工具调用
 */
public record ToolCall(String id, Function function) {
    public record Function(String name, String arguments) {}
}
