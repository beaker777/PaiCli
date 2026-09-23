package com.paicode.llm.entity;

import java.util.List;

/**
 * @Author beaker
 * @Date 2026/9/7 21:06
 * @Description 消息
 */
public record Message(String role, String reasoningContent, String content, List<ToolCall> toolCalls, String toolCallId) {

    public Message(String role, String content) {
        this(role, null, content, null, null);
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

    public static Message assistant(String reasoningContent, String content) {
        return new Message("assistant", reasoningContent, content, null, null);
    }

    public static Message assistant(String content, List<ToolCall> toolCalls) {
        return new Message("assistant", null, content, toolCalls, null);
    }

    public static Message assistant(String reasoningContent, String content, List<ToolCall> toolCalls) {
        return new Message("assistant", reasoningContent, content, toolCalls, null);
    }

    public static Message tool(String toolCallId, String content) {
        return new Message("tool", null, content, null, toolCallId);
    }
}
