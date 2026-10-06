package com.paicode.memory.service.manager;

import com.paicode.context.ContextProfile;
import com.paicode.llm.service.model.LlmClient;
import com.paicode.llm.service.model.impl.DeepSeekClient;
import com.paicode.memory.constant.MemoryType;
import com.paicode.memory.entity.MemoryEntry;
import com.paicode.memory.service.compress.ContextCompressor;
import com.paicode.memory.service.compress.TokenBudget;
import com.paicode.memory.service.memorize.ConversationMemory;
import com.paicode.memory.service.memorize.LongTermMemory;
import com.paicode.memory.service.query.MemoryRetriever;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @Author beaker
 * @Date 2026/9/14 18:10
 * @Description Memory 系统的门面类, 管理记忆相关的服务
 */
@Getter
public class MemoryManager {

    private Logger log = LoggerFactory.getLogger(MemoryManager.class);
    private final ConversationMemory shortTermMemory;
    private final LongTermMemory longTermMemory;
    private final ContextCompressor compressor;
    private final MemoryRetriever retriever;
    private TokenBudget tokenBudget;
    private ContextProfile contextProfile;
    private String currentProject;

    // 工具调用结果在记忆中保存的最大长度
    private static final int MAX_TOOL_RESULT_CHARS = 500;

    public MemoryManager(LlmClient llmClient) {
        this(llmClient, ContextProfile.from(llmClient), null);
    }

    public MemoryManager(LlmClient llmClient, int shortTermBudget, int contextWindow, LongTermMemory longTermMemory) {
        this(llmClient, ContextProfile.custom(contextWindow, shortTermBudget), longTermMemory);
    }

    public MemoryManager(LlmClient llmClient, ContextProfile contextProfile, LongTermMemory longTermMemory) {
        this.contextProfile = contextProfile;
        this.shortTermMemory = new ConversationMemory(contextProfile.shortTermMemoryBudget());
        this.longTermMemory = longTermMemory != null ? longTermMemory : new LongTermMemory();
        this.compressor = new ContextCompressor(llmClient);
        this.retriever = new MemoryRetriever(shortTermMemory, this.longTermMemory);
        this.tokenBudget = new TokenBudget(contextProfile.maxContextWindow());
        this.currentProject = defaultProjectKey();
    }

    public void setLlmClient(LlmClient llmClient) {
        this.compressor.setLlmClient(llmClient);
        applyContextProfile(ContextProfile.from(llmClient));
    }

    public void setProjectPath(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return;
        }
        this.currentProject = normalizeProjectKey(projectPath);
    }

    public void applyContextProfile(ContextProfile contextProfile) {
        this.contextProfile = contextProfile;
        this.tokenBudget = new TokenBudget(contextProfile.maxContextWindow());
        this.shortTermMemory.setMaxTokens(contextProfile.shortTermMemoryBudget());
    }

    /**
     * 添加用户信息到短期记忆
     */
    public void addUserMessage(String content) {
        MemoryEntry entry = new MemoryEntry(
                "user-" + UUID.randomUUID().toString().substring(0, 8),
                content,
                MemoryType.CONVERSATION,
                Map.of("source", "user"),
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
                Map.of("source", "assistant"),
                MemoryEntry.estimateTokens(content)
        );
        shortTermMemory.store(entry);

        compressIfNeeded();
    }

    /**
     * 添加工具调用结果到短期记忆, 截断过长结果, 避免快速撑爆上下文
     */
    public void addToolResult(String toolName, String result) {
        // 将 toolCall 进行截断
        String truncated = result.length() > MAX_TOOL_RESULT_CHARS
                ? result.substring(0, MAX_TOOL_RESULT_CHARS) + "...(已截断)"
                : result;

        String content = "[" + toolName + "] " + truncated;
        MemoryEntry entry = new MemoryEntry(
                "tool-" + UUID.randomUUID().toString().substring(0, 8),
                content,
                MemoryType.TOOL_RESULT,
                Map.of("source", "tool", "toolName", toolName),
                MemoryEntry.estimateTokens(result)
        );
        shortTermMemory.store(entry);

        compressIfNeeded();
    }

    /**
     * 存储关键事实到长期记忆
     */
    public void storeFact(String fact) {
        storeFact(fact, "project");
    }

    public void storeFact(String fact, String scope) {
        String normalizedScope = normalizeScope(scope);
        Map<String, String> metadata = "global".equals(normalizedScope)
                ? Map.of("source", "fact", "scope", "global")
                : Map.of("source", "fact", "scope", "project", "project", currentProject);
        MemoryEntry entry = new MemoryEntry(
                "fact-" + UUID.randomUUID().toString().substring(0, 8),
                fact,
                MemoryType.FACT,
                metadata,
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

    public List<MemoryEntry> listLongTerm() {
        return longTermMemory.getAll();
    }

    public List<MemoryEntry> searchLongTerm(String query, int limit) {
        return longTermMemory.search(query, limit, currentProject);
    }

    public boolean deleteLongTerm(String id) {
        return longTermMemory.delete(id);
    }


    /**
     * 构建上下文
     */
    public String buildContextForQuery(String query, int maxTokens) {
        return retriever.buildContextForQuery(query, maxTokens, currentProject);
    }

    /**
     * 记录 token 使用
     */
    public void recordTokenUsage(int inputTokens, int outputTokens) {
        tokenBudget.recordUsage(inputTokens, outputTokens);
    }

    public void recordTokenUsage(int inputTokens, int outputTokens, int cachedInputTokens) {
        tokenBudget.recordUsage(inputTokens, outputTokens, cachedInputTokens);
    }

    /**
     * 检查并触发压缩
     *
     * @return 是否进行了压缩
     */
    public boolean compressIfNeeded() {
        if (!tokenBudget.needsCompression(shortTermMemory, contextProfile.compressionTriggerRatio())) {
            return false;
        }

        int beforeTokens = shortTermMemory.getTokenCount();
        log.info("上下文占用达到压缩阈值（{}%），触发短期记忆压缩", (int) (contextProfile.compressionTriggerRatio() * 100));
        String summary = compressor.compress(shortTermMemory);
        if (summary != null) {
            int afterTokens = shortTermMemory.getTokenCount();
            String preview = summary.substring(0, Math.min(100, summary.length()));
            log.info("短期记忆压缩完成: {} -> {} tokens, summaryPreview={}", beforeTokens, afterTokens, preview);
        }
        return summary != null;
    }

    /**
     * 清空短期记忆, 保留长期记忆 (先提取事实)
     */
    public void clearShortTerm() {
        shortTermMemory.clear();
    }

    /**
     * 清空长期记忆
     */
    public void clearLongTerm() {
        longTermMemory.clear();
    }

    /**
     * 获取记忆系统的整体状态
     */
    public String getSystemStatus() {
        return "上下文策略: " + contextProfile.summary() + "\n" +
                shortTermMemory.getStatusSummary() + "\n" +
                longTermMemory.getStatusSummary() + "\n" +
                tokenBudget.getUsageReport();
    }

    private static String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "project";
        }
        String normalized = scope.trim().toLowerCase();
        return "global".equals(normalized) ? "global" : "project";
    }

    private static String defaultProjectKey() {
        return normalizeProjectKey(System.getProperty("user.dir"));
    }

    private static String normalizeProjectKey(String path) {
        try {
            Path candidate = Path.of(path).toAbsolutePath().normalize();
            if (Files.exists(candidate)) {
                return candidate.toRealPath().toString();
            }
            return candidate.toString();
        } catch (Exception e) {
            return Path.of(path).toAbsolutePath().normalize().toString();
        }
    }
}
