package com.paicode.memory.service.query;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.paicode.utils.JiebaSegmenterFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * @Author beaker
 * @Date 2026/9/14 20:07
 * @Description 基于 jieba 的检索分词器
 */
public class MemoryQueryTokenizer {

    private final static JiebaSegmenter SEGMENTER = JiebaSegmenterFactory.createSilently();

    /**
     * 对文本进行分词, 返回可用于检索的 token 集合
     */
    public static Set<String> tokenize(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return tokens;
        }

        List<String> words = SEGMENTER.sentenceProcess(query.toLowerCase(Locale.ROOT).trim());
        for (String word : words) {
            String trimmed = word.trim();

            // 过滤单字符和标点符号
            if (trimmed.length() >= 2 && !isPunctuation(trimmed)) {
                tokens.add(trimmed);
            }
        }

        return tokens;
    }

    /**
     * 检查文本中是否包含任意一个 query token
     */
    public static boolean matches(String text, Set<String> queryTokens) {
        if (text == null || text.isBlank() || queryTokens.isEmpty()) {
            return false;
        }

        String normalizedText = text.toLowerCase(Locale.ROOT);
        for (String token : queryTokens) {
            if (normalizedText.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPunctuation(String s) {
        // 先过滤掉所有非字母和数字, 在过滤所有不在汉字集的符号
        return s.codePoints().allMatch(cp ->
                !Character.isLetterOrDigit(cp) && Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN);
    }
}
