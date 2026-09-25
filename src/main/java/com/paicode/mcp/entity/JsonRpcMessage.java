package com.paicode.mcp.entity;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * @Author beaker
 * @Date 2026/9/25 13:40
 * @Description JsonRPC 消息
 */
public class JsonRpcMessage {

    public record Request(long id, String method, JsonNode params) {

    }

    public record Response(long id, JsonNode result, Error error) {

    }

    public record Notification(String method, JsonNode params) {

    }

    public record Error(int code, String message, JsonNode data) {

    }
}
