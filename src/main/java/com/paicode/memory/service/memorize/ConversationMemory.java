package com.paicode.memory.service.memorize;

import com.paicode.memory.entity.MemoryEntry;
import com.paicode.memory.service.query.MemoryQueryTokenizer;

import java.util.*;

/**
 * @Author beaker
 * @Date 2026/9/14 19:27
 * @Description 对话记忆, 短期记忆, 过期的记忆会被压缩
 */
public class ConversationMemory implements Memory {

    private final Map<String, MemoryEntry> entries;
    private final int maxTokens;
    private int currentTokens;
    private final List<MemoryEntry> compressedSummaries;

    public ConversationMemory(int maxTokens) {
        this.entries = new LinkedHashMap<>();
        this.compressedSummaries = new ArrayList<>();
        this.currentTokens = 0;
        this.maxTokens = maxTokens;
    }

    @Override
    public void store(MemoryEntry entry) {
        entries.put(entry.getId(), entry);
        currentTokens += entry.getTokenCount();

        // 移除最后一条记忆, 等待被压缩
        while (currentTokens > maxTokens && entries.size() > 1) {
            removeOldest();
        }
    }

    @Override
    public Optional<MemoryEntry> retrieve(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public List<MemoryEntry> search(String query, int limit) {
        Set<String> queryTokens = MemoryQueryTokenizer.tokenize(query);
        return entries.values().stream()
                .filter(entry -> MemoryQueryTokenizer.matches(entry.getContent(), queryTokens))
                .limit(limit)
                .toList();
    }

    @Override
    public List<MemoryEntry> getAll() {
        return new ArrayList<>(entries.values());
    }

    @Override
    public boolean delete(String id) {
        MemoryEntry removed = entries.remove(id);
        if (removed != null) {
            currentTokens -= removed.getTokenCount();
            return true;
        }
        return false;
    }

    @Override
    public void clear() {
        entries.clear();
        compressedSummaries.clear();
        currentTokens = 0;
    }

    @Override
    public int getTokenCount() {
        return currentTokens;
    }

    @Override
    public int size() {
        return entries.size();
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    private void removeOldest() {
        Iterator<Map.Entry<String, MemoryEntry>> iterator = entries.entrySet().iterator();
        Map.Entry<String, MemoryEntry> oldest = iterator.next();

        // 删除最后一条, 并等待压缩
        iterator.remove();
        currentTokens -= oldest.getValue().getTokenCount();
        compressedSummaries.add(oldest.getValue());
    }

    /**
     * 获取记忆使用率
     */
    public double getUsageRatio() {
        return maxTokens > 0 ? (double) currentTokens / maxTokens : 0;
    }

    /**
     * 生成记忆状态摘要
     */
    public String getStatusSummary() {
        return String.format("短期记忆: %d 条 / %d tokens (预算: %d, 使用率: %.0f%%, 已压缩: %d条)",
                entries.size(), currentTokens, maxTokens, getUsageRatio() * 100, compressedSummaries.size());
    }
}
