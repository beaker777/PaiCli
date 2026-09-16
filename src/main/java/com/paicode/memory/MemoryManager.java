package com.paicode.memory;

import com.paicode.llm.DeepSeekClient;
import com.paicode.memory.constant.MemoryType;
import com.paicode.memory.service.compress.ContextCompressor;
import com.paicode.memory.service.compress.TokenBudget;
import com.paicode.memory.service.memorize.ConversationMemory;
import com.paicode.memory.service.memorize.LongTermMemory;
import com.paicode.memory.service.query.MemoryRetriever;
import lombok.Getter;

import java.util.List;
import java.util.UUID;

/**
 * @Author beaker
 * @Date 2026/9/14 18:10
 * @Description Memory 系统的门面类, 管理记忆相关的服务
 */
@Getter
public class MemoryManager {

    private final ConversationMemory shortTermMemory;
    private final LongTermMemory longTermMemory;
    private final ContextCompressor compressor;
    private final MemoryRetriever retriever;
    private final TokenBudget tokenBudget;


    public MemoryManager(DeepSeekClient llmClient) {
        this(llmClient, 200000, 8192);
    }

    public MemoryManager(DeepSeekClient llmClient, int contextWindow, int shortTermBudget) {
        this(llmClient, null, contextWindow, shortTermBudget);
    }

    public MemoryManager(DeepSeekClient llmClient, LongTermMemory longTermMemory, int contextWindow, int shortTermBudget) {
        this.shortTermMemory = new ConversationMemory(shortTermBudget);
        this.longTermMemory = longTermMemory != null ? longTermMemory : new LongTermMemory();
        this.compressor = new ContextCompressor(llmClient);
        this.retriever = new MemoryRetriever(shortTermMemory, this.longTermMemory);
        this.tokenBudget = new TokenBudget(contextWindow);
    }

    /**
     * 添加用户信息到短期记忆
     */
    public void addUserMessage(String content) {
        MemoryEntry entry = new MemoryEntry(
                "user-" + UUID.randomUUID().toString().substring(0, 8),
                content,
                MemoryType.CONVERSATION,
                null,
                MemoryEntry.estimateTokens(content)
        );
        shortTermMemory.store(entry);

        compressIfNeeded();
    }

    /**
     * 添加助手回复到短期记忆
     */
    public void addAssistantMessage(String content) {
        MemoryEntry entry = new MemoryEntry(
                "assistant-" + UUID.randomUUID().toString().substring(0, 8),
                content,
                MemoryType.CONVERSATION,
                null,
                MemoryEntry.estimateTokens(content)
        );
        shortTermMemory.store(entry);

        compressIfNeeded();
    }

    /**
     * 添加工具调用结果到短期记忆
     */
    public void addToolResult(String toolName, String result) {
        MemoryEntry entry = new MemoryEntry(
                "tool-" + UUID.randomUUID().toString().substring(0, 8),
                "[" + toolName + "] " + result,
                MemoryType.TOOL_RESULT,
                null,
                MemoryEntry.estimateTokens(result)
        );
        shortTermMemory.store(entry);

        compressIfNeeded();
    }

    /**
     * 存储关键事实到长期记忆
     */
    public void storeFact(String fact) {
        MemoryEntry entry = new MemoryEntry(
                "fact-" + UUID.randomUUID().toString().substring(0, 8),
                fact,
                MemoryType.FACT,
                null,
                MemoryEntry.estimateTokens(fact)
        );
        longTermMemory.store(entry);
    }

    /**
     * 检索最相关的记忆
     */
    public List<MemoryEntry> retrieveRelevant(String query, int limit) {
        return retriever.retrieve(query, limit);
    }

    /**
     * 构建上下文
     */
    public String buildContextForQuery(String query, int maxTokens) {
        return retriever.buildContextForQuery(query, maxTokens);
    }

    /**
     * 记录 token 使用
     */
    public void recordTokenUsage(int inputTokens, int outputTokens) {
        tokenBudget.recordUsage(inputTokens, outputTokens);
    }

    /**
     * 检查并触发压缩
     *
     * @return 是否进行了压缩
     */
    public boolean compressIfNeeded() {
        if (!tokenBudget.needsCompression(shortTermMemory)) {
            return false;
        }

        System.out.println("短期记忆接近预算上限, 开始压缩");
        String summary = compressor.compress(shortTermMemory);
        if (summary != null) {
            System.out.println("压缩完成, 摘要: " +
                    summary.substring(0, Math.min(100, summary.length())) + "...");
        }
        return summary != null;
    }

    /**
     * 对话结束时, 将关键事实提取到长期记忆
     */
    public void extractAndSaveFacts() {
        List<MemoryEntry> conversations = shortTermMemory.getAll();
        if (conversations.isEmpty()) {
            return;
        }

        System.out.println("提取关键事实到长期记忆...");
        List<String> facts = compressor.extractFacts(conversations, longTermMemory);
        if (!facts.isEmpty()) {
            System.out.println("提取了 " + facts.size() + " 条事实");
        }
    }

    /**
     * 清空短期记忆, 保留长期记忆 (先提取事实)
     */
    public void clearShortTerm() {
        extractAndSaveFacts();
        shortTermMemory.clear();
    }

    /**
     * 获取记忆系统的整体状态
     */
    public String getSystemStatus() {
        return shortTermMemory.getStatusSummary() + "\n" +
                longTermMemory.getStatusSummary() + "\n" +
                tokenBudget.getUsageReport();
    }
}
