package com.paicode.policy.exception;

/**
 * @Author beaker
 * @Date 2026/9/25 00:57
 * @Description 安全策略拦截时抛出
 *
 * 策略拦截不应静默通过, 应当让 LLM 不再尝试相同的请求
 */
public class PolicyException extends RuntimeException {
    public PolicyException(String message) {
        super(message);
    }
}
