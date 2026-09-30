package com.paicode.memory.service.compress;

import com.paicode.llm.entity.ChatResponse;
import com.paicode.llm.entity.Message;
import com.paicode.llm.entity.ToolCall;
import com.paicode.llm.service.model.LlmClient;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * @Author beaker
 * @Date 2026/10/1 01:51
 * @Description 对话历史压缩
 *
 * 在之前并未对 Agent 的 conversationHistory 进行压缩, 本类用于处理该问题
 *
 * 保留 retainRecentRounds 条 userMessage, 压缩前面的内容
 */
@Setter
public class ConversationHistoryCompactor {

    private static final Logger log = LoggerFactory.getLogger(ConversationHistoryCompactor.class);

    private static final int DEFAULT_RETAIN_RECENT_ROUNDS = 3;
    private static final int MAX_SUMMARY_INPUT_CHARS = 60_000;

    private static final String SUMMARY_PROMPT = """
            请把下面的对话历史压缩成简明摘要，保留：
            1. 用户提出的关键诉求与目标
            2. Agent 已经完成的关键操作（哪些工具调用了什么、返回了什么核心结果）
            3. 已经达成的共识或结论
            4. 仍未解决的问题或待办

            不要复述每条原文，不要列举所有工具调用，不要保留无关闲聊。
            输出 1-3 段中文，不要用列表，不要加任何前缀或元描述。

            === 待压缩的对话 ===
            %s
            === 待压缩的对话（结束）===
            """;

    private LlmClient llmClient;
    private final int retainRecentRounds;

    public ConversationHistoryCompactor(LlmClient llmClient) {
        this(llmClient, DEFAULT_RETAIN_RECENT_ROUNDS);
    }

    public ConversationHistoryCompactor(LlmClient llmClient, int retainRecentRounds) {
        this.llmClient = llmClient;
        this.retainRecentRounds = Math.max(1, retainRecentRounds);
    }

    public boolean compactIfNeeded(List<Message> history, int triggerTokens) throws IOException {
        // 判断 token 是否达到 trigger
        if (history == null || history.isEmpty()) return false;
        int currentTokens = TokenBudget.estimateMessagesTokens(history);
        if (currentTokens < triggerTokens) return false;

        // 提取用户信息的索引
        int systemEnd = "system".equals(history.get(0).role()) ? 1 : 0;
        List<Integer> userMessages = new ArrayList<>();
        for (int i = systemEnd; i < history.size(); i++) {
            if ("user".equals(history.get(i).role())) {
                userMessages.add(i);
            }
        }
        if (userMessages.size() <= retainRecentRounds) {
            log.info("compactIfNeeded skip: only {} user turns, < retain {}",
                    userMessages.size(), retainRecentRounds);
            return false;
        }

        // 分割出旧信息
        int splitIdx = userMessages.get(userMessages.size() - retainRecentRounds);
        if (splitIdx <= systemEnd) return false;
        List<Message> oldMessages = new ArrayList<>(history.subList(systemEnd, splitIdx));
        if (oldMessages.isEmpty()) return false;

        // 总结旧信息
        String summary;
        try {
            summary = summarize(oldMessages);
        } catch (IOException e) {
            log.warn("conversation summary LLM call failed; skip compaction", e);
            return false;
        }
        if (summary == null || summary.isBlank()) {
            log.warn("conversation summary returned empty; skip compaction");
            return false;
        }

        // 重构 history
        List<Message> rebuilt = new ArrayList<>();
        for (int i = 0; i < systemEnd; i++) {
            rebuilt.add(history.get(i));
        }
        rebuilt.add(Message.user("[已压缩的历史对话摘要]\n" + summary.trim()));
        rebuilt.add(Message.assistant("好的，我已了解之前的上下文，请继续。"));
        rebuilt.addAll(history.subList(splitIdx, history.size()));

        int afterTokens = TokenBudget.estimateMessagesTokens(rebuilt);
        history.clear();
        history.addAll(rebuilt);
        log.info(String.format(Locale.ROOT,
                "compacted conversationHistory: tokens %d -> %d, messages %d -> %d, summary chars %d",
                currentTokens, afterTokens, userMessages.size() + systemEnd /* 估值 */, rebuilt.size(),
                summary.length()));
        return true;
    }

    /**
     * 真正调 LLM 摘要。包可见以便测试通过子类替换。
     */
    protected String summarize(List<Message> messages) throws IOException {
        if (llmClient == null) {
            throw new IOException("LLM client not configured");
        }

        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            sb.append(m.role().toUpperCase(Locale.ROOT)).append(": ");
            if (m.content() != null) {
                sb.append(m.content());
            }
            if (m.toolCalls() != null) {
                for (ToolCall tc : m.toolCalls()) {
                    sb.append("\n  TOOL_CALL ").append(tc.function().name())
                            .append(": ").append(tc.function().arguments());
                }
            }
            sb.append("\n\n");
            if (sb.length() > MAX_SUMMARY_INPUT_CHARS) {
                sb.append("...(超长内容已截断)\n");
                break;
            }
        }

        String prompt = String.format(SUMMARY_PROMPT, sb);
        List<Message> req = List.of(
                Message.system("你是一个对话摘要助手，只输出摘要本身，不输出元描述。"),
                Message.user(prompt)
        );
        ChatResponse response = llmClient.chat(req, null);
        return response == null ? null : response.content();
    }
}
