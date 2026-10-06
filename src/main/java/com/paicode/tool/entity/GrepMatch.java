package com.paicode.tool.entity;

import java.util.List;

/**
 * @Author beaker
 * @Date 2026/10/7 05:20
 * @Description grep 匹配结果
 */
public record GrepMatch(String file, int lineNumber, List<ContextLine> context) {
}
