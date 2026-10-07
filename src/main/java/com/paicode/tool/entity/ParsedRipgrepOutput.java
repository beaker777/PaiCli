package com.paicode.tool.entity;

import java.util.List;

/**
 * @Author beaker
 * @Date 2026/10/7 09:15
 * @Description
 */
public record ParsedRipgrepOutput(List<GrepMatch> matches, boolean partial, String partialReason) {
}
