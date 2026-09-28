package com.paicode.browser.entity;

/**
 * @Author beaker
 * @Date 2026/9/28 20:43
 * @Description 探针结果
 */
public record ProbeResult(boolean ok, String browserUrl, String message) {

    public static ProbeResult ok(String browserUrl) {
        return new ProbeResult(true, browserUrl, null);
    }

    public static ProbeResult failed(String message) {
        return new ProbeResult(false, null, message == null ? "连接失败" : message);
    }
}
