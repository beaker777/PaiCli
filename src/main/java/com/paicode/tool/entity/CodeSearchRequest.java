package com.paicode.tool.entity;

import java.nio.file.Path;

/**
 * @Author beaker
 * @Date 2026/10/7 08:53
 * @Description code 搜索请求
 */
public record CodeSearchRequest(String query, Path root, Path projectRoot,
                                String glob, boolean regex, boolean caseSensitive,
                                int contextLines, int maxResults, int headLimit) {
}
