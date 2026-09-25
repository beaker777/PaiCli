package com.paicode.mcp.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * @Author beaker
 * @Date 2026/9/25 17:08
 * @Description MCP 工具调用请求
 */
public class McpToolCallRequest {

    public static ObjectNode toJson(String name, JsonNode arguments) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("name", name);
        root.set("arguments", arguments == null ? JsonNodeFactory.instance.objectNode() : arguments);

        return root;
    }
}
