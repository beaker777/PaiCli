package com.paicode.memory.service.query;

import com.paicode.memory.MemoryEntry;
import com.paicode.memory.service.memorize.ConversationMemory;
import com.paicode.memory.service.memorize.LongTermMemory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * @Author beaker
 * @Date 2026/9/15 16:16
 * @Description 记忆检索器
 */
public class MemoryRetriever {

    private final ConversationMemory shortTermMemory;
    private final LongTermMemory longTermMemory;

    public MemoryRetriever(ConversationMemory shortTermMemory, LongTermMemory longTermMemory) {
        this.shortTermMemory = shortTermMemory;
        this.longTermMemory = longTermMemory;
    }

    public List<MemoryEntry> retrieve(String query, int limit) {
        List<ScoredEntry> scored = new ArrayList<>();

        // 从短期记忆中检索
        for (MemoryEntry entry : shortTermMemory.getAll()) {
            double score = computeRelevanceScore(entry, query);
            if (score > 0) {
                scored.add(new ScoredEntry(entry, score, true));
            }
        }

        // 从长期记忆中检索
        for (MemoryEntry entry : longTermMemory.getAll()) {
            double score = computeRelevanceScore(entry, query);
            if (score > 0) {
                // 为长期记忆添加一个权值
                scored.add(new ScoredEntry(entry, score * 1.2, false));
            }
        }

        // 按照得分降序排序
        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredEntry::score).reversed())
                .limit(limit)
                .map(ScoredEntry::entry)
                .toList();
    }

    /**
     * 构建上下文: 将相关记忆组装成文本, 用于注入到 LLM 的 system prompt 中
     */
    public String buildContextForQuery(String query, int maxTokens) {
        List<MemoryEntry> relevant = retrieve(query, 10);
        if (relevant.isEmpty()) return "";

        StringBuilder context = new StringBuilder();
        context.append("## 相关记忆\n\n");

        int usedTokens = 0;
        for (MemoryEntry entry : relevant) {
            if (usedTokens + entry.getTokenCount() > maxTokens) break;

            context.append("- [").append(entry.getType()).append("] ")
                    .append(entry.getContent()).append("\n");
            usedTokens += entry.getTokenCount();
        }

        context.append("\n");
        return context.toString();
    }

    /**
     * 计算记忆条目的相关度分数
     */
    private double computeRelevanceScore(MemoryEntry entry, String query) {
        String contentLower = entry.getContent().toLowerCase();
        String queryLower = query.toLowerCase();

        // 精确匹配
        if (contentLower.equals(queryLower)) {
            return 1.0;
        }

        // 关键词匹配
        Set<String> queryWords = MemoryQueryTokenizer.tokenize(query);
        int matchedWord = 0;
        for (String queryWord : queryWords) {
            if (!queryWord.isBlank() && contentLower.contains(queryWord)) {
                matchedWord ++;
            }
        }

        if (matchedWord == 0) {
            return 0;
        }
        double keyWordScore = (double) matchedWord / queryWords.size();

        // 时间衰减 (时间越接近分数越高, 逐渐衰减到 0.5)
        long ageMs = System.currentTimeMillis() - entry.getTimestamp().toEpochMilli();
        double ageHours = ageMs / (1000.0 * 60 * 60);
        double timeDecay = Math.max(0.5, 1.0 - ageHours / 24.0);

        return keyWordScore * timeDecay;
    }
}
