package com.paicode.image.entity;

/**
 * @Author beaker
 * @Date 2026/10/4 18:52
 * @Description Osascript 结果
 */
public record OsascriptOutcome(int exitCode, String stderr, boolean timeOut) {
}
