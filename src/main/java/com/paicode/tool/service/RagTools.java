package com.paicode.tool.service;

import com.paicode.rag.CodeRetriever;
import com.paicode.rag.DTO.IndexStats;
import com.paicode.rag.DTO.SearchResult;
import com.paicode.rag.utils.SearchResultFormatter;
import com.paicode.tool.DTO.Param;
import com.paicode.tool.ToolDefinition;
import com.paicode.tool.ToolSchema;

import java.util.List;

/**
 * @Author beaker
 * @Date 2026/9/17 22:46
 * @Description rag 相关工具
 */
public class RagTools {

    public static List<ToolDefinition> create(String projectPath) {
        return List.of(createSearchCodeTool(projectPath));
    }

    private static ToolDefinition createSearchCodeTool(String projectPath) {
        return new ToolDefinition(
                "search_code",
                "语义检索代码库, 根据自然语言查找相关代码块",
                ToolSchema.createParameters(
                        new Param("query", "string", "自然语言描述查询内容, 如'用户登录的实现'", true),
                        new Param("top_k", "integer", "返回结果数量 (默认为 5)", false)
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

                    try (CodeRetriever retriever = new CodeRetriever(projectPath)) {
                        IndexStats stats = retriever.getStats();
                        if (stats.chunkCount() == 0) {
                            return "代码库尚未索引, 请先使用 /index 命令为项目建立索引";
                        }

                        List<SearchResult> results = retriever.hybridSearch(query, topK);
                        if (results.isEmpty()) {
                            return "未找到相关代码";
                        }

                        return SearchResultFormatter.formatForTool(query, results);
                    } catch (Exception e) {
                        return "代码检索失败: " + e.getMessage();
                    }
                }
        );
    }
}
