package com.paicode.web.entity;

/**
 * @Author beaker
 * @Date 2026/9/24 18:21
 * @Description 一条抓取结果
 */
public record FetchResult(String url, String title, String markdown, int contentLength,
                          boolean truncated, boolean bodyEmpty, String hint) {

    public static FetchResult ok(String url, String title, String markdown, int originalLength, boolean truncated) {
        boolean empty = markdown == null || markdown.isBlank();
        String hint = empty ? "未提取到正文, 可能是 JS 渲染或反爬墙, 本次范围内不再重试" : "";

        return new FetchResult(url, title == null ? "" : title, markdown == null ? "" : markdown,
                originalLength, truncated, empty, hint);
    }
}
