package com.paicode.rag.DTO;

/**
 * @Author beaker
 * @Date 2026/9/16 21:47
 * @Description 检索结果
 */
public record SearchResult(String filePath, String chunkType, String name, String content, double similarity) {
}
