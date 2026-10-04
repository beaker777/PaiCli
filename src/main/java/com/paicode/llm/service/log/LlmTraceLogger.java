package com.paicode.llm.service.log;

import com.paicode.llm.service.model.LlmClient;
import org.slf4j.Logger;

/**
 * @Author beaker
 * @Date 2026/10/4 21:47
 * @Description
 */
public class LlmTraceLogger {

    public static void logReasoning(Logger log, String scope, LlmClient llmClient, String reasoningContent) {
        if (log == null || reasoningContent == null || reasoningContent.isBlank()) {
            return;
        }

        String normalized = reasoningContent.replace("\r\n", "\n").replace('\r', '\n').trim();
        log.info("LLM reasoning [{}] provider={} model={} chars={}\n{}",
                scope == null || scope.isBlank() ? "unknown" : scope,
                llmClient == null ? "unknown" : llmClient.getProviderName(),
                llmClient == null ? "unknown" : llmClient.getModelName(),
                normalized.length(),
                normalized);
    }
}
