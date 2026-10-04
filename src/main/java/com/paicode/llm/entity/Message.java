package com.paicode.llm.entity;

import java.util.ArrayList;
import java.util.List;

/**
 * @Author beaker
 * @Date 2026/9/7 21:06
 * @Description 消息
 */
public record Message(String role,
                      String reasoningContent, String content,
                      List<ToolCall> toolCalls, String toolCallId,
                      List<ContentPart> contentParts) {

    public Message(String role, String reasoningContent, String content, List<ToolCall> toolCalls, String toolCallId) {
        this(role, reasoningContent, content, toolCalls, toolCallId, null);
    }

    public Message(String role, String content) {
        this(role, null, content, null, null);
    }

    public static Message system(String content) {
        return new Message("system", content);
    }

    public static Message user(String content) {
        return new Message("user", content);
    }

    public static Message user(List<ContentPart> contentParts) {
        return new Message("user", null, plainText(contentParts), null, null,
                contentParts == null ? null : List.copyOf(contentParts));
    }

    public static Message assistant(String content) {
        return new Message("assistant", content);
    }

    public static Message assistant(String reasoningContent, String content) {
        return new Message("assistant", reasoningContent, content, null, null);
    }

    public static Message assistant(String content, List<ToolCall> toolCalls) {
        return new Message("assistant", null, content, toolCalls, null);
    }

    public static Message assistant(String reasoningContent, String content, List<ToolCall> toolCalls) {
        return new Message("assistant", reasoningContent, content, toolCalls, null);
    }

    public static Message tool(String toolCallId, String content) {
        return new Message("tool", null, content, null, toolCallId);
    }

    public boolean hasContentParts() {
        return contentParts != null && !contentParts.isEmpty();
    }

    public boolean hasImageContent() {
        return hasContentParts() && contentParts.stream().anyMatch(ContentPart::isImage);
    }

    public int imagePartCount() {
        if (!hasContentParts()) {
            return 0;
        }

        int count = 0;
        for (ContentPart part : contentParts) {
            if (part != null && part.isImage()) {
                count++;
            }
        }
        return count;
    }

    public Message withoutImageContent() {
        if (!hasImageContent()) {
            return this;
        }

        List<ContentPart> stripped = new ArrayList<>();
        int omitted = 0;
        for (ContentPart part : contentParts) {
            if (part == null) {
                continue;
            }
            if (part.isImage()) {
                omitted++;
            } else {
                stripped.add(part);
            }
        }
        stripped.add(ContentPart.text("[历史图片附件已省略 " + omitted
                + " 张；如需重新查看，请使用上文 Image source 或相关工具结果。]"));
        return new Message(role, plainText(stripped), reasoningContent, toolCalls, toolCallId, List.copyOf(stripped));
    }

    public Message withoutReasoningContent() {
        if (reasoningContent == null || reasoningContent.isBlank()) {
            return this;
        }
        return new Message(role, content, null, toolCalls, toolCallId, contentParts);
    }

    private static String plainText(List<ContentPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        int imageCount = 0;
        for (ContentPart part : parts) {
            if (part == null) {
                continue;
            }
            if (part.isText() && part.text() != null && !part.text().isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(part.text());
            } else if (part.isImage()) {
                imageCount++;
            }
        }

        if (imageCount > 0) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append("[已附加 ").append(imageCount).append(" 张图片]");
        }
        return sb.toString();
    }
}
