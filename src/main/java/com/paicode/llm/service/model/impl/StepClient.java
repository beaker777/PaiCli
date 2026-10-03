package com.paicode.llm.service.model.impl;

import com.paicode.llm.service.model.AbstractOpenaiCompatibleClient;

/**
 * @Author beaker
 * @Date 2026/10/3 23:40
 * @Description StepFun 客户端
 */
public class StepClient extends AbstractOpenaiCompatibleClient {

    private static final String DEFAULT_BASE_URL = "https://api.stepfun.com/v1";
    private static final String DEFAULT_MODEL = "step-3.5-flash";
    private final String apiKey;
    private final String model;
    private final String apiUrl;

    public StepClient(String apiKey, String model, String baseUrl) {
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
        return "step";
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
        return "step-prefix-cache";
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
