package com.paicode.memory.service.compress;

import com.paicode.llm.entity.ChatResponse;
import com.paicode.llm.entity.Message;
import com.paicode.llm.service.DeepSeekClient;
import com.paicode.memory.entity.MemoryEntry;
import com.paicode.memory.constant.MemoryType;
import com.paicode.memory.service.memorize.ConversationMemory;
import com.paicode.memory.service.memorize.LongTermMemory;

import java.io.IOException;
import java.util.*;

/**
 * @Author beaker
 * @Date 2026/9/14 22:59
 * @Description 上下文压缩
 * 1. 使用 Map-Reduce 策略, 先将旧信息分片 (Map), 再合并摘要 (Reduce)
 * 2. 保留最近 N 轮完整信息
 * 3. 压缩后的摘要注入到 ConversationMemory
 */
public class ContextCompressor {

    private final DeepSeekClient llmClient;
    private final int retainRecentRounds;

    private static final String MAP_PROMPT = """
            请将以下对话片段压缩成一段简洁的摘要，保留关键信息：
            - 用户的需求和意图
            - 已执行的操作和结果
            - 做出的决策和结论
            - 重要的技术细节
            
            对话片段：
            %s
            
            请用中文输出摘要，控制在200字以内。
            """;

    private static final String REDUCE_PROMPT = """
            请将以下多个摘要合并成一个整体摘要，保留所有关键信息。
            
            各片段摘要：
            %s
            
            请用中文输出合并摘要，控制在300字以内。
            """;

    private static final String EXTRACT_FACTS_PROMPT = """
            请从以下对话中提取“跨会话仍然成立、未来复用仍有价值”的稳定事实，格式为每行一条：
            - 用户偏好和习惯
            - 项目信息（名称、路径、技术栈）
            - 重要决策和约定

            只保留用户明确说明、或工具/代码库可验证的信息。
            绝对不要提取以下内容：
            - 当前这一轮让你执行的临时任务、步骤、todo
            - 一次性的文件名、目录名、输出要求
            - 模型自己的猜测、纠错、提醒、推断
            - “用户想要/需要/让我/请你...” 这类请求句

            对话内容：
            %s

            请每行一条事实，不要多余解释。
            """;

    // 短期事实的前缀
    private static final List<String> EPHEMERAL_FACT_PREFIXES = List.of(
            "用户想", "用户要", "用户需要", "用户请求", "帮我", "让我",
            "新建", "创建", "删除", "修改", "生成", "补充要求", "当前这一轮", "本次任务"
    );

    // 投机性提示
    private static final List<String> SPECULATION_CUES = List.of(
            "可能", "应该", "猜测", "推测", "笔误", "提醒"
    );

    // 长期事实的前缀
    private static final List<String> DURABLE_FACT_HINTS = List.of(
            "用户偏好", "用户习惯", "喜欢", "倾向", "项目", "仓库", "路径", "技术栈",
            "版本", "模型", "接口", "配置", "环境变量", "命令", "约定", "规则", "默认"
    );

    public ContextCompressor(DeepSeekClient llmClient) {
        this(llmClient, 3);
    }

    public ContextCompressor(DeepSeekClient llmClient, int retainRecentRounds) {
        this.llmClient = llmClient;
        this.retainRecentRounds = retainRecentRounds;
    }

    /**
     * 压缩对话记忆
     *
     * @param memory 短期记忆
     * @return 压缩后的摘要, 不需要压缩直接返回 null
     */
    public String compress(ConversationMemory memory) {
        List<MemoryEntry> allEntries = memory.getAll();
        if (allEntries.size() < retainRecentRounds) {
            // 数量太少, 不需要压缩
            return null;
        }

        // 分割旧信息和近期信息
        int spiltPoint = allEntries.size() - retainRecentRounds;
        ArrayList<MemoryEntry> oldEntries = new ArrayList<>(allEntries.subList(0, spiltPoint));
        ArrayList<MemoryEntry> recentEntries = new ArrayList<>(allEntries.subList(spiltPoint, allEntries.size()));

        // map 阶段: 分片并生成摘要
        List<String> chunkSummaries = mapPhase(oldEntries);
        if (chunkSummaries.isEmpty()) {
            return null;
        }

        // reduce 阶段: 合并摘要
        String finalSummary;
        if (chunkSummaries.size() == 1) {
            finalSummary = chunkSummaries.get(0);
        } else {
            finalSummary = reducePhase(chunkSummaries);
        }

        // 清空旧记忆, 注入摘要, 保留近期记忆
        memory.clear();
        MemoryEntry summaryEntry = new MemoryEntry(
                "summary-" + UUID.randomUUID().toString().substring(0, 8),
                "[历史对话摘要] " + finalSummary,
                MemoryType.SUMMARY,
                null,
                MemoryEntry.estimateTokens(finalSummary)
        );
        memory.store(summaryEntry);

        // 注入近期记忆
        for (MemoryEntry entry : recentEntries) {
            memory.store(entry);
        }

        return finalSummary;
    }

    /**
     * 从对话中提取事实, 注入长期记忆
     */
    public List<String> extractFacts(List<MemoryEntry> entries, LongTermMemory longTermMemory) {
        if (entries.isEmpty()) {
            return List.of();
        }

        StringBuilder conversation = new StringBuilder();
        for (MemoryEntry entry : entries) {
            conversation.append(resolveSource(entry).toUpperCase(Locale.ROOT))
                    .append("(").append(entry.getType()).append("): ")
                    .append(entry.getContent()).append("\n\n");
        }

        try {
            String prompt = String.format(EXTRACT_FACTS_PROMPT, conversation);
            List<Message> messages = List.of(
                    Message.system("你是一个信息提取助手, 只输出关键事实, 不输出其他内容"),
                    Message.user(prompt)
            );

            ChatResponse response = llmClient.chat(messages, null);
            String factsText = response.content();

            List<String> facts = new ArrayList<>();
            for (String line : factsText.split("\n")) {
                String fact = normalizeFactLine(line);

                if (isPersistentFactCandidate(fact)) {
                    facts.add(fact);

                    // 存入长期记忆
                    MemoryEntry factEntry = new MemoryEntry(
                            "fact-" + UUID.randomUUID().toString().substring(0, 8),
                            fact,
                            MemoryType.FACT,
                            Map.of("source", "fact_extrator"),
                            MemoryEntry.estimateTokens(fact)
                    );
                    longTermMemory.store(factEntry);
                }
            }

            return facts;
        } catch (IOException e) {
            System.out.println("事实提取失败: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * map 阶段, 将旧消息分片, 每片独立摘要
     */
    private List<String> mapPhase(List<MemoryEntry> oldEntries) {
        List<String> summaries = new ArrayList<>();
        int chunkSize = 5;
        List<List<MemoryEntry>> chunks = partition(oldEntries, chunkSize);

        for (List<MemoryEntry> chunk : chunks) {
            StringBuilder chunkText = new StringBuilder();
            for (MemoryEntry entry : chunk) {
                chunkText.append(entry.getType()).append(":")
                        .append(entry.getContent()).append("\n\n");
            }

            try {
                String prompt = String.format(MAP_PROMPT, chunkText);
                List<Message> messages = List.of(
                        Message.system("你是一个对话摘要助手"),
                        Message.user(prompt)
                );

                ChatResponse response = llmClient.chat(messages, null);
                summaries.add(response.content());
            } catch (IOException e) {
                System.out.println("摘要生成失败: " + e.getMessage());

                // 降级: 直接截取前 200 字
                String fallback = chunkText.substring(0, Math.min(200, chunkText.length()));
                summaries.add("[压缩] " + fallback);
            }
        }

        return summaries;
    }

    /**
     * reduce 阶段, 合并摘要
     */
    private String reducePhase(List<String> summaries) {
        String joined = String.join("\n\n---\n\n", summaries);

        try {
            String prompt = String.format(REDUCE_PROMPT, joined);
            List<Message> messages = List.of(
                    Message.system("你是一个摘要合并助手"),
                    Message.user(prompt)
            );

            ChatResponse response = llmClient.chat(messages, null);
            return response.content();
        } catch (IOException e) {
            System.out.println("摘要合并失败: " + e.getMessage());

            // 降级: 直接拼接
            return String.join(",", summaries);
        }
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }

        return partitions;
    }

    private String resolveSource(MemoryEntry entry) {
        String source = entry.getMetadata().get("source");
        if (source != null && !source.isBlank()) {
            return source;
        }
        if (entry.getId().startsWith("user-")) {
            return "user";
        }
        if (entry.getId().startsWith("assistant-")) {
            return "assistant";
        }
        if (entry.getId().startsWith("tool-")) {
            return "tool";
        }
        return "unknown";
    }

    private String normalizeFactLine(String line) {
        String fact = line == null ? "" : line.trim();
        if (fact.startsWith("- ")) {
            fact = fact.substring(2);
        } else if (fact.startsWith("• ")) {
            fact = fact.substring(2);
        }
        return fact.trim();
    }

    private boolean isPersistentFactCandidate(String fact) {
        if (fact == null || fact.length() <= 5) {
            return false;
        }

        String normalized = fact.toLowerCase(Locale.ROOT);
        for (String prefix : EPHEMERAL_FACT_PREFIXES) {
            if (normalized.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }

        for (String cue : SPECULATION_CUES) {
            if (normalized.contains(cue.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }

        if (normalized.contains("：") || normalized.contains(":")) {
            return true;
        }

        for (String hint : DURABLE_FACT_HINTS) {
            if (normalized.contains(hint.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        return false;
    }

}
