package com.paicode.agent;

import com.paicode.llm.DTO.ChatResponse;
import com.paicode.llm.DTO.Message;
import com.paicode.llm.DTO.ToolCall;
import com.paicode.llm.DeepSeekClient;
import com.paicode.memory.MemoryManager;
import com.paicode.tool.ToolRegistry;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * @Author beaker
 * @Date 2026/9/9 19:27
 * @Description Agent
 */
@Getter
public class Agent {

    private final DeepSeekClient llmClient;
    private final ToolRegistry toolRegistry;
    private final List<Message> conversationHistory;
    private final MemoryManager memoryManager;

    // 最大迭代次数
    private static final int MAX_ITERATIONS = 10;

    // 系统提示词
    private static final String SYSTEM_PROMPT = """
            你是一个智能编程助手，可以帮助用户完成各种任务。

            你可以使用以下工具来完成任务：
            1. read_file - 读取文件内容
            2. write_file - 写入文件内容
            3. list_dir - 列出目录内容
            4. execute_command - 执行Shell命令
            5. create_project - 创建新项目结构
            6. search_code - 语义检索代码库, 参数: {"query": "自然语言描述", "top_k": 5}

            当需要操作文件、执行命令或创建项目时，请使用工具调用。
            使用工具后，根据工具返回的结果继续思考下一步行动。
            
            如果用户询问和代码库相关的问题 (如"这个类是干什么的", "哪里用了某个功能").
            请优先使用 search_code 工具检索相关代码, 再基于检索结果回答.

            如果提供了相关记忆, 请参考记忆来辅助决策.

            请用中文回复用户。
            """;

    public Agent(String apikey) {
        llmClient = new DeepSeekClient(apikey);
        toolRegistry = new ToolRegistry();
        conversationHistory = new ArrayList<>();
        memoryManager = new MemoryManager(llmClient);

        // 添加系统提示词
        conversationHistory.add(Message.system(SYSTEM_PROMPT));
    }

    // 运行
    public String run(String userInput) {
        // 存入短期记忆
        memoryManager.addUserMessage(userInput);

        // 检索相关长期记忆, 注入到 system prompt
        String memoryContext = memoryManager.buildContextForQuery(userInput, 500);
        updateSystemPromptWithMemory(memoryContext);

        // 将用户输入添加到历史
        conversationHistory.add(Message.user(userInput));
        StringBuilder reasoningTranscript = new StringBuilder();

        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration ++;

            try {
                // 调用模型
                ChatResponse response = llmClient.chat(conversationHistory, toolRegistry.getTools());

                // 调用工具
                if (response.hasToolCalls()) {
                    appendReasoning(reasoningTranscript, response.reasoningContent());

                    // 添加信息
                    conversationHistory.add(Message.assistant(response.reasoningContent(), response.content(), response.toolCalls()));

                    for (ToolCall toolCall : response.toolCalls()) {
                        String name = toolCall.function().name();
                        String toolArgs = toolCall.function().arguments();

                        // 执行工具
                        String toolResult = toolRegistry.executeTool(name, toolArgs);

                        // 将工具调用结果存入记忆
                        memoryManager.addToolResult(name, toolResult);

                        // 将工具调用结果添加到历史
                        conversationHistory.add(Message.tool(toolCall.id(), toolResult));
                    }
                } else {
                    appendReasoning(reasoningTranscript, response.reasoningContent());

                    // 不调用工具, 结束迭代
                    conversationHistory.add(Message.assistant(response.reasoningContent(), response.content()));

                    // 存入记忆
                    memoryManager.addAssistantMessage(response.content());

                    // 记录 token 使用情况
                    memoryManager.recordTokenUsage(response.inputTokens(), response.outputTokens());

                    return formatUserFacingResponse(reasoningTranscript.toString(), response.content());
                }
            } catch (Exception e) {
                return "模型调用失败: " + e.getMessage();
            }
        }

        return "超过最大迭代次数";
    }

    // 清空历史 (保留系统提示词)
    public void clearHistory() {
        // 保存当前对话的关键事实
        memoryManager.extractAndSaveFacts();

        Message systemPrompt = conversationHistory.get(0);
        conversationHistory.clear();
        conversationHistory.add(systemPrompt);

        // 清空短期记忆
        memoryManager.getShortTermMemory().clear();
    }

    public String getSystemStatus() {
        return memoryManager.getSystemStatus();
    }

    /**
     * 将记忆注入到 system prompt 替换 conversationHistory[0]
     */
    private void updateSystemPromptWithMemory(String memoryContext) {
        if (memoryContext == null || memoryContext.isBlank()) {
            // 没有记忆的时候重置回默认的 system prompt, 避免上下文污染
            conversationHistory.set(0, Message.system(SYSTEM_PROMPT));
        } else {
            String enrichedPrompt = SYSTEM_PROMPT + "\n" + memoryContext;
            conversationHistory.set(0, Message.system(enrichedPrompt));
        }
    }

    private void appendReasoning(StringBuilder reasoningTranscript, String reasoningContent) {
        if (reasoningContent == null || reasoningContent.isBlank()) {
            return;
        }
        if (!reasoningTranscript.isEmpty()) {
            reasoningTranscript.append("\n\n");
        }

        reasoningTranscript.append(reasoningContent);
    }

    private String formatUserFacingResponse(String reasoningContent, String answer) {
        String normalizedReasoning = reasoningContent == null ? "" : reasoningContent.trim();
        String normalizedAnswer = answer == null ? "" : answer.trim();

        if (normalizedReasoning.isEmpty()) {
            return normalizedAnswer;
        }
        if (normalizedAnswer.isEmpty()) {
            return "思考过程:\n" + normalizedReasoning;
        }
        return "思考过程:\n" + normalizedReasoning + "\n\n最终结果:\n" + normalizedAnswer;
    }
}
