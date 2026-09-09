package com.paicode.llm.DTO;

import java.util.List;

/**
 * @Author beaker
 * @Date 2026/9/7 21:06
 * @Description 消息
 */
public record Message(String role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public Message(String role, String content) {
        this(role, content, null, null);
    }

    public static Message system(String content) {
        return new Message("system", content);
    }

    public static Message user(String content) {
        return new Message("user", content);
    }

    public static Message assistant(String content) {
        return new Message("assistant", content);
    }

    public static Message assistant(String content, List<ToolCall> toolCalls) {
        return new Message("assistant", content, toolCalls, null);
    }

    public static Message tool(String toolCallId, String content) {
        return new Message("tool", content, null, toolCallId);
    }
}
