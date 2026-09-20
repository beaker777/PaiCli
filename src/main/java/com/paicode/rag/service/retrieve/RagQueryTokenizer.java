package com.paicode.rag.service.retrieve;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.paicode.utils.JiebaSegmenterFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Author beaker
 * @Date 2026/9/16 23:29
 * @Description RAG 查询分词器
 */
public class RagQueryTokenizer {

    private static final JiebaSegmenter SEGMENTER = JiebaSegmenterFactory.createSilently();
    private static final Pattern ASCII_TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9_.$-]{1,}");

    public static Set<String> tokenize(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return tokens;
        }

        String normalized = query.trim();
        List<String> words = SEGMENTER.sentenceProcess(normalized);
        for (String word : words) {
            String token = word.trim();
            if (isUsefulToken(token)) {
                tokens.add(token);
            }
        }

        Matcher matcher = ASCII_TOKEN.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group();
            if (isUsefulToken(token)) {
                tokens.add(token);
            }
        }

        return tokens;
    }

    private static boolean isUsefulToken(String token) {
        if (token == null) {
            return false;
        }

        // 排除长度为 1 的 token
        String normalized = token.trim();
        if (normalized.length() < 2) {
            return false;
        }

        // 排除特定的 token
        String lower = normalized.toLowerCase(Locale.ROOT);
        boolean stopWord = switch (lower) {
            case "怎么", "如何", "什么", "哪些", "一下", "实现", "的是", "一个", "可以", "这里", "那里" -> true;
            default -> false;
        };

        return !stopWord && isMeaningful(normalized);
    }

    private static boolean isMeaningful(String token) {
        boolean hasHan = token.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
        boolean hasAsciiWord = token.codePoints().anyMatch(Character::isLetterOrDigit);

        // 是字母, 数字, 汉字之一
        return hasHan || hasAsciiWord;
    }
}
