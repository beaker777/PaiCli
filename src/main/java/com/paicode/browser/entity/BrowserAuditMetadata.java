package com.paicode.browser.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.paicode.browser.constant.BrowserMode;

import java.util.Locale;

/**
 * @Author beaker
 * @Date 2026/9/27 22:55
 * @Description 浏览器审计数据
 */
public record BrowserAuditMetadata(
        @JsonProperty("browser_mode") String browserMode,
        Boolean sensitive,
        @JsonProperty("target_url") String targetUrl
) {

    public static BrowserAuditMetadata of(BrowserMode mode, boolean sensitive, String targetUrl) {
        return new BrowserAuditMetadata(mode == null ? null : mode.name().toLowerCase(), sensitive, targetUrl);
    }
}
