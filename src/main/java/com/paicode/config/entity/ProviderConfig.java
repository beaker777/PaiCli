package com.paicode.config.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * @Author beaker
 * @Date 2026/9/23 21:46
 * @Description 模型提供方参数
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProviderConfig {

    private String apiKey;
    private String baseUrl;
    private String model;

    public ProviderConfig() {}

    public ProviderConfig(String apiKey, String baseUrl, String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }
}
