package com.paicode.mcp.service.manage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicode.mcp.entity.McpToolDescription;
import com.paicode.mcp.entity.resource.McpResourceContent;
import com.paicode.mcp.entity.resource.McpResourceDescription;
import com.paicode.mcp.exception.JsonRpcException;
import com.paicode.mcp.request.McpInitializeRequest;
import com.paicode.mcp.request.McpToolCallRequest;
import com.paicode.mcp.response.McpToolCallResponse;
import com.paicode.mcp.service.jsonrpc.JsonRpcClient;
import com.paicode.mcp.service.protocol.McpSchemaSanitizer;
import com.paicode.mcp.service.transport.McpTransport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * @Author beaker
 * @Date 2026/9/25 16:35
 * @Description MCP 客户端
 */
public class McpClient implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String serverName;
    private final JsonRpcClient rpc;
    private final McpTransport transport;
    private volatile JsonNode serverCapabilities = JsonNodeFactory.instance.objectNode();

    public McpClient(String serverName, McpTransport transport) {
        this.serverName = serverName;
        this.transport = transport;
        this.rpc = new JsonRpcClient(this.transport);
    }

    /**
     * 进行初始化握手
     */
    public void initialize() throws IOException {
        JsonNode result = rpc.request("initialize", McpInitializeRequest.toJson(), 30);
        serverCapabilities = result == null ? JsonNodeFactory.instance.objectNode() : result.path("capabilities");

        rpc.sendNotification("notifications/initialized", JsonNodeFactory.instance.objectNode());
    }

    public boolean supportsResources() {
        return serverCapabilities.has("resources");
    }

    public boolean supportsPrompts() {
        return serverCapabilities.has("prompts");
    }

    /**
     * 列出工具
     */
    public List<McpToolDescription> listTools() throws IOException {
        JsonNode result = rpc.request("tools/list", JsonNodeFactory.instance.objectNode(), 30);
        JsonNode tools = result.path("tools");
        if (!tools.isArray()) {
            return List.of();
        }

        List<McpToolDescription> descriptions = new ArrayList<>();
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }

            String description = tool.path("description").asText("");
            JsonNode schema = McpSchemaSanitizer.sanitize(tool.path("inputSchema"));
            descriptions.add(new McpToolDescription(
                    serverName,
                    name,
                    McpToolDescription.namespaced(serverName, name),
                    description,
                    schema
            ));
        }
        return descriptions;
    }

    /**
     * 调用工具
     */
    public String callTool(String toolName, String argumentsJson) throws IOException {
        JsonNode args;
        if (argumentsJson == null || argumentsJson.isBlank()) {
            args  = JsonNodeFactory.instance.objectNode();
        } else {
            args = MAPPER.readTree(argumentsJson);
        }

        ObjectNode params = McpToolCallRequest.toJson(toolName, args);
        JsonNode result = rpc.request("tools/call", params, 60);
        McpToolCallResponse callResponse = MAPPER.treeToValue(result, McpToolCallResponse.class);
        String formatted = callResponse.formatForLlm();

        if (callResponse.isError()) {
            return "MCP 工具返回错误: " + formatted;
        }
        return formatted;
    }

    /**
     * 列出资源
     */
    public List<McpResourceDescription> listResources() throws IOException {
        try {
            JsonNode result = rpc.request("resouces/list", JsonNodeFactory.instance.objectNode(), 30);
            JsonNode resources = result.path("resources");
            if (!resources.isArray()) {
                return List.of();
            }

            List<McpResourceDescription> descriptions = new ArrayList<>();
            for (JsonNode resource : resources) {
                McpResourceDescription description = McpResourceDescription.fromJson(serverName, resource);
                if (description != null) {
                    descriptions.add(description);
                }
            }
            return descriptions;
        } catch (JsonRpcException e) {
            if (e.code() == -32601) {
                return List.of();
            }
            throw e;
        }
    }

    /**
     * 读取资源
     */
    public List<McpResourceContent> readResource(String uri) throws IOException {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("uri", uri);

        JsonNode result = rpc.request("resources/read", params, 60);
        JsonNode contents = result.path("contents");
        if (!contents.isArray()) {
            return List.of();
        }

        List<McpResourceContent> resourceContents = new ArrayList<>();
        for (JsonNode content : contents) {
            McpResourceContent resourceContent = McpResourceContent.fromJson(content);
            if (resourceContent != null) {
                resourceContents.add(resourceContent);
            }
        }
        return resourceContents;
    }

    /**
     * 列出 prompts
     */
    public List<String> listPrompts() throws IOException {
        try {
            JsonNode result = rpc.request("prompts/list", JsonNodeFactory.instance.objectNode(), 30);
            JsonNode prompts = result.path("prompts");
            if (!prompts.isArray()) {
                return List.of();
            }

            List<String> lines = new ArrayList<>();
            for (JsonNode prompt : prompts) {
                String name = prompt.path("name").asText("");
                if (name.isBlank()) {
                    continue;
                }

                String title = prompt.path("title").asText("");
                String description = prompt.path("description").asText("");
                String display = title.isBlank() ? name : title + " (" + name + ")";
                lines.add(description.isBlank() ? display : display + " - " + description);
            }
            return lines;
        } catch (JsonRpcException e) {
            if (e.code() == -32601) {
                return List.of();
            }
            throw e;
        }
    }

    public void onNotification(Consumer<JsonNode> listener) {
        rpc.onNotification(listener);
    }

    public static String formatResources(List<McpResourceDescription> resources) {
        if (resources == null || resources.isEmpty()) {
            return "📭 该 MCP server 暂无 resources";
        }

        StringBuilder sb = new StringBuilder("📚 MCP resources（").append(resources.size()).append("）\n");
        for (McpResourceDescription resource : resources) {
            sb.append("- ").append(resource.uri());
            String name = resource.displayName();
            if (name != null && !name.isBlank() && !name.equals(resource.uri())) {
                sb.append(" | ").append(name);
            }
            if (resource.mimeType() != null && !resource.mimeType().isBlank()) {
                sb.append(" | ").append(resource.mimeType());
            }
            if (resource.description() != null && !resource.description().isBlank()) {
                sb.append("\n  ").append(resource.description());
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    public static String formatResourceContents(List<McpResourceContent> contents) {
        if (contents == null || contents.isEmpty()) {
            return "📭 MCP resource 内容为空";
        }

        StringBuilder sb = new StringBuilder();
        for (McpResourceContent content : contents) {
            String mimeType = content.mimeType() == null || content.mimeType().isBlank()
                    ? "application/octet-stream"
                    : content.mimeType();
            sb.append("<resource uri=\"").append(escapeXml(content.uri()))
                    .append("\" mimeType=\"").append(escapeXml(mimeType)).append("\">\n");
            if (content.isText()) {
                sb.append(content.text());
            } else {
                sb.append("[binary resource blob omitted, base64 length=")
                        .append(content.blob() == null ? 0 : content.blob().length())
                        .append(']');
            }
            sb.append("\n</resource>\n");
        }
        return sb.toString().trim();
    }

    public List<String> stderrLines() {
        return transport.stderrLines();
    }

    public Long processId() {
        return transport.processId();
    }

    public String transportName() {
        return transport.transportName();
    }

    @Override
    public void close() {
        // 直接走 transport-level 关闭信号：stdio 通过 stdin EOF + 进程销毁；HTTP 通过 DELETE session。
        // 之前会先发 shutdown notification，但当 server 卡死 / 队列堵塞时这条通知会让 close 阻塞 60 秒。
        // 移除后退出更快、行为更可预期；shutdown 语义改由 transport 层承担。
        rpc.close();
    }

    private static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
