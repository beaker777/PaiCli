package com.paicode.policy.entity;

import java.util.regex.Pattern;

/**
 * @Author beaker
 * @Date 2026/9/25 01:01
 * @Description 拒绝规则
 */
public record DenyRule(String reason, Pattern pattern) {
}
