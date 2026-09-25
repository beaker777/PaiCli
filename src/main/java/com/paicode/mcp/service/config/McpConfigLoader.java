package com.paicode.mcp.service.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Author beaker
 * @Date 2026/9/25 17:31
 * @Description MCP 参数加载器
 */
public class McpConfigLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*|PROJECT_DIR|HOME)}");

    private final Path userConfig;
    private final Path projectConfig;
    private final Path projectDir;

    public McpConfigLoader(Path projectDir) {
        this(
                Path.of(System.getProperty("user.home"), ".paicode", "mcp.json"),
                projectDir.resolve(".paicode").resolve("mcp.json"),
                projectDir
        );
    }

    public McpConfigLoader(Path userConfig, Path projectConfig, Path projectDir) {
        this.userConfig = userConfig;
        this.projectConfig = projectConfig;
        this.projectDir = projectDir.toAbsolutePath().normalize();
    }

    public Map<String, McpServerConfig> load() throws IOException {
        Map<String, McpServerConfig> merged = new LinkedHashMap<>();
        if (Files.exists(userConfig)) {
            merged.putAll(read(userConfig));
        }

        if (Files.exists(projectConfig)) {
            merged.putAll(read(projectConfig));
        }
        return merged;
    }

    public void prepare(McpServerConfig config) {
        expand(config);
        validate(config);
    }

    /**
     * 在文件中读取 Mcp Server Config
     */
    private Map<String, McpServerConfig> read(Path file) throws IOException {
        McpConfigFile configFile = MAPPER.readValue(file.toFile(), McpConfigFile.class);

        return configFile.getMcpServers();
    }

    /**
     *
     */
    private void expand(McpServerConfig config) {
        if (config.getCommand() != null) {
            config.setCommand(expandString(config.getCommand()));
        }

        List<String> expandedArgs = new ArrayList<>();
        for (String arg : config.getArgs()) {
            expandedArgs.add(expandString(arg));
        }
        config.setArgs(expandedArgs);

        config.setEnv(expandMap(config.getEnv()));

        if (config.getUrl() != null) {
            config.setUrl(expandString(config.getUrl()));
        }

        config.setHeaders(expandMap(config.getHeaders()));
    }

    private Map<String, String> expandMap(Map<String, String> raw) {
        Map<String, String> expanded = new LinkedHashMap<>();
        if (raw == null) {
            return expanded;
        }
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            expanded.put(entry.getKey(), expandString(entry.getValue()));
        }
        return expanded;
    }

    private String expandString(String raw) {
        if (raw == null) {
            return null;
        }

        Matcher matcher = VAR_PATTERN.matcher(raw);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = switch (name) {
                case "PROJECT_DIR" -> projectDir.toString();
                case "HOME" -> System.getProperty("user.home");
                default -> System.getenv(name);
            };
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("MCP 配置引用了未设置的环境变量: " + name);
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 如果同时配置了 URL 和 Command 则无效
     */
    private void validate(McpServerConfig config) {
        if (config.isStdio() == config.isHttp()) {
            throw new IllegalArgumentException("MCP server 必须且只能配置 command 或 url");
        }
    }
}
