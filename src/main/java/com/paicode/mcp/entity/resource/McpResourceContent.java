package com.paicode.mcp.entity.resource;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * @Author beaker
 * @Date 2026/9/26 17:08
 */
public record McpResourceContent(String uri, String mimeType, String text, String blob) {

    public static McpResourceContent fromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        String uri = node.path("uri").asText("");
        String mimeType = node.path("mimeType").asText("");
        String text = node.hasNonNull("text") ? node.path("text").asText("") : null;
        String blob = node.hasNonNull("blob") ? node.path("blob").asText("") : null;
        return new McpResourceContent(uri, mimeType, text, blob);
    }

    public boolean isText() {
        return text != null;
    }
}
