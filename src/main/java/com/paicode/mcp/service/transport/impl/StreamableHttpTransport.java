package com.paicode.mcp.service.transport.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicode.mcp.request.McpInitializeRequest;
import com.paicode.mcp.service.transport.McpTransport;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * @Author beaker
 * @Date 2026/9/25 16:11
 * @Description Stream 向 URL 发送请求, MCP server 流式返回内容
 */
public class StreamableHttpTransport implements McpTransport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build();
    private final String url;
    private final Map<String, String> headers;
    private final List<Consumer<JsonNode>> listeners = new CopyOnWriteArrayList<>();
    private volatile String sessionId;

    public StreamableHttpTransport(String url, Map<String, String> headers) {
        this.url = url;
        this.headers = headers;
    }

    @Override
    public void send(JsonNode message) throws IOException {
        // 构造 requestBody
        RequestBody body = RequestBody.create(MAPPER.writeValueAsString(message), JSON);
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", McpInitializeRequest.PROTOCOL_VERSION)
                .post(body);
        headers.forEach(builder::header);
        if (sessionId != null && !sessionId.isBlank()) {
            builder.header("Mcp-Session-Id", sessionId);
        }

        try (Response response = client.newCall(builder.build()).execute()) {
            String newSession = response.header("Mcp-Session-Id");
            if (newSession != null && !newSession.isBlank()) {
                sessionId = newSession;
            }
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " " + response.message());
            }

            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                return;
            }

            String contentType = response.header("Content-Type", "");
            String raw = responseBody.string();
            // notification 路径下 server 可以返回 202 + 空 body 或 200 + 空 body。
            // 这里 swallow 空响应，避免 Jackson 对空字符串抛 MismatchedInputException。
            if (raw.isBlank()) {
                return;
            }

            List<JsonNode> messages = contentType.contains("text/event-stream")
                    ? parseSse(raw)
                    : List.of(MAPPER.readTree(raw));
            for (JsonNode node : messages) {
                for (Consumer<JsonNode> listener : listeners) {
                    listener.accept(node);
                }
            }
        }
    }

    @Override
    public void onReceive(Consumer<JsonNode> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public String transportName() {
        return "http";
    }

    @Override
    public void close() {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("MCP-Protocol-Version", McpInitializeRequest.PROTOCOL_VERSION)
                .header("Mcp-Session-Id", sessionId)
                .delete();
        headers.forEach(builder::header);

        // close 是 best-effort：server 已经关停 / 网络不通时不应该让 PaiCode 退出卡住。
        // 主 client 的 callTimeout 是 60s，这里用 5s 短超时单独发请求。
        OkHttpClient closeClient = client.newBuilder()
                .callTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .connectTimeout(2, TimeUnit.SECONDS)
                .build();
        try (Response ignored = closeClient.newCall(builder.build()).execute()) {
            // best effort
        } catch (IOException ignored) {
        }
    }

    private static List<JsonNode> parseSse(String raw) throws IOException {
        List<JsonNode> messages = new ArrayList<>();
        StringBuilder data = new StringBuilder();

        for (String line : raw.split("\\R")) {
            if (line.isBlank()) {
                if (!data.isEmpty()) {
                    messages.add(MAPPER.readTree(data.toString()));
                    data.setLength(0);
                }
                continue;
            }

            if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring("data:".length()).trim());
            }
        }
        if (!data.isEmpty()) {
            messages.add(MAPPER.readTree(data.toString()));
        }

        return messages;
    }
}
