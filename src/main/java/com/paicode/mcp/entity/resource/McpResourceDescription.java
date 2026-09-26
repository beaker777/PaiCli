package com.paicode.mcp.entity.resource;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * @Author beaker
 * @Date 2026/9/26 16:26
 */
public record McpResourceDescription(String serverName, String uri, String name,
                                     String title, String description, String mimeType, Long size) {

    public static McpResourceDescription fromJson(String serverName, JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        String uri = node.path("uri").asText("");
        if (uri.isBlank()) {
            return null;
        }

        String name = node.path("name").asText("");
        String title = node.path("title").asText("");
        String description = node.path("description").asText("");
        String mimeType = node.path("mimeType").asText("");
        Long size = node.hasNonNull("size") ? node.path("size").asLong() : null;
        return new McpResourceDescription(serverName, uri, name, title, description, mimeType, size);
    }

    public String displayName() {
        if (title != null && !title.isBlank()) {
            return title;
        }
        if (name != null && !name.isBlank()) {
            return name;
        }
        return uri;
    }
}
