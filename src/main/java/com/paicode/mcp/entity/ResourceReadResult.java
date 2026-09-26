package com.paicode.mcp.entity;

import com.paicode.mcp.entity.resource.McpResourceContent;

import java.util.List;

/**
 * @Author beaker
 * @Date 2026/9/26 17:06
 * @Description 资源读取结果
 */
public record ResourceReadResult(String content, String mimeType) {

    public static ResourceReadResult from(List<McpResourceContent> contents) {
        if (contents == null || contents.isEmpty()) {
            return new ResourceReadResult("", "text/plain");
        }

        StringBuilder text = new StringBuilder();
        String firstMimeType = null;
        for (McpResourceContent content : contents) {
            if (firstMimeType == null || firstMimeType.isBlank()) {
                firstMimeType = content.mimeType();
            }

            if (content.isText()) {
                text.append(content.text());
            } else {
                text.append("[binary resource blob omitted, base64 length=")
                        .append(content.blob() == null ? 0 : content.blob().length())
                        .append(']');
            }
            text.append(System.lineSeparator());
        }

        return new ResourceReadResult(text.toString().trim(), firstMimeType);
    }
}
