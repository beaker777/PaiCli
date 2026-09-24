package com.paicode.tool.service.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicode.tool.entity.Param;
import com.paicode.tool.entity.ToolDefinition;
import com.paicode.tool.entity.ToolSchema;
import com.paicode.web.entity.Extracted;
import com.paicode.web.entity.FetchResult;
import com.paicode.web.entity.RawResponse;
import com.paicode.web.entity.SearchResult;
import com.paicode.web.service.fetch.HtmlExtractor;
import com.paicode.web.service.fetch.NetworkPolicy;
import com.paicode.web.service.fetch.WebFetcher;
import com.paicode.web.service.provider.SearchProvider;
import com.paicode.web.service.provider.factory.SearchProviderFactory;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @Author beaker
 * @Date 2026/9/23 23:49
 * @Description Web 搜索工具 - 通过 SerpAPI 进行联网搜索
 */
public class WebSearchTools {

    private static final int DEFAULT_FETCH_MAX_CHARS = 8_000;

    private SearchProvider searchProvider;
    private WebFetcher webFetcher;
    private HtmlExtractor htmlExtractor;
    private NetworkPolicy networkPolicy;

    public List<ToolDefinition> create() {
        return List.of(
                createWebSearchTool(),
                createWebFetchTool()
        );
    }

    private ToolDefinition createWebSearchTool() {
        return new ToolDefinition("web_search",
                "在互联网上搜索, 获取实时信息 (最新版本, 官方文档, 技术资讯等)",
                ToolSchema.createParameters(
                        new Param("query", "string", "搜索关键词 (例如 Java 21 新特性, Spring AI, 等)", true),
                        new Param("top_k", "integer", "返回结果数量 (默认为 5)", false)
                ),
                args -> webSearch(args.get("query"), parseInt(args.get("top_k"), 5)));
    }

    private ToolDefinition createWebFetchTool() {
        return new ToolDefinition("web_fetch",
                "抓取指定 URL, 提取正文解析为 Markdown" +
                        "适用静态 / SSR 页面, JS 渲染或反爬墙或返回空文本, 暂不重试",
                ToolSchema.createParameters(
                        new Param("url", "string", "完整 URL, 需使用 http 或 https 协议", true),
                        new Param("max_chars", "integer", "返回 Markdown 的最大字符数 (默认 8000)", false)
                ),
                args -> webFetch(args.get("url"), parseInt(args.get("max_chars"), DEFAULT_FETCH_MAX_CHARS)));
    }

    private String webSearch(String query, int topK) {
        if (query == null || query.isBlank()) {
            return "搜索关键词不能为空";
        }

        SearchProvider provider = searchProvider;
        if (!provider.isReady()) {
            return "⚠️ " + provider.unavailableHint();
        }

        try {
            List<SearchResult> results = provider.search(query, topK);
            return formatSearchResults(provider.name(), query, results);
        } catch (Exception e) {
            return "搜索失败 (" + provider.name() + "): " + e.getMessage();
        }
    }

    private String webFetch(String url, int maxChars) {
        if (url == null || url.isBlank()) {
            return "URL 不能为空";
        }

        NetworkPolicy policy = networkPolicy();
        String denyReason = policy.checkUrl(url);
        if (denyReason != null) {
            return "❌ 网络访问被拒绝: " + denyReason;
        }
        String rateReason = policy.acquire();
        if (rateReason != null) {
            return "❌ " + rateReason;
        }

        try {
            RawResponse raw = webFetcher().fetch(url.trim());

            Extracted extracted = htmlExtractor().extract(raw.body(), raw.url());
            String markdown = extracted.markdown();

            int originalLength = markdown.length();
            boolean truncated = false;
            if (maxChars > 0 && markdown.length() > maxChars) {
                markdown = markdown.substring(0, maxChars);
                truncated = true;
            }

            FetchResult result = FetchResult.ok(raw.url(), extracted.title(), markdown, originalLength, truncated);
            return formatFetchResult(result);
        } catch (Exception e) {
            return "抓取失败: " + e.getMessage();
        }
    }

    private String formatSearchResults(String providerName, String query, List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "🔍 [" + providerName + "] " + query + "\n\n未找到相关结果。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🔍 [").append(providerName).append("] ").append(query).append("\n\n");
        for (SearchResult r : results) {
            sb.append(r.position()).append(". ").append(r.title()).append("\n");

            if (!r.snippet().isBlank()) {
                String snippet = r.snippet();
                if (snippet.length() > 200) {
                    snippet = snippet.substring(0, 200) + "...";
                }
                sb.append("   ").append(snippet).append("\n");
            }

            if (!r.url().isBlank()) {
                sb.append("   🔗 ").append(r.url());
                if (!r.source().isBlank()) {
                    sb.append("  (").append(r.source()).append(")");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        return sb.toString().trim();
    }

    private String formatFetchResult(FetchResult result) {
        StringBuilder sb = new StringBuilder();

        sb.append("🌐 抓取: ").append(result.url()).append("\n");

        if (!result.title().isBlank()) {
            sb.append("📄 标题: ").append(result.title()).append("\n");
        }

        if (result.bodyEmpty()) {
            sb.append("\n⚠️ ").append(result.hint()).append("\n");
            return sb.toString();
        }

        sb.append("📏 正文 ").append(result.contentLength()).append(" 字符");
        if (result.truncated()) {
            sb.append("（已截断）");
        }

        sb.append("\n\n---\n\n");
        sb.append(result.markdown());

        return sb.toString();
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private synchronized SearchProvider searchProvider() {
        if (searchProvider == null) {
            searchProvider = SearchProviderFactory.create();
        }
        return searchProvider;
    }

    private synchronized NetworkPolicy networkPolicy() {
        if (networkPolicy == null) {
            networkPolicy = new NetworkPolicy();
        }
        return networkPolicy;
    }

    private synchronized WebFetcher webFetcher() {
        if (webFetcher == null) {
            webFetcher = new WebFetcher();
        }
        return webFetcher;
    }

    private synchronized HtmlExtractor htmlExtractor() {
        if (htmlExtractor == null) {
            htmlExtractor = new HtmlExtractor();
        }
        return htmlExtractor;
    }
}
