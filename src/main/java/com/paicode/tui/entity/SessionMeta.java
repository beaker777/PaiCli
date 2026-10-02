package com.paicode.tui.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;

/**
 * @Author beaker
 * @Date 2026/10/1 19:09
 * @Description 会话元数据
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionMeta(String sessionId, String title,
                          long createAt, long lastActiveAt, int messageCount) {
}
