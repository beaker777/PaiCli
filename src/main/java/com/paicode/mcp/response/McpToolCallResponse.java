package com.paicode.mcp.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.paicode.mcp.entity.McpContent;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @Author beaker
 * @Date 2026/9/25 17:10
 * @Description MCP 工具调用结果
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpToolCallResponse(List<McpContent> contents, boolean isError) {

    public String formatForLlm() {
        if (contents == null || contents.isEmpty()) {
            return isError ? "MCP 工具返回错误，但没有错误正文" : "";
        }

        return contents.stream()
                .map(item -> {
                    String type = item.type() == null || item.type().isBlank() ? "text" : item.type();
                    if ("text".equals(type)) {
                        return item.text() == null ? "" : item.text();
                    }

                    return "[此工具返回了 " + type + "，请向用户描述结果]";
                })
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n\n"));
    }
}
