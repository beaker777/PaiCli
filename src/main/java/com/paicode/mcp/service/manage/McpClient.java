package com.paicode.mcp.service.manage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicode.mcp.entity.McpToolDescription;
import com.paicode.mcp.request.McpInitializeRequest;
import com.paicode.mcp.request.McpToolCallRequest;
import com.paicode.mcp.response.McpToolCallResponse;
import com.paicode.mcp.service.jsonrpc.JsonRpcClient;
import com.paicode.mcp.service.protocol.McpSchemaSanitizer;
import com.paicode.mcp.service.transport.McpTransport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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

    public McpClient(String serverName, McpTransport transport) {
        this.serverName = serverName;
        this.transport = transport;
        this.rpc = new JsonRpcClient(this.transport);
    }

    /**
     * 进行初始化握手
     */
    public void initialize() throws IOException {
        rpc.request("initialize", McpInitializeRequest.toJson(), 30);
        rpc.sendNotification("notifications/initialized", JsonNodeFactory.instance.objectNode());
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
}
