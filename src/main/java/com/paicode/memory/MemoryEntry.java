package com.paicode.memory;

import com.paicode.memory.constant.MemoryType;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;

/**
 * @Author beaker
 * @Date 2026/9/14 18:17
 * @Description 记忆条目
 */
@Getter
public class MemoryEntry {

    private final String id;
    private final String content;
    private final MemoryType type;
    private final Instant timestamp;
    private final Map<String, String> metadata;
    private final int tokenCount;

    public MemoryEntry(String id, String content, MemoryType type, Map<String, String> metadata, int tokenCount) {
        this(id, content, type, Instant.now(), metadata, tokenCount);
    }

    public MemoryEntry(String id, String content, MemoryType type, Instant timestamp, Map<String, String> metadata, int tokenCount) {
        this.id = id;
        this.content = content;
        this.type = type;
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.metadata = metadata != null ? metadata : Map.of();
        this.tokenCount = tokenCount;
    }

    // 粗略估算 token 数
    public static int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;

        long chineseChars = text.chars().filter(c -> 0x9FFF > c && c > 0x4E00).count();
        long otherChars = text.length() - chineseChars;
        return (int) Math.ceil(chineseChars / 1.5 + otherChars / 4.0);
    }

    @Override
    public String toString() {
        return "[%s] %s: %s".formatted(type, id,
                content.length() > 80 ? content.substring(0, 80) + "..." : content);
    }
}
