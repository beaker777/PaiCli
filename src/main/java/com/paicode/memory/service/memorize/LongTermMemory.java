package com.paicode.memory.service.memorize;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.paicode.memory.entity.MemoryEntry;
import com.paicode.memory.constant.MemoryType;
import com.paicode.memory.service.query.MemoryQueryTokenizer;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * @Author beaker
 * @Date 2026/9/14 20:33
 * @Description 长期记忆
 */
public class LongTermMemory implements Memory {

    private static final String STORAGE_DIR_PROPERTY = "paicode.memory.dir";
    private static final String STORAGE_DIR_ENV = "PAICODE_MEMORY_DIR";
    private static final String STORAGE_FILE = "long_term_memory.json";
    private final Map<String, MemoryEntry> entries;
    private final AtomicInteger tokenCounter;
    private final ObjectMapper mapper;
    private final File storageFile;

    public LongTermMemory() {
        this(resolveStorageDir());
    }

    public LongTermMemory(File storageDir) {
        this.entries = new ConcurrentHashMap<>();
        this.tokenCounter = new AtomicInteger(0);
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);

        // 确保存储目录存在
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }
        this.storageFile = new File(storageDir, STORAGE_FILE);

        // 加载磁盘中的长期记忆
        loadFromDisk();
    }

    @Override
    public void store(MemoryEntry entry) {
        // 去重检查, 如果存在完全相同的长期记忆, 跳过
        boolean duplicate = entries.values().stream()
                .anyMatch(e -> e.getContent().equals(entry.getContent()));
        if (duplicate) return;

        entries.put(entry.getId(), entry);
        tokenCounter.addAndGet(entry.getTokenCount());
        saveToDisk();
    }

    @Override
    public Optional<MemoryEntry> retrieve(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public List<MemoryEntry> search(String query, int limit) {
        Set<String> queryTokens = MemoryQueryTokenizer.tokenize(query);

        // 记忆内容或者 metadata 匹配均可
        return entries.values().stream()
                .filter(entry -> {
                    if (MemoryQueryTokenizer.matches(entry.getContent(), queryTokens)) {
                        return true;
                    }

                    return entry.getMetadata().values().stream()
                            .anyMatch(value -> MemoryQueryTokenizer.matches(value, queryTokens));
                })
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
            tokenCounter.addAndGet(-removed.getTokenCount());
            saveToDisk();
            return true;
        }
        return false;
    }

    @Override
    public void clear() {
        entries.clear();
        tokenCounter.set(0);
        saveToDisk();
    }

    @Override
    public int getTokenCount() {
        return tokenCounter.get();
    }

    @Override
    public int size() {
        return entries.size();
    }

    /**
     * 按类型筛选记忆
     */
    public List<MemoryEntry> getByType(MemoryType type) {
        return entries.values().stream()
                .filter(entry -> entry.getType() == type)
                .toList();
    }

    /**
     * 生成记忆状态摘要
     */
    public String getStatusSummary() {
        Map<MemoryType, Long> typeCounts = entries.values().stream()
                .collect(Collectors.groupingBy(MemoryEntry::getType, Collectors.counting()));

        return String.format("长期记忆: %d条 / %d tokens (事实: %d, 摘要: %d, 工具结果: %d)",
                entries.size(), tokenCounter.get(),
                typeCounts.getOrDefault(MemoryType.FACT, 0L),
                typeCounts.getOrDefault(MemoryType.SUMMARY, 0L),
                typeCounts.getOrDefault(MemoryType.TOOL_RESULT, 0L));
    }

    private void loadFromDisk() {
        if (!storageFile.exists()) {
            return;
        }

        try {
            List<Map<String, Object>> dataList = mapper.readValue(storageFile, List.class);
            for (Map<String, Object> data : dataList) {
                MemoryEntry entry = mapToEntry(data);

                if (entry != null) {
                    entries.put(entry.getId(), entry);
                    tokenCounter.addAndGet(entry.getTokenCount());
                }
            }

            System.out.println("加载了 " + entries.size() + " 条长期记忆");
        } catch (IOException e) {
            System.out.println("加载长期记忆失败: " + e.getMessage());
        }
    }

    /**
     * 持久化到磁盘
     */
    public void saveToDisk() {
        try {
            List<Map<String, Object>> dataList = entries.values().stream()
                    .map(this::entryToMap)
                    .toList();
            mapper.writeValue(storageFile, dataList);
        } catch (IOException e) {
            System.out.println("长期记忆持久化失败: " + e.getMessage());
        }
    }

    private MemoryEntry mapToEntry(Map<String, Object> map) {
        try {
            String id = (String) map.get("id");
            String content = (String) map.get("content");
            MemoryType type = MemoryType.valueOf((String) map.get("type"));

            Instant timestamp = null;
            Object timestampObj = map.get("timestamp");
            if (timestampObj instanceof String timeStampValue && !timeStampValue.isBlank()) {
                timestamp = Instant.parse(timeStampValue);
            }

            Map<String, String> metadata = new HashMap<>();
            Object metaObj = map.get("metadata");
            if (metaObj instanceof Map) {
                ((Map<String, Object>) metaObj).forEach((k, v) -> metadata.put(k, String.valueOf(v)));
            }

            int tokenCount = map.get("tokenCount") instanceof Number n ? n.intValue() : MemoryEntry.estimateTokens(content);

            return new MemoryEntry(id, content, type, timestamp, metadata, tokenCount);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> entryToMap(MemoryEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entry.getId());
        map.put("content", entry.getContent());
        map.put("type", entry.getType().name());
        map.put("timestamp", entry.getTimestamp().toString());
        map.put("metadata", entry.getMetadata());
        map.put("tokenCount", entry.getTokenCount());
        return map;
    }

    private static File resolveStorageDir() {
        String configureDir = System.getProperty(STORAGE_DIR_PROPERTY);
        if (configureDir == null || configureDir.isBlank()) {
            configureDir = System.getenv(STORAGE_DIR_ENV);
        }
        if (configureDir != null && !configureDir.isBlank()) {
            return new File(configureDir);
        }
        return new File(new File(System.getProperty("user.home"), ".paicode"), "memory");
    }
}
