package com.paicode.rag.entity;

/**
 * @Author beaker
 * @Date 2026/9/17 17:52
 * @Description 索引结果
 */
public record IndexResult(int chunkCount, int relationCount, String message) {
}
