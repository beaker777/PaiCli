package com.paicode.context;

import com.paicode.llm.service.model.LlmClient;

/**
 * @Author beaker
 * @Date 2026/9/26 23:00
 * @Description 上下文策略
 */
public record ContextProfile(int maxContextWindow, int agentTokenBudget, double compressionTriggerRatio,
                             int shortTermMemoryBudget, int memoryContextTokens,
                             boolean mcpResourceIndexEnabled, boolean promptCachingSupported, String promptCacheMode) {

    public static final double DEFAULT_COMPRESSION_TRIGGER_RATIO = 0.90;
    private static final int MIN_WINDOW = 8_000;
    private static final int MCP_RESOURCE_INDEX_MIN_WINDOW = 32_000;

    public static ContextProfile from(LlmClient llmClient) {
        int window = Math.max(MIN_WINDOW, llmClient == null ? 128_000 : llmClient.maxContextWindow());
        return new ContextProfile(
                window,
                agentBudget(window),
                DEFAULT_COMPRESSION_TRIGGER_RATIO,
                shortTermBudget(window),
                memoryContextTokens(window),
                window >= MCP_RESOURCE_INDEX_MIN_WINDOW,
                llmClient != null && llmClient.supportsPromptCaching(),
                llmClient == null ? "none" : llmClient.promptCacheMode()
        );
    }

    public static ContextProfile custom(int contextWindow, int shortTermMemoryBudget) {
        int window = Math.max(MIN_WINDOW, contextWindow);
        int shortTerm = Math.max(1, shortTermMemoryBudget);
        return new ContextProfile(
                window,
                agentBudget(window),
                DEFAULT_COMPRESSION_TRIGGER_RATIO,
                shortTerm,
                memoryContextTokens(window),
                window >= MCP_RESOURCE_INDEX_MIN_WINDOW,
                false,
                "none"
        );
    }

    public int compressionTriggerTokens() {
        return (int) Math.floor(maxContextWindow * compressionTriggerRatio);
    }

    public String summary() {
        return "window: " + maxContextWindow
                + " | 压缩阈值: " + (int) (compressionTriggerRatio * 100) + "% (" + compressionTriggerTokens() + " tokens)"
                + " | 短期记忆预算: " + shortTermMemoryBudget
                + " | MCP resource 索引: " + (mcpResourceIndexEnabled ? "on" : "off")
                + " | prompt cache: " + promptCacheMode;
    }

    private static int agentBudget(int window) {
        // Agent 单次 run 的 token 上限 (input + output 累计), 保 20% 余量给响应突发
        return Math.max(4_000, (int) Math.floor(window * 0.8));
    }

    private static int shortTermBudget(int window) {
        return Math.max(4_000, (int) Math.floor(window * 0.45));
    }

    private static int memoryContextTokens(int window) {
        return Math.max(500, Math.min(5_000, window / 200));
    }
}
