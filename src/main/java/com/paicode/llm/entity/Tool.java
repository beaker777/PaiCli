package com.paicode.llm.entity;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * @Author beaker
 * @Date 2026/9/7 21:07
 * @Description 工具
 */
public record Tool(String name, String description, JsonNode parameters) {
}
