package com.paicode.mcp.service.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @Author beaker
 * @Date 2026/9/25 17:48
 * @Description
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class McpConfigFile {

    private Map<String, McpServerConfig> mcpServers = new LinkedHashMap<>();

    public void setMcpServers(Map<String, McpServerConfig> mcpServers) {
        this.mcpServers = mcpServers == null ? new LinkedHashMap<>() : mcpServers;
    }
}
