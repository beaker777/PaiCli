package com.paicode.tool.entity;

import java.util.List;

/**
 * @Author beaker
 * @Date 2026/10/7 09:13
 * @Description 可变搜索
 */
public record MutableMatch(String file, int lineNumber, List<ContextLine> context) {

    public GrepMatch toGrepMatch() {
        return new GrepMatch(file, lineNumber, context);
    }
}
