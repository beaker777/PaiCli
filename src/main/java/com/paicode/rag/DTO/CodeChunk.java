package com.paicode.rag.DTO;

/**
 * @Author beaker
 * @Date 2026/9/16 19:39
 * @Description 代码块
 */
public record CodeChunk(String filePath, String chunkType, String name, String content, int startLine, int endLine) {

    /**
     * 文件级别的代码块
     */
    public static CodeChunk fileChunk(String filePath, String content) {
        return new CodeChunk(filePath, "file", filePath, content, 0, 0);
    }

    /**
     * 类级别的代码块
     */
    public static CodeChunk classChunk(String filePath, String className, String content, int startLine, int endLine) {
        return new CodeChunk(filePath, "class", className, content, startLine, endLine);
    }

    /**
     * 方法级别的代码块
     */
    public static CodeChunk methodChunk(String filePath, String methodName, String content, int startLine, int endLine) {
        return new CodeChunk(filePath, "method", methodName, content, startLine, endLine);
    }

    /**
     * 生成用于 embedding 的文本
     */
    public String toEmbeddingText() {
        return String.format("[%s:%s] %s", chunkType, name, content);
    }
}
