package com.paicode.mcp.service.mention;

import com.paicode.mcp.entity.MentionToken;
import com.paicode.mcp.entity.ResourceReadResult;
import com.paicode.mcp.service.manage.McpServerManager;

import java.util.List;

/**
 * @Author beaker
 * @Date 2026/9/26 17:01
 * @Description 输入内容扩展
 */
public class AtMentionExpander {

    private static final int MAX_INLINE_RESOURCE_CHARS = 200_000;

    private final McpServerManager serverManager;

    public AtMentionExpander(McpServerManager serverManager) {
        this.serverManager = serverManager;
    }

    public String expand(String input) {
        // 解析 input 中的 tokens
        List<MentionToken> tokens = AtMentionParser.parse(input);
        if (tokens.isEmpty()) {
            return input;
        }

        // 反向拓展, 避免位置错误
        StringBuilder expanded = new StringBuilder(input);
        for (int i = tokens.size() - 1; i >= 0; i --) {
            MentionToken token = tokens.get(i);
            String expandedToken = expandToken(token);

            expanded.replace(token.start(), token.end(), expandedToken);
        }
        return expanded.toString();
    }

    /**
     * 将输入的内容替换为 xml resource
     */
    private String expandToken(MentionToken token) {
        try {
            ResourceReadResult result = serverManager.readResourceForMention(token.serverName(), token.uri());
            String content = result.content();
            boolean truncated = false;
            if (content.length() > MAX_INLINE_RESOURCE_CHARS) {
                content = content.substring(0, MAX_INLINE_RESOURCE_CHARS);
                truncated = true;
            }

            String mimeType = result.mimeType() == null || result.mimeType().isBlank()
                    ? "text/plain"
                    : result.mimeType();
            String suffix = truncated
                    ? "\n[resource truncated by PaiCODE at " + MAX_INLINE_RESOURCE_CHARS + " chars]"
                    : "";
            return "<resource server=\"" + escapeXml(token.serverName()) +
                    "\" uri=\"" + escapeXml(token.uri()) +
                    "\" mimeType=\"" + escapeXml(mimeType) + "\">\n" +
                    content + suffix + "\n</resource>";
        } catch (Exception e) {
            return token.raw() + "\n<resource_error server=\"" + escapeXml(token.serverName()) +
                    "\" uri=\"" + escapeXml(token.uri()) + "\">" +
                    escapeXml(e.getMessage()) + "</resource_error>";
        }
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
