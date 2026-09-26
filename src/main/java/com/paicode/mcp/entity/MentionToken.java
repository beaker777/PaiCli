package com.paicode.mcp.entity;

/**
 * @Author beaker
 * @Date 2026/9/26 16:49
 * @Description 内容条目
 */
public record MentionToken(String serverName, String uri, int start, int end, String raw) {
}
