package com.paicode.tui.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.paicode.browser.entity.MatchResult;

import java.util.Map;

/**
 * @Author beaker
 * @Date 2026/10/1 19:05
 * @Description 消息记录
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MessageRecord(String role, String content, long timestamp, Map<String, String> metadata) {

    public static MessageRecord of(String role, String content) {
        return new MessageRecord(role, content, System.currentTimeMillis(), Map.of());
    }

    public static MessageRecord of(String role, String content, Map<String, String> metadata) {
        return new MessageRecord(role, content, System.currentTimeMillis(), metadata);
    }
}
