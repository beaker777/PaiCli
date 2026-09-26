package com.paicode.llm.service.model;

import com.paicode.llm.entity.ChatResponse;
import com.paicode.llm.entity.Message;
import com.paicode.llm.entity.Tool;
import com.paicode.llm.service.stream.StreamListener;

import java.io.IOException;
import java.util.List;

/**
 * @Author beaker
 * @Date 2026/9/23 21:30
 * @Description 模型同一接口
 */
public interface LlmClient {

    ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException;

    ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException;

    String getModelName();

    String getProviderName();

    default int maxContextWindow() {
        return 128_000;
    }

    default boolean supportsPromptCaching() {
        return false;
    }

    default String promptCacheMode() {
        return "none";
    }
}
