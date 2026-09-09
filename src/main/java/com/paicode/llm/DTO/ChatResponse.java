package com.paicode.llm.DTO;

import java.util.List;

/**
 * @Author beaker
 * @Date 2026/9/7 21:29
 * @Description TODO
 */
public record ChatResponse(String role, String content, List<ToolCall> toolCalls, int inputTokens, int outputTokens) {

    public boolean hasToolCalls() {
        return this.toolCalls != null && !this.toolCalls.isEmpty();
    }
}
