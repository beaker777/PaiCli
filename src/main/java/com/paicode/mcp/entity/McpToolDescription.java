package com.paicode.mcp.entity;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * @Author beaker
 * @Date 2026/9/25 16:41
 * @Description MCP 工具描述
 */
public record McpToolDescription(String serverName, String name, String namespacedName, String description, JsonNode inputSchema) {

    public static String namespaced(String serverName, String toolName) {
        return "mcp__" + serverName + "__" + toolName;
    }
}
