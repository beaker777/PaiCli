package com.paicode.llm.entity;

import java.util.List;

/**
 * @Author beaker
 * @Date 2026/9/7 21:29
 * @Description TODO
 */
public record ChatResponse(String role, String reasoningContent, String content, List<ToolCall> toolCalls, int inputTokens, int outputTokens) {

    public ChatResponse create(String role, String content, List<ToolCall> toolCalls, int inputTokens, int outputTokens) {
        return new ChatResponse(role, null, content, toolCalls, inputTokens, outputTokens);
    }

    public boolean hasToolCalls() {
        return this.toolCalls != null && !this.toolCalls.isEmpty();
    }
}
