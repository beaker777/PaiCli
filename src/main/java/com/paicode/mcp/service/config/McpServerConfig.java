package com.paicode.mcp.service.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Author beaker
 * @Date 2026/9/25 17:21
 * @Description MCP 服务端参数
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class McpServerConfig {

    private String command;
    private List<String> args = new ArrayList<>();
    private Map<String, String> env = new LinkedHashMap<>();
    private String url;
    private Map<String, String> headers = new LinkedHashMap<>();
    private boolean disabled;

    public void setArgs(List<String> args) {
        this.args = args == null ? new ArrayList<>() : args;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env == null ? new LinkedHashMap<>() : env;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers == null ? new LinkedHashMap<>() : headers;
    }

    public boolean isStdio() {
        return command != null && !command.isBlank();
    }

    public boolean isHttp() {
        return url != null && !url.isBlank();
    }

    public String transportName() {
        return isHttp() ? "http" : "stdio";
    }
}
