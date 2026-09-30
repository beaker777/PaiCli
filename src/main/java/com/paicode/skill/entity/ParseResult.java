package com.paicode.skill.entity;

import java.util.List;
import java.util.Map;

/**
 * @Author beaker
 * @Date 2026/10/1 00:19
 * @Description yml 解析结果
 */
public record ParseResult(Map<String, Object> frontmatter, String body, List<String> warnings) {
}
