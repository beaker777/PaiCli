package com.paicode.llm.service.model.impl;

import com.paicode.llm.service.model.AbstractOpenaiCompatibleClient;

/**
 * @Author beaker
 * @Date 2026/10/7 10:13
 * @Description FreeLLm 客户端
 */
public class FreeLlmApiClient extends AbstractOpenaiCompatibleClient {

    private static final String DEFAULT_BASE_URL = "http://localhost:5173/v1";
    private static final String DEFAULT_MODEL = "auto";

    private final String apiKey;
    private final String model;
    private final String apiUrl;

    public FreeLlmApiClient(String apiKey, String model, String baseUrl) {
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
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return "freellmapi";
    }

    @Override
    public int maxContextWindow() {
        return 128_000;
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
