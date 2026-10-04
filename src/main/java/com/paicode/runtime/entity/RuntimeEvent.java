package com.paicode.runtime.entity;

import java.time.Instant;

/**
 * @Author beaker
 * @Date 2026/10/4 03:54
 * @Description 运行时任务
 */
public record RuntimeEvent(long id, String threadId, String type, String data, Instant createdAt) {
}
