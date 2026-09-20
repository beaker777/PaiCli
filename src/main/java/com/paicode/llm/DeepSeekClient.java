package com.paicode.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicode.llm.DTO.*;
import com.paicode.llm.stream.StreamListener;
import okhttp3.*;
import okio.BufferedSource;

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

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final OkHttpClient SHARED_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    public DeepSeekClient(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * 发送聊天请求 (支持工具调用)
     */
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return chat(messages, tools, StreamListener.NO_OP);
    }

    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        return chatStream(messages, tools, listener);
    }

    /**
     * 流式聊天请求, 通过 listener 持续返回增量, 最后汇总为 response
     */
    public ChatResponse chatStream(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        // 创建 listener
        StreamListener streamListener = listener == null ? StreamListener.NO_OP : listener;

        // 构造请求体
        RequestBody body = RequestBody.create(
                buildRequestBody(messages, tools, true).toString(),
                MediaType.parse("application/json")
        );

        // 创建请求
        Request request = new Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = SHARED_HTTP_CLIENT.newCall(request).execute()) {
            ResponseBody responseBodyObj = response.body();
            if (!response.isSuccessful()) {
                String errorBody = responseBodyObj != null ? responseBodyObj.string() : "无响应体";
                throw new IOException("API 请求失败: " + response.code() + " - " + errorBody);
            }

            if (responseBodyObj == null) {
                throw new IOException("API 返回空响应体");
            }

            BufferedSource source = responseBodyObj.source();
            String role = "assistant";
            StringBuilder content = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            List<ToolCallAccumulator> toolCallAccumulators = new ArrayList<>();
            int inputTokens = 0;
            int outputTokens = 0;

            // 处理流式响应
            while (!source.exhausted()) {
                // 1. 获取响应内容
                String line = source.readUtf8Line();
                if (line == null) {
                    break;
                }

                String trimmed = line.trim();
                if (trimmed.isBlank() || !trimmed.startsWith("data:")) {
                    continue;
                }

                String payload = trimmed.substring("data:".length()).trim();
                if (payload.isBlank()) {
                    continue;
                }
                if ("[DONE]".equals(payload)) {
                    break;
                }

                // 2. 解析 JSON
                JsonNode root = mapper.readTree(payload);
                JsonNode usage = root.path("usage");
                if (!usage.isMissingNode()) {
                    inputTokens = usage.path("prompt_tokens").asInt(inputTokens);
                    outputTokens = usage.path("completion_tokens").asInt(outputTokens);
                }

                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    continue;
                }

                JsonNode choice = choices.get(0);
                JsonNode delta = choice.path("delta");
                if (delta.isMissingNode() || delta.isNull()) {
                    delta = choice.path("message");
                }
                if (delta.isMissingNode() || delta.isNull()) {
                    continue;
                }

                String deltaRole = delta.path("role").asText();
                if (!deltaRole.isBlank()) {
                    role = deltaRole;
                }
                String reasoningDelta = delta.path("reasoning_content").asText();
                if (!reasoningDelta.isBlank()) {
                    reasoning.append(reasoningDelta);
                    streamListener.onReasoningDelta(reasoningDelta);
                }
                String contentDelta = delta.path("content").asText();
                if (!contentDelta.isBlank()) {
                    content.append(contentDelta);
                    streamListener.onContentDelta(contentDelta);
                }

                mergeToolCallDeltas(toolCallAccumulators, delta.path("tool_calls"));
            }

            return new ChatResponse(
                    role,
                    content.toString(),
                    reasoning.toString(),
                    buildToolCalls(toolCallAccumulators),
                    inputTokens,
                    outputTokens
            );
        }
    }

    private ObjectNode buildRequestBody(List<Message> messages, List<Tool> tools, boolean stream) {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", DEFAULT_MODEL);
        if (stream) {
            requestBody.put("stream", true);
        }

        // 处理 messages
        ArrayNode messagesArray = requestBody.putArray("messages");
        for (Message msg : messages) {
            ObjectNode msgNode = messagesArray.addObject();
            msgNode.put("role", msg.role());
            msgNode.put("content", msg.content());
            if (msg.reasoningContent() != null && !msg.reasoningContent().isBlank()) {
                msgNode.put("reasoning_content", msg.reasoningContent());
            }

            // 如果有工具调用, 进行处理
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                ArrayNode toolCallsArray = msgNode.putArray("tool_calls");
                for (ToolCall tc : msg.toolCalls()) {
                    ObjectNode tcNode = toolCallsArray.addObject();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", "function");
                    ObjectNode functionNode = tcNode.putObject("function");
                    functionNode.put("name", tc.function().name());
                    functionNode.put("arguments", tc.function().arguments());
                }
            }

            // 如果是工具调用的结果, 记录 tool_call_id
            if (msg.toolCallId() != null) {
                msgNode.put("tool_call_id", msg.toolCallId());
            }
        }

        // 处理 tools
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = requestBody.putArray("tools");
            for (Tool tool : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode functionNode = toolNode.putObject("function");
                functionNode.put("name", tool.name());
                functionNode.put("description", tool.description());
                functionNode.set("parameters", tool.parameters());
            }
        }
        return requestBody;
    }

    private void mergeToolCallDeltas(List<ToolCallAccumulator> accumulators, JsonNode toolCallsNode) {
        if (toolCallsNode == null || !toolCallsNode.isArray()) {
            return;
        }

        for (JsonNode tc : toolCallsNode) {
            int index = tc.path("index").asInt(accumulators.size());
            while (accumulators.size() <= index) {
                accumulators.add(new ToolCallAccumulator());
            }

            ToolCallAccumulator acc = accumulators.get(index);
            String id = tc.path("id").asText();
            if (!id.isEmpty()) {
                acc.setId(id);
            }

            JsonNode function = tc.path("function");
            String name = function.path("name").asText();
            if (!name.isEmpty()) {
                acc.getName().append(name);
            }
            String arguments = function.path("arguments").asText();
            if (!arguments.isEmpty()) {
                acc.getArguments().append(arguments);
            }
        }
    }

    private List<ToolCall> buildToolCalls(List<ToolCallAccumulator> accumulators) {
        if (accumulators.isEmpty()) {
            return null;
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        for (ToolCallAccumulator acc : accumulators) {
            if (acc.getId() == null || acc.getId().isBlank()) {
                continue;
            }
            toolCalls.add(new ToolCall(
                    acc.getId(),
                    new ToolCall.Function(acc.getName().toString(), acc.getArguments().toString())
            ));
        }
        return toolCalls.isEmpty() ? null : toolCalls;
    }
}
