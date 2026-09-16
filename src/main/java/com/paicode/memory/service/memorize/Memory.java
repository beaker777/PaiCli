package com.paicode.memory.service.memorize;

import com.paicode.memory.MemoryEntry;

import java.util.List;
import java.util.Optional;

/**
 * @Author beaker
 * @Date 2026/9/14 19:19
 * @Description 记忆的统一抽象类, 分为长期记忆和短期记忆
 */
public interface Memory {

    /**
     * 存储一条记忆
     */
    void store(MemoryEntry entry);

    /**
     * 根据 id 检索记忆
     */
    Optional<MemoryEntry> retrieve(String id);

    /**
     * 搜索相关记忆
     */
    List<MemoryEntry> search(String query, int limit);

    /**
     * 获取所有记忆
     */
    List<MemoryEntry> getAll();

    /**
     * 删除指定记忆
     */
    boolean delete(String id);

    /**
     * 清空所有记忆
     */
    void clear();

    /**
     * 获取当期记忆的总 token
     */
    int getTokenCount();

    /**
     * 获取记忆条数
     */
    int size();
}
