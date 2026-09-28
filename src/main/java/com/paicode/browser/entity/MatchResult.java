package com.paicode.browser.entity;

/**
 * @Author beaker
 * @Date 2026/9/27 22:41
 * @Description 敏感网页匹配结果
 */
public record MatchResult(boolean matched, String pattern) {

    public static MatchResult matched(String pattern) {
        return new MatchResult(true, pattern);
    }

    public static MatchResult notMatched() {
        return new MatchResult(false, null);
    }
}
