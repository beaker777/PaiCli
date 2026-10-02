package com.paicode.renderer.entity;

/**
 * @Author beaker
 * @Date 2026/10/1 23:40
 * @Description 渲染器状态栏数据
 */
public record StatusInfo(String model, long totalTokens, long contextWindow,
                         boolean hitlEnabled, long elapsedMillis) {

    public static StatusInfo idle(String model, long contextWindow, boolean hitlEnabled) {
        return new StatusInfo(model, 0L, contextWindow, hitlEnabled, 0L);
    }
}
