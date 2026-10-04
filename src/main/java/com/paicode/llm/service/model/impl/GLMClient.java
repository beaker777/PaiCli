package com.paicode.llm.service.model.impl;

import com.paicode.llm.entity.ContentPart;
import com.paicode.llm.service.model.AbstractOpenaiCompatibleClient;

/**
 * @Author beaker
 * @Date 2026/9/23 22:01
 * @Description GLM 客户端
 */
public class GLMClient extends AbstractOpenaiCompatibleClient {

    private static final String CODING_API_URL = "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions";
    private static final String MULTIMODAL_API_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
    private static final String DEFAULT_MODEL = "glm-5.1";
    private final String apiKey;
    private final String model;
    private final String apiUrl;

    public GLMClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public GLMClient(String apiKey, String model) {
        this(apiKey, model, null);
    }

    public GLMClient(String apiKey, String model, String apiUrl) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
        this.apiUrl = apiUrl != null && !apiUrl.isBlank() ? apiUrl : selectApiUrl(this.model);
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
        return "glm";
    }

    @Override
    public int maxContextWindow() {
        return 200_000;
    }

    @Override
    public boolean supportsPromptCaching() {
        return true;
    }

    @Override
    public String promptCacheMode() {
        return "glm-prompt-cache";
    }

    @Override
    public String toImageUrl(ContentPart part) {
        if (isGlm5v() && "image_base64".equals(part.type())) {
            return part.imageBase64();
        }
        return super.toImageUrl(part);
    }

    private static String selectApiUrl(String model) {
        String normalized = model == null ? "" : model.trim().toLowerCase();
        if (normalized.startsWith("glm-5v")) {
            return MULTIMODAL_API_URL;
        }
        return CODING_API_URL;
    }

    private boolean isGlm5v() {
        return model != null && model.trim().toLowerCase().startsWith("glm-5v");
    }
}
