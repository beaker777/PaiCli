package com.paicode.mcp.request;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * @Author beaker
 * @Date 2026/9/25 16:20
 * @Description MCP 初始化请求
 */
public final class McpInitializeRequest {

    public static final String PROTOCOL_VERSION = "2025-03-26";

    public static ObjectNode toJson() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();

        root.put("protocolVersion", PROTOCOL_VERSION);

        ObjectNode capabilities = root.putObject("capabilities");
        capabilities.putObject("tools");

        ObjectNode clientInfo = root.putObject("clientInfo");
        clientInfo.put("name", "paicode");
        clientInfo.put("version", "10.0.0");

        return root;
    }
}
