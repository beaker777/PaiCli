package com.paicode.rag.DTO;

/**
 * @Author beaker
 * @Date 2026/9/16 21:40
 * @Description 带向量的代码块
 */
public record CodeChunkEntry(CodeChunk chunk, float[] embedding) {
}
