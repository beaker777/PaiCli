package com.paicode.llm.service.model.factory;

import com.paicode.config.PaiCodeConfig;
import com.paicode.llm.service.model.impl.DeepSeekClient;
import com.paicode.llm.service.model.LlmClient;
import com.paicode.llm.service.model.impl.GLMClient;
import com.paicode.llm.service.model.impl.StepClient;

/**
 * @Author beaker
 * @Date 2026/9/23 21:43
 * @Description LLM Client 创建工厂
 */
public class LlmClientFactory {

    public static LlmClient create(String provider, PaiCodeConfig config) {
        if (provider == null) return null;

        String normalized = normalizeProvider(provider);
        String apiKey = config.getApiKey(normalized);
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        String model = config.getModel(normalized);
        String baseUrl = config.getBaseUrl(normalized);

        return switch (normalized) {
            case "glm" -> new GLMClient(apiKey, model);
            case "deepseek" -> new DeepSeekClient(apiKey, model);
            case "step" -> new StepClient(apiKey, model, baseUrl);
            default -> null;
        };
    }

    public static LlmClient createFromConfig(PaiCodeConfig config) {
        // 先使用默认模型
        LlmClient client = create(config.getDefaultProvider(), config);
        if (client != null) {
            return client;
        }

        // 优先级逐个降低
        for (String provider : new String[]{"glm", "deepseek", "step"}) {
            client = create(provider, config);
            if (client != null) {
                return client;
            }
        }

        return null;
    }

    private static String normalizeProvider(String provider) {
        String normalized = provider.trim().toLowerCase();
        return switch (normalized) {
            case "stepfun", "step-fun" -> "step";
            default -> normalized;
        };
    }
}
