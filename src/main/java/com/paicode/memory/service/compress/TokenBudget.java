package com.paicode.memory.service.compress;

import com.paicode.llm.entity.Message;
import com.paicode.llm.entity.ToolCall;
import com.paicode.memory.entity.MemoryEntry;
import com.paicode.memory.service.memorize.ConversationMemory;
import lombok.Getter;

import java.util.List;

/**
 * @Author beaker
 * @Date 2026/9/15 15:53
 * @Description token 预算管理器
 */
@Getter
public class TokenBudget {

    // 上下文窗口大小
    private final int contextWindow;
    // 系统预留
    private final int reservedForSystem;
    // 工具预留
    private final int reservedForTools;
    // 回复预留
    private final int reservedForResponse;

    private int totalInputTokens;
    private int totalOutputTokens;
    private int totalCachedInputTokens;
    private int llmCallCount;

    public TokenBudget(int contextWindow) {
        this(contextWindow, 500, 800, 2000);
    }

    public TokenBudget(int contextWindow, int reservedForSystem, int reservedForTools, int reservedForResponse) {
        this.contextWindow = contextWindow;
        this.reservedForSystem = reservedForSystem;
        this.reservedForTools = reservedForTools;
        this.reservedForResponse = reservedForResponse;
        this.totalInputTokens = 0;
        this.totalOutputTokens = 0;
        this.totalCachedInputTokens = 0;
        this.llmCallCount = 0;
    }

    /**
     * 获取对话历史可用的 token 预算
     */
    public int getAvailableForConversation() {
        return contextWindow - reservedForSystem - reservedForTools - reservedForResponse;
    }

    /**
     * 检查给定的消息是否在预算范围内
     */
    public boolean isWithinBudget(List<Message> messages) {
        int estimatedTokens = estimateMessagesTokens(messages);
        return estimatedTokens <= getAvailableForConversation();
    }

    /**
     * 检查是否需要压缩, 超过指定的占用率就压缩
     */
    public boolean needsCompression(ConversationMemory memory, double triggerRatio) {
        // 压缩预算为短期记忆的 maxToken 和 contextWindow 中的较小值
        int compressionBudget = Math.min(memory.getMaxTokens(), getAvailableForConversation());
        return memory.getTokenCount() >= compressionBudget * triggerRatio;
    }

    /**
     * 兼容旧调用方, 默认 80%
     */
    public boolean needsCompression(ConversationMemory memory) {
        int compressionBudget = Math.min(memory.getMaxTokens(), getAvailableForConversation());
        return memory.getTokenCount() >= compressionBudget * 0.8;
    }

    /**
     * 统计 token 使用
     */
    public void recordUsage(int inputTokens, int outputTokens) {
        recordUsage(inputTokens, outputTokens, 0);
    }

    public void recordUsage(int inputTokens, int outputTokens, int cachedInputTokens) {
        totalInputTokens += inputTokens;
        totalOutputTokens += outputTokens;
        totalCachedInputTokens += cachedInputTokens;
        llmCallCount ++;
    }

    /**
     * 获取 token 使用统计
     */
    public String getUsageReport() {
        double avgInput = llmCallCount > 0 ? (double) totalInputTokens / llmCallCount : 0;
        return String.format(
                "Token 统计: 调用 %d 次 | 总输入: %d | 总输出: %d | cached: %d | 平均输入: %.0f | 预算: %d (可用: %d)",
                llmCallCount, totalInputTokens, totalOutputTokens, totalCachedInputTokens, avgInput,
                contextWindow, getAvailableForConversation()
        );
    }

    public static int estimateMessagesTokens(List<Message> messages) {
        if (messages == null) return 0;
        int total = 0;
        for (Message message : messages) {
            total += MemoryEntry.estimateTokens(message.content());

            // toolCall 的 token 也计算在内
            if (message.toolCalls() != null) {
                for (ToolCall toolCall : message.toolCalls()) {
                    total += MemoryEntry.estimateTokens(toolCall.function().arguments());
                }
            }
        }

        // 每条消息额外开销 4 tokens
        total += messages.size() * 4;
        return total;
    }
}
