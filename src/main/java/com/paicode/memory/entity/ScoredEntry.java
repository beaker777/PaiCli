package com.paicode.memory.entity;

/**
 * @Author beaker
 * @Date 2026/9/15 22:59
 * @Description
 */
public record ScoredEntry(MemoryEntry entry, double score, boolean fromShortTerm) {
}
