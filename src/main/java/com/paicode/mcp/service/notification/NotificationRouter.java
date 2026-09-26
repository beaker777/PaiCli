package com.paicode.mcp.service.notification;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * @Author beaker
 * @Date 2026/9/26 17:29
 * @Description 路由 server -> client 的通知到 handler
 */
public class NotificationRouter implements Consumer<JsonNode>, AutoCloseable {

    private final Map<String, Consumer<JsonNode>> handlers = new ConcurrentHashMap<>();
    private final ExecutorService dispatcher;

    public NotificationRouter() {
        AtomicInteger threadId = new AtomicInteger();

        this.dispatcher = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread("paicode-mcp-notifications-" + threadId.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    public void on(String method, Consumer<JsonNode> handler) {
        if (method == null || method.isBlank() || handler == null) {
            return;
        }

        handlers.put(method, handler);
    }

    @Override
    public void accept(JsonNode message) {
        if (message == null || message.has("id")) {
            return;
        }

        String method = message.path("method").asText("");
        Consumer<JsonNode> handler = handlers.get(method);
        if (handler == null) {
            return;
        }

        JsonNode params = message.path("params");
        // 异步派发
        try {
            dispatcher.submit(() -> {
                try {
                    handler.accept(params);
                } catch (Exception ignored) {

                }
            });
        } catch (Exception ignored) {
            // executor 已关闭, 忽略
        }
    }

    @Override
    public void close() throws Exception {
        dispatcher.shutdownNow();
    }
}


