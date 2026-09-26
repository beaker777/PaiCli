package com.paicode.mcp.entity.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicode.mcp.entity.McpToolDescription;
import com.paicode.mcp.service.manage.McpClient;

import java.util.List;
import java.util.function.Function;

/**
 * @Author beaker
 * @Date 2026/9/26 18:38
 * @Description Mcp 资源工具
 */
public class McpResourceTool {

    public static final String LIST_RESOURCES = "list_resources";
    public static final String READ_RESOURCE = "read_resource";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static List<McpToolDescription> descriptions(String serverName) {
        return List.of(
                new McpToolDescription(
                        serverName,
                        LIST_RESOURCES,
                        McpToolDescription.namespaced(serverName, LIST_RESOURCES),
                        "列出 MCP server 暴露的 resources，返回 URI、名称、MIME 类型和描述",
                        emptyObjectSchema()
                ),
                new McpToolDescription(
                        serverName,
                        READ_RESOURCE,
                        McpToolDescription.namespaced(serverName, READ_RESOURCE),
                        "读取 MCP resource 内容。参数 uri 必须来自 list_resources 或用户明确提供的 resource URI",
                        readResourceSchema()
                )
        );
    }

    public static Function<String, String> invoker(McpClient client, McpToolDescription description) {
        return argumentsJson -> {
            try {
                if (LIST_RESOURCES.equals(description.name())) {
                    return McpClient.formatResources(client.listResources());
                }
                if (READ_RESOURCE.equals(description.name())) {
                    JsonNode args = argumentsJson == null || argumentsJson.isBlank()
                            ? JsonNodeFactory.instance.objectNode()
                            : MAPPER.readTree(argumentsJson);
                    String uri = args.path("uri").asText("");
                    if (uri.isBlank()) {
                        return "读取 MCP resource 失败: 缺少必填参数 uri";
                    }
                    return McpClient.formatResourceContents(client.readResource(uri));
                }
                return "未知 MCP resource 虚拟工具: " + description.name();
            } catch (Exception e) {
                return "MCP resource 工具调用失败 (" + description.serverName() + "/" + description.name() + "): " + e.getMessage();
            }
        };
    }

    private static ObjectNode emptyObjectSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        return schema;
    }

    private static ObjectNode readResourceSchema() {
        ObjectNode schema = emptyObjectSchema();
        ObjectNode properties = (ObjectNode) schema.get("properties");
        ObjectNode uri = properties.putObject("uri");
        uri.put("type", "string");
        uri.put("description", "要读取的 MCP resource URI");
        schema.putArray("required").add("uri");
        return schema;
    }
}
