package com.paicode.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicode.llm.DTO.ChatResponse;
import com.paicode.llm.DTO.Message;
import com.paicode.llm.DTO.Tool;
import com.paicode.llm.DTO.ToolCall;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @Author beaker
 * @Date 2026/9/7 21:05
 * @Description DeepSeek 客户端
 */
public class DeepSeekClient {

    private static final String API_URL = "https://api.deepseek.com/chat/completions";
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";
    private final String apiKey;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final OkHttpClient okHttpClient;

    public DeepSeekClient(String apiKey) {
        this.apiKey = apiKey;

        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        ObjectNode requestBody = objectMapper.createObjectNode();

        // 默认模型
        requestBody.put("model", DEFAULT_MODEL);

        //添加消息
        ArrayNode messagesArray = requestBody.putArray("messages");
        for(Message msg : messages) {
            ObjectNode messageNode = messagesArray.addObject();
            messageNode.put("role", msg.role());
            messageNode.put("content", msg.content());

            // 工具调用
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                ArrayNode toolCallsArray = messageNode.putArray("tool_calls");
                for (ToolCall tc : msg.toolCalls()) {
                    ObjectNode toolCallNode = toolCallsArray.addObject();
                    toolCallNode.put("id", tc.id());
                    toolCallNode.put("type", "function");

                    ObjectNode functionNode = toolCallNode.putObject("function");
                    functionNode.put("name", tc.function().name());
                    functionNode.put("arguments", tc.function().arguments());
                }
            }

            // 工具调用结果
            if (msg.toolCallId() != null) {
                messageNode.put("tool_call_id", msg.toolCallId());
            }
        }

        // 添加工具
        ArrayNode toolsArray = requestBody.putArray("tools");
        for (Tool tool : tools) {
            ObjectNode toolNode = toolsArray.addObject();
            toolNode.put("type", "function");

            ObjectNode functionNode = toolNode.putObject("function");
            functionNode.put("name", tool.name());
            functionNode.put("description", tool.description());
            functionNode.set("parameters", tool.parameters());
        }

        // 构建请求
        RequestBody body = RequestBody.create(
                requestBody.toString(),
                MediaType.parse("application/json")
        );
        Request request = new Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("API 请求失败: " + response.code() + " - " + response.body().toString());
            }

            String responseBody = response.body().string();
            JsonNode root = objectMapper.readTree(responseBody);

            // 解析响应
            JsonNode choice = root.path("choices").get(0);
            JsonNode message = choice.path("message");

            String role = message.path("role").asText();
            String content = message.path("content").asText();

            // 解析工具调用
            List<ToolCall> toolCalls = null;
            if (message.has("tool_calls") && message.path("tool_calls").isArray()) {
                toolCalls = new ArrayList<>();
                for (JsonNode tc : message.path("tool_calls")) {
                    toolCalls.add(
                            new ToolCall(
                                    tc.path("id").asText(),
                                    new ToolCall.Function(
                                            tc.path("function").path("name").asText(),
                                            tc.path("function").path("arguments").asText()
                                    )
                            )
                    );
                }
            }

            // 解析 token 使用
            JsonNode usage = root.path("usage");
            int inputTokens = usage.path("prompt_tokens").asInt();
            int outputTokens = usage.path("completion_tokens").asInt();

            return new ChatResponse(role, content, toolCalls, inputTokens, outputTokens);
        }
    }
}
