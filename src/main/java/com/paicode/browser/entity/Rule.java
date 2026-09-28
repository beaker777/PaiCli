package com.paicode.browser.entity;

import java.util.regex.Pattern;

/**
 * @Author beaker
 * @Date 2026/9/27 22:43
 * @Description 敏感网页匹配规则
 */
public record Rule(String pattern, Pattern regex) {
}
