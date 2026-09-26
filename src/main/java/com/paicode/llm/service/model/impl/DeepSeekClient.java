package com.paicode.llm.service.model.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicode.llm.entity.*;
import com.paicode.llm.service.model.AbstractOpenaiCompatibleClient;
import com.paicode.llm.service.stream.StreamListener;
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
public class DeepSeekClient extends AbstractOpenaiCompatibleClient {

    private static final String API_URL = "https://api.deepseek.com/chat/completions";
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";
    private final String apiKey;
    private final String model;

    public DeepSeekClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public DeepSeekClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
    }

    @Override
    public String getApiUrl() {
        return API_URL;
    }

    @Override
    public String getModel() {
        return model;
    }

    @Override
    public String getApiKey() {
        return apiKey;
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return "deepseek";
    }

    @Override
    public int maxContextWindow() {
        return 1_000_000;
    }

    @Override
    public boolean supportsPromptCaching() {
        return true;
    }

    @Override
    public String promptCacheMode() {
        return "automatic-prefix-cache";
    }
}
