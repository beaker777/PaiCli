package com.paicode.tool.service.register;

import com.paicode.tool.entity.Param;
import com.paicode.tool.entity.ToolDefinition;
import com.paicode.tool.entity.ToolSchema;
import com.paicode.tool.service.tools.WebSearchTools;

import java.io.BufferedReader;
import java.io.File;
import java.util.List;

/**
 * @Author beaker
 * @Date 2026/9/24 16:08
 * @Description 网络搜索工具注册
 */
public class WebSearchToolsRegistry {

    private WebSearchTools webSearchTools;

    public List<ToolDefinition> create() {
        return List.of(createWebSearchTools());
    }

    private ToolDefinition createWebSearchTools() {
        return new ToolDefinition("web_search",
                "在互联网上搜索, 获取实时信息 (最新版本, 官方文档, 技术资讯等)",
                ToolSchema.createParameters(
                        new Param("query", "string", "搜索关键词 (例如 Java 21 新特性, Spring AI, 等)", true),
                        new Param("top_k", "intrger", "返回结果数量 (默认为 5)", false)
                ),
                args -> {
                    String query = args.get("query");
                    int topK = 5;
                    try {
                        if (args.containsKey("top_k")) {
                            topK = Integer.parseInt(args.get("top_k"));
                        }
                    } catch (NumberFormatException ignored) {

                    }

                    // 懒初始化
                    if (this.webSearchTools == null) {
                        String apiKey = loadWebSearchApiKey();
                        webSearchTools = new WebSearchTools(apiKey);
                    }

                    return webSearchTools.search(query, topK);
                });
    }

    /**
     * 从环境变量或 .env 文件读取 SerpAPI Key
     */
    private String loadWebSearchApiKey() {
        // 优先系统环境变量
        String apiKey = System.getenv("SERPAPI_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey.trim();
        }

        // 降级读 .env 文件
        String dotEnvValue = readFromDotEnv("SERPAPI_KEY");
        if (dotEnvValue != null && !dotEnvValue.isBlank()) {
            return dotEnvValue.trim();
        }

        return null;
    }

    /**
     * 从 .env 文件读取指定 key
     */
    private static String readFromDotEnv(String key) {
        File[] envFiles = { new File(".env"), new File(System.getProperty("user.home"), ".env") };
        for (File envFile : envFiles) {
            if (!envFile.exists()) continue;
            try (BufferedReader reader = new BufferedReader(new java.io.FileReader(envFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith(key + "=")) {
                        return line.substring((key + "=").length()).trim();
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }
}
