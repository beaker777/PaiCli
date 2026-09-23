package com.paicode.llm.service.model.factory;

import com.paicode.config.PaiCodeConfig;
import com.paicode.llm.service.model.impl.DeepSeekClient;
import com.paicode.llm.service.model.LlmClient;
import com.paicode.llm.service.model.impl.GLMClient;

/**
 * @Author beaker
 * @Date 2026/9/23 21:43
 * @Description LLM Client 创建工厂
 */
public class LlmClientFactory {

    public static LlmClient create(String provider, PaiCodeConfig config) {
        if (provider == null) return null;

        String normalized = provider.toLowerCase();
        String apiKey = config.getApiKey(normalized);
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        String model = config.getModel(normalized);

        return switch (normalized) {
            case "glm" -> new GLMClient(apiKey, model);
            case "deepseek" -> new DeepSeekClient(apiKey, model);
            default -> null;
        };
    }

    public static LlmClient createFromConfig(PaiCodeConfig config) {
        LlmClient client = create(config.getDefaultProvider(), config);
        if (client != null) {
            return client;
        }

        for (String provider : new String[]{"glm", "deepseek"}) {
            client = create(provider, config);
            if (client != null) {
                return client;
            }
        }

        return null;
    }
}
