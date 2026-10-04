package com.paicode.llm.service.model.impl;

import com.paicode.llm.service.model.AbstractOpenaiCompatibleClient;

/**
 * @Author beaker
 * @Date 2026/10/4 21:38
 * @Description Kimi 客户端
 */
public class KimiClient extends AbstractOpenaiCompatibleClient {

    private static final String DEFAULT_BASE_URL = "https://api.moonshot.ai/v1";
    private static final String DEFAULT_MODEL = "kimi-k2.6";
    private final String apiKey;
    private final String model;
    private final String apiUrl;


    public KimiClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL, DEFAULT_BASE_URL);
    }

    public KimiClient(String apiKey, String model, String baseUrl) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
        this.apiUrl = toChatCompletionsUrl(baseUrl);
    }

    @Override
    public String getApiUrl() {
        return apiUrl;
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
    public boolean shouldSendReasoningContentInRequestHistory() {
        return true;
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return "kimi";
    }

    @Override
    public int maxContextWindow() {
        return 256_000;
    }

    @Override
    public boolean supportsPromptCaching() {
        return true;
    }

    @Override
    public String promptCacheMode() {
        return "moonshot-context-cache";
    }

    private static String toChatCompletionsUrl(String baseUrl) {
        String normalized = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : DEFAULT_BASE_URL;
        String withoutTrailingSlash = normalized.replaceAll("/+$", "");
        if (withoutTrailingSlash.endsWith("/chat/completions")) {
            return withoutTrailingSlash;
        }
        return withoutTrailingSlash + "/chat/completions";
    }
}
