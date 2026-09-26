package com.paicode.agent.service;

import com.paicode.agent.constant.ExitReason;
import com.paicode.context.ContextProfile;
import com.paicode.llm.entity.ToolCall;
import com.paicode.llm.service.model.LlmClient;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * @Author beaker
 * @Date 2026/9/24 16:22
 * @Description Agent 退出循环的预算, 将何时退出循环的主导权交给 AI 自己
 *
 * 本类只承担三种职责, 避免无限的消耗 Token:
 * 1. Token 预算: 当超过 Token 预算后强制收尾.
 * 2. 停滞检测: 当连续多次调用完全相同的工具, 使用完全一致的参数时强制收尾
 * 3. 硬轮数检测: 累计迭代次数超过 hardMaxIterations 强制停止.
 */
public class AgentBudget {

    private static final int DEFAULT_STAGNATION_WINDOW = 3;
    private static final int DEFAULT_HARD_MAX_ITERATIONS = 50;

    private final int tokenBudget;
    private final int stagnationWindow;
    private final int hardMaxIterations;

    private final Deque<String> recentToolSignatures = new ArrayDeque<>();
    private int iteration;
    private int totalInputTokens;
    private int totalOutputTokens;
    private int totalCachedInputTokens;
    private boolean stagnant;

    public AgentBudget(int tokenBudget, int stagnationWindow, int hardMaxIterations) {
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException("tokenBudget must be positive");
        }
        if (stagnationWindow < 2) {
            throw new IllegalArgumentException("stagnationWindow must be >= 2");
        }
        if (hardMaxIterations <= 0) {
            throw new IllegalArgumentException("hardMaxIterations must be positive");
        }
        this.tokenBudget = tokenBudget;
        this.stagnationWindow = stagnationWindow;
        this.hardMaxIterations = hardMaxIterations;
    }

    public static AgentBudget fromSystemProperties() {
        return fromLlmClient(null);
    }

    public static AgentBudget fromLlmClient(LlmClient llmClient) {
        ContextProfile profile = ContextProfile.from(llmClient);
        return new AgentBudget(
                readIntProperty("paicode.react.token.budget", profile.agentTokenBudget()),
                readIntProperty("paicode.react.stagnation.window", DEFAULT_STAGNATION_WINDOW),
                readIntProperty("paicode.react.hard.max.iterations", DEFAULT_HARD_MAX_ITERATIONS)
        );
    }

    /**
     * 进入新一轮, 返回当前轮次
     */
    public int beginIteration() {
        return ++ iteration;
    }

    public void recordTokens(int inputTokens, int outputTokens) {
        recordTokens(inputTokens, outputTokens, 0);
    }

    public void recordTokens(int inputTokens, int outputTokens, int cachedInputTokens) {
        this.totalInputTokens += Math.max(0, inputTokens);
        this.totalOutputTokens += Math.max(0, outputTokens);
        this.totalCachedInputTokens += Math.max(0, cachedInputTokens);
    }

    /**
     * 记录本轮 toolCall, 判断是否停滞
     */
    public void recordToolCalls(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            recentToolSignatures.clear();
            return;
        }

        // 通过滑动窗口判断是否停滞
        String signature = signatureOf(toolCalls);
        recentToolSignatures.addLast(signature);
        while (recentToolSignatures.size() > stagnationWindow) {
            recentToolSignatures.removeFirst();
        }
        if (recentToolSignatures.size() == stagnationWindow) {
            String first = recentToolSignatures.peek();
            stagnant = recentToolSignatures.stream()
                    .allMatch(sig -> sig.equals(first));
        }
    }

    public ExitReason check() {
        if (stagnant) {
            return ExitReason.STAGNATION_DETECTED;
        }
        if (totalInputTokens + totalOutputTokens >= tokenBudget) {
            return ExitReason.TOKEN_BUDGET_EXCEEDED;
        }
        if (iteration >= hardMaxIterations) {
            return ExitReason.HARD_ITERATION_LIMIT;
        }
        return ExitReason.WITHIN_BUDGET;
    }

    public int iteration() {
        return iteration;
    }

    public int totalInputTokens() {
        return totalInputTokens;
    }

    public int totalOutputTokens() {
        return totalOutputTokens;
    }

    public int totalCachedInputTokens() {
        return totalCachedInputTokens;
    }

    public int tokenBudget() {
        return tokenBudget;
    }

    public int hardMaxIterations() {
        return hardMaxIterations;
    }

    public int stagnationWindow() {
        return stagnationWindow;
    }

    public String describeExit(ExitReason reason) {
        return switch (reason) {
            case WITHIN_BUDGET -> "未触发兜底条件";
            case TOKEN_BUDGET_EXCEEDED -> String.format(Locale.ROOT,
                    "Token 预算已用尽（%d / %d），任务被强制收尾",
                    totalInputTokens + totalOutputTokens, tokenBudget);
            case STAGNATION_DETECTED -> String.format(Locale.ROOT,
                    "检测到连续 %d 轮重复的工具调用，疑似死循环，已强制收尾",
                    stagnationWindow);
            case HARD_ITERATION_LIMIT -> String.format(Locale.ROOT,
                    "达到硬轮数上限（%d），已强制收尾", hardMaxIterations);
        };
    }

    private static String signatureOf(List<ToolCall> toolCalls) {
        StringBuilder sb = new StringBuilder();
        for (ToolCall tc : toolCalls) {
            sb.append(tc.function().name()).append('|')
                    .append(tc.function().arguments()).append(';');
        }

        return sb.toString();
    }

    private static int readIntProperty(String key, int defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
