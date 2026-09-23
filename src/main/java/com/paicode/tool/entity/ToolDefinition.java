package com.paicode.tool.entity;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * @Author beaker
 * @Date 2026/9/8 22:05
 * @Description 工具
 */
public record ToolDefinition(String name, String description, JsonNode parameters, ToolExecutor executor) {
}
