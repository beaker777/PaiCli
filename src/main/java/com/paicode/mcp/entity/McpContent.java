package com.paicode.mcp.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * @Author beaker
 * @Date 2026/9/25 17:12
 * @Description MCP 响应正文
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpContent(String type, String text) {
}
