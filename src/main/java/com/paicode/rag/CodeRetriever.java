package com.paicode.rag;

import com.paicode.rag.DTO.CodeRelation;
import com.paicode.rag.DTO.IndexStats;
import com.paicode.rag.DTO.SearchResult;
import com.paicode.rag.utils.RagQueryTokenizer;

import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.*;

/**
 * @Author beaker
 * @Date 2026/9/16 21:57
 * @Description 代码检索器: 语义检索 + 图谱检索
 */
public class CodeRetriever implements AutoCloseable {

    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;

    public CodeRetriever(String projectPath) throws SQLException {
        this.embeddingClient = new EmbeddingClient();
        this.vectorStore = new VectorStore(Paths.get(projectPath).toAbsolutePath().normalize().toString());
    }

    public CodeRetriever(String projectPath, EmbeddingClient embeddingClient) throws SQLException {
        this.embeddingClient = embeddingClient;
        this.vectorStore = new VectorStore(Paths.get(projectPath).toAbsolutePath().normalize().toString());
    }

    /**
     * 语义检索: 使用自然语言检索
     */
    public List<SearchResult> semanticSearch(String query, int topK) throws Exception {
        // 将自然语言向量化
        float[] queryEmbedding = embeddingClient.embed(query);
        return vectorStore.search(queryEmbedding, topK);
    }

    /**
     * 关键词检索: 按类名 / 方法名 / 内容匹配
     */
    public List<SearchResult> keywordSearch(String keyword) throws SQLException {
        return vectorStore.searchByKeyword(keyword);
    }

    /**
     * 混合检索: 同时使用语义检索和关键词检索, 合并后去重
     */
    public List<SearchResult> hybridSearch(String query, int topK) throws Exception {
        Map<String, SearchResult> merged = new LinkedHashMap<>();
        Set<String> dualMatchBonus = new HashSet<>();

        // 语义检索
        int semanticLimit = Math.max(topK * 2, 10);
        for (SearchResult result : semanticSearch(query, semanticLimit)) {
            mergeResult(merged, result, dualMatchBonus);
        }

        // 关键词检索
        Set<String> keywords = RagQueryTokenizer.tokenize(query);
        for (String keyword : keywords) {
            for (SearchResult result : keywordSearch(keyword)) {
                mergeResult(merged, boostKeywordMatch(result, keyword), dualMatchBonus);
            }
        }

        // 代码类型加分
        List<SearchResult> ranked = new ArrayList<>();
        for (SearchResult result : merged.values()) {
            double typeBonus = switch (result.chunkType()) {
                case "method" -> 0.15;
                case "class" -> 0.10;
                default -> 0.0;
            };

            ranked.add(typeBonus == 0.0 ? result : new SearchResult(
                    result.filePath(), result.chunkType(), result.name(), result.content(), result.similarity() + typeBonus
            ));
        }

        ranked.sort(Comparator.comparingDouble(SearchResult::similarity).reversed());
        return limitPerFile(ranked, topK, 2);
    }

    private void mergeResult(Map<String, SearchResult> merged, SearchResult candidate, Set<String> dualMatchBonus) {
        String key = candidate.filePath() + "#" + candidate.name();
        SearchResult existing = merged.get(key);

        if (existing == null) {
            merged.put(key, candidate);
        } else {
            double best = Math.max(existing.similarity(), candidate.similarity());
            // 双重命中奖励, 只给一次
            if (!dualMatchBonus.contains(key)) {
                best += 0.1;
                dualMatchBonus.add(key);
            }

            merged.put(key, new SearchResult(candidate.filePath(), candidate.chunkType(), candidate.name(), existing.content(), best));
        }
    }

    private SearchResult boostKeywordMatch(SearchResult result, String keyword) {
        String name = result.name().toLowerCase();
        String filePath = result.filePath().toLowerCase();
        String content = result.content().toLowerCase();
        String keywordLower = keyword.toLowerCase();

        // 加分控制在 0.1 ~ 0.5, 确保关键词结果 (最高 0.8) 不会超过语义结果 (最高 1.0)
        double bonus = 0.0;
        if (name.contains(keywordLower)) {
            // 类名命中是最强信号
            bonus += 0.3;
        }
        if (filePath.contains(keywordLower)) {
            bonus += 0.1;
        }
        if (content.contains(keywordLower)) {
            bonus += 0.1;
        }

        return new SearchResult(
                result.filePath(), result.chunkType(),
                result.name(), result.content(),
                result.similarity() + bonus);
    }

    /**
     * 同一文件最多保留 maxPerFile 个结果，总数不超过 topK
     */
    private List<SearchResult> limitPerFile(List<SearchResult> sorted, int topK, int maxPerFile) {
        List<SearchResult> result = new ArrayList<>();
        Map<String, Integer> fileCount = new HashMap<>();

        for (SearchResult r : sorted) {
            int count = fileCount.getOrDefault(r.filePath(), 0);

            if (count < maxPerFile) {
                result.add(r);
                fileCount.put(r.filePath(), count + 1);
                if (result.size() >= topK) {
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 图谱检索, 查询指定类的关系图谱
     */
    public List<CodeRelation> getRelationGraph(String name) throws SQLException {
        return vectorStore.getRelations(name);
    }

    /**
     * 获取当前索引统计
     */
    public IndexStats getStats() throws SQLException {
        return vectorStore.getStats();
    }

    @Override
    public void close() throws Exception {
        vectorStore.close();
    }
}
