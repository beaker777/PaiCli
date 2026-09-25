package com.paicode.tool.entity;

import com.paicode.mcp.entity.McpToolDescription;

import java.util.function.Function;

/**
 * @Author beaker
 * @Date 2026/9/26 01:52
 * @Description MCP Tool
 */
public record McpRegisteredTool(McpToolDescription description, Function<String, String> invoker) {
}
