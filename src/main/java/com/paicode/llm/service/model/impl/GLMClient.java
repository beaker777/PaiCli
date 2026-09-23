package com.paicode.llm.service.model.impl;

import com.paicode.llm.service.model.AbstractOpenaiCompatibleClient;

/**
 * @Author beaker
 * @Date 2026/9/23 22:01
 * @Description GLM 客户端
 */
public class GLMClient extends AbstractOpenaiCompatibleClient {

    private static final String API_URL = "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions";
    private static final String DEFAULT_MODEL = "glm-5.1";
    private final String apiKey;
    private final String model;

    public GLMClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public GLMClient(String apiKey, String model) {
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
        return "glm";
    }
}
