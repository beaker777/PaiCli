package com.paicode.mcp.service.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicode.mcp.exception.JsonRpcException;
import com.paicode.mcp.service.transport.McpTransport;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * @Author beaker
 * @Date 2026/9/25 14:52
 * @Description JsonRpc 客户端
 */
public class JsonRpcClient implements AutoCloseable {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;

    private final McpTransport transport;
    private final AtomicLong ids = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "paicode-mcp-jsonrpc-timeout");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Consumer<JsonNode>> notificationListeners = new CopyOnWriteArrayList<>();

    public JsonRpcClient(McpTransport transport) {
        this.transport = transport;
        this.transport.onReceive(this::handleMessage);
    }

    public JsonNode request(String method, JsonNode params) throws IOException {
        return request(method, params, DEFAULT_TIMEOUT_SECONDS);
    }

    public JsonNode request(String method, JsonNode params, long timeoutSeconds) throws IOException {
        long id = ids.incrementAndGet();

        // 构造 request
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.put("params", params);
        }

        // 创建定时任务, 超时未执行自动抛出异常
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        scheduler.schedule(() -> {
            CompletableFuture<JsonNode> removed = pending.remove(id);
            if (removed != null) {
                removed.completeExceptionally(new TimeoutException("Json-RPC request timed out: " + method));
            }
        }, timeoutSeconds, TimeUnit.SECONDS);

        try {
            // 将消息发给传输层
            transport.send(request);

            // 等待 timeout + 1s
            return future.get(timeoutSeconds + 1, TimeUnit.SECONDS);
        } catch (JsonRpcException e) {
            throw e;
        } catch (Exception e) {
            pending.remove(id);
            if (e.getCause() instanceof JsonRpcException jsonRpcException) {
                throw jsonRpcException;
            }
            throw new IOException(e.getMessage(), e);
        }
    }

    public void sendNotification(String method, JsonNode params) throws IOException {
        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        if (params != null) {
            notification.set("params", params);
        }

        transport.send(notification);
    }

    public void onNotification(Consumer<JsonNode> listener) {
        if (listener != null) {
            notificationListeners.add(listener);
        }
    }

    private void handleMessage(JsonNode message) {
        JsonNode idNode = message.get("id");
        if (idNode == null || idNode.isNull()) {
            for (Consumer<JsonNode> listener : notificationListeners) {
                listener.accept(message);
            }
            return;
        }

        long id = idNode.asLong();
        CompletableFuture<JsonNode> future = pending.remove(id);
        if (future == null) {
            return;
        }
        JsonNode error = message.get("error");
        if (error != null && !error.isNull()) {
            future.completeExceptionally(new JsonRpcException(
                    error.path("code").asInt(-32603),
                    error.path("message").asText("JSON-RPC error")));
            return;
        }
        future.complete(message.get("result"));
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        transport.close();
    }
}
