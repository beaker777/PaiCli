package com.paicode.llm.service.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicode.llm.entity.*;
import com.paicode.llm.service.stream.StreamListener;
import okhttp3.*;
import okio.BufferedSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @Author beaker
 * @Date 2026/9/23 21:33
 * @Description 兼容 Openai 的抽象类
 */
public abstract class AbstractOpenaiCompatibleClient implements LlmClient {

    private static final ObjectMapper mapper = new ObjectMapper();

    // readTimeout 是流式输出时两次 read 间隔的最大时长, 使用 callTimeOut 做兜底
    // 支持在配置文件中覆盖
    private static final OkHttpClient SHARED_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(readTimeoutSeconds("paicode.llm.connect.timeout.seconds", 60), TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds("paicode.llm.read.timeout.seconds", 300), TimeUnit.SECONDS)
            .writeTimeout(readTimeoutSeconds("paicode.llm.write.timeout.seconds", 60), TimeUnit.SECONDS)
            .callTimeout(readTimeoutSeconds("paicode.llm.call.timeout.seconds", 600), TimeUnit.SECONDS)
            .build();

    private static long readTimeoutSeconds(String key, long defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }

        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public abstract String getApiUrl();

    public abstract String getModel();

    public abstract String getApiKey();

    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return chat(messages, tools, StreamListener.NO_OP);
    }

    /**
     * 流式聊天请求, 通过 listener 持续返回增量, 最后汇总为 response
     */
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        // 创建 listener
        StreamListener streamListener = listener == null ? StreamListener.NO_OP : listener;

        // 构造请求体
        RequestBody body = RequestBody.create(
                buildRequestBody(messages, tools, true).toString(),
                MediaType.parse("application/json")
        );

        // 创建请求
        Request request = new Request.Builder()
                .url(getApiUrl())
                .header("Authorization", "Bearer " + getApiKey())
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
            int cachedInputTokens = 0;

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
                    cachedInputTokens = parseCachedInputTokens(usage, cachedInputTokens);
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
                    outputTokens,
                    cachedInputTokens
            );
        }
    }

    private int parseCachedInputTokens(JsonNode usage, int fallback) {
        int cached = usage.path("cached_tokens").asInt(fallback);
        cached = usage.path("prompt_cache_hit_tokens").asInt(cached);
        cached = usage.path("input_cache_hit_tokens").asInt(cached);

        JsonNode promptDetails = usage.path("prompt_tokens_details");
        if (!promptDetails.isMissingNode()) {
            cached = promptDetails.path("cached_tokens").asInt(cached);
        }

        JsonNode inputDetails = usage.path("input_tokens_details");
        if (!inputDetails.isMissingNode()) {
            cached = inputDetails.path("cached_tokens").asInt(cached);
        }
        return cached;
    }

    private ObjectNode buildRequestBody(List<Message> messages, List<Tool> tools, boolean stream) {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", getModel());
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
