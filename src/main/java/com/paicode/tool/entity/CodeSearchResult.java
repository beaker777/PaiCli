package com.paicode.tool.entity;

import java.util.List;

/**
 * @Author beaker
 * @Date 2026/10/7 08:55
 * @Description Code 搜索结果
 */
public record CodeSearchResult(String engine, List<GrepMatch> matches,
                               boolean partial, String partialReason) {
}
