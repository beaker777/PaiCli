package com.paicode.web.service.provider.factory;

import com.paicode.web.service.provider.SearchProvider;
import com.paicode.web.service.provider.impl.SearxngSearchProvider;
import com.paicode.web.service.provider.impl.SerpApiSearchProvider;
import com.paicode.web.service.provider.impl.ZhipuSearchProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

/**
 * @Author beaker
 * @Date 2026/9/24 18:14
 * @Description 根据配置选择 SearchProvider
 */
public class SearchProviderFactory {

    private static final Logger log = LoggerFactory.getLogger(SearchProviderFactory.class);

    public static SearchProvider create() {
        String provider = readEnv("SEARCH_PROVIDER");
        String glmKey = readEnv("GLM_API_KEY");
        String zhipuEngine = readEnv("ZHIPU_SEARCH_ENGINE");
        String serpKey = readEnv("SERPAPI_KEY");
        String searxngUrl = readEnv("SEARXNG_URL");

        String chosen = pickProvider(provider, glmKey, serpKey, searxngUrl);
        log.info("SearchProvider chosen: {}", chosen);

        return switch (chosen) {
            case "searxng" -> new SearxngSearchProvider(searxngUrl);
            case "serpapi" -> new SerpApiSearchProvider(serpKey);
            default -> new ZhipuSearchProvider(glmKey, zhipuEngine);
        };
    }

    static String pickProvider(String explicit, String glmKey, String serpKey, String searxngUrl) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim().toLowerCase(Locale.ROOT);
        }

        if (glmKey != null && !glmKey.isBlank()) {
            return "zhipu";
        }

        if (serpKey != null && !serpKey.isBlank()) {
            return "serpapi";
        }

        if (searxngUrl != null && !searxngUrl.isBlank()) {
            return "searxng";
        }

        // 此时默认选择智谱, 但 apiKey 为空, 由系统提示用户
        return "zhipu";
    }


    private static String readEnv(String key) {
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }

        String fromProp = System.getProperty(key);
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp.trim();
        }

        return readFromDotEnv(key);
    }

    private static String readFromDotEnv(String key) {
        File[] envFiles = {new File(".env"), new File(System.getProperty("user.home"), ".env")};
        for (File envFile : envFiles) {
            if (!envFile.exists()) continue;

            try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith(key + "=")) {
                        return line.substring((key + "=").length()).trim();
                    }
                }
            } catch (Exception ignored) {
            }
        }

        return null;
    }
}
