package com.paicode.mcp.service.mention;

import com.paicode.mcp.entity.MentionToken;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Author beaker
 * @Date 2026/9/26 16:47
 * @Description 解析 @ 的内容
 */
public class AtMentionParser {

    private static final Pattern RESOURCE_PATTERN = Pattern.compile("@([a-zA-Z][\\w-]*):([a-z]+)://([^\\s@]+)");

    public static List<MentionToken> parse(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }

        List<MentionToken> tokens = new ArrayList<>();
        Matcher matcher = RESOURCE_PATTERN.matcher(input);
        while (matcher.find()) {
            if (isInsideQuotes(input, matcher.start())) {
                continue;
            }

            String serverName = matcher.group(1);
            String uri = matcher.group(2) + "://" + matcher.group(3);
            tokens.add(new MentionToken(serverName, uri, matcher.start(), matcher.end(), matcher.group()));
        }
        return tokens;
    }

    /**
     * 判断内容是否在引号中
     */
    private static boolean isInsideQuotes(String input, int offset) {
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escaped = false;

        for (int i = 0; i < offset; i++) {
            char c = input.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            }
        }
        return inSingle || inDouble;
    }
}
