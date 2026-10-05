package com.paicode.renderer.service.inline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicode.llm.entity.ToolCall;
import com.paicode.utils.AnsiStyle;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Author beaker
 * @Date 2026/10/2 13:36
 * @Description 将工具调用渲染成 FoldableBlock, 每次调用产生一个 block
 */
public class ToolCallRenderer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PrintStream out;
    private final BlockRegistry registry;

    public ToolCallRenderer(PrintStream out, BlockRegistry registry) {
        this.out = out;
        this.registry = registry;
    }

    public void render(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }

        Map<String, List<ToolCall>> grouped = group(toolCalls);
        String header = collapsedHeader(grouped);
        List<String> expanded = expandedLines(grouped);

        FoldableBlock block = new FoldableBlock(out, header, expanded);
        registry.register(block);
        block.renderInitial();
    }

    public static Map<String, List<ToolCall>> group(List<ToolCall> toolCalls) {
        Map<String, List<ToolCall>> grouped = new LinkedHashMap<>();
        for (ToolCall toolCall : toolCalls) {
            grouped.computeIfAbsent(toolCall.function().name(), k -> new ArrayList<>()).add(toolCall);
        }
        return grouped;
    }

    public static String collapsedHeader(Map<String, List<ToolCall>> grouped) {
        // 只有一种 toolCall
        if (grouped.size() == 1) {
            var entry = grouped.entrySet().iterator().next();
            String label = toolCollapsedLabel(entry.getKey(), entry.getValue());

            return AnsiStyle.subtle("⏵ " + stripPrefixIcon(label) + " (ctrl+o to expand)");
        }

        // 多种 toolCall
        int totalCalls = grouped.values().stream().mapToInt(List::size).sum();
        return AnsiStyle.subtle("⏵ " + grouped.size() + " 组工具调用 / " + totalCalls + " 次 (ctrl+o to expand)");
    }

    public static List<String> expandedLines(Map<String, List<ToolCall>> grouped) {
        List<String> lines = new ArrayList<>();
        for (var group : grouped.entrySet()) {
            String toolName = group.getKey();
            List<ToolCall> calls = group.getValue();
            lines.add(AnsiStyle.subtle("  " + toolLabel(toolName, calls.size())));

            for (ToolCall tc : calls) {
                String detail = extractKeyParam(toolName, tc.function().arguments());
                if (!detail.isEmpty()) {
                    lines.add(AnsiStyle.subtle("    └ " + detail));
                }
            }
        }
        return lines;
    }

    private static String toolLabel(String toolName, int count) {
        return switch (toolName) {
            case "read_file" -> "📖 读取 " + count + " 个文件";
            case "write_file" -> "✏️ 写入 " + count + " 个文件";
            case "list_dir" -> "📂 列出 " + count + " 个目录";
            case "execute_command" -> "⚡ 执行 " + count + " 条命令";
            case "create_project" -> "🏗️ 创建 " + count + " 个项目";
            case "search_code" -> "🔍 搜索代码 " + count + " 次";
            case "web_search" -> "🌐 联网搜索 " + count + " 次";
            case "web_fetch" -> "📰 抓取 " + count + " 个网页";
            case "save_memory" -> "💾 保存长期记忆 " + count + " 条";
            default -> toolName.startsWith("mcp__") ? formatMcpLabel(toolName, count) : "🔧 " + toolName + " × " + count;
        };
    }

    private static String toolCollapsedLabel(String toolName, List<ToolCall> calls) {
        int count = calls == null ? 0 : calls.size();
        String label = toolLabel(toolName, count);
        if (count != 1 || calls.isEmpty()) {
            return label;
        }

        String detail = extractKeyParam(toolName, calls.get(0).function().arguments());
        if (detail.isBlank()) {
            return label;
        }
        return switch (toolName) {
            case "web_search" -> "🌐 WebSearch(\"" + detail + "\")";
            case "web_fetch" -> "📰 WebFetch(" + compactUrl(detail) + ")";
            case "search_code" -> "🔍 SearchCode(\"" + detail + "\")";
            case "read_file" -> "📖 ReadFile(" + detail + ")";
            case "list_dir" -> "📂 ListDir(" + detail + ")";
            case "execute_command" -> "⚡ Shell(" + detail + ")";
            default -> label + " · " + detail;
        };
    }

    private static String formatMcpLabel(String toolName, int count) {
        String[] parts = toolName.split("__", 3);
        String display = parts.length == 3 ? parts[1] + "." + parts[2] : toolName;
        return count == 1
                ? "🔌 调用 MCP 工具 " + display
                : "🔌 调用 MCP 工具 " + display + " × " + count;
    }

    /** 移除 emoji 前缀（折叠态视觉更紧凑），如 "📖 读取 3 个文件" → "读取 3 个文件"。 */
    private static String stripPrefixIcon(String label) {
        if (label == null || label.isEmpty()) {
            return "";
        }
        int firstSpace = label.indexOf(' ');
        if (firstSpace < 0) {
            return label;
        }

        // 仅当第一个 token 是 emoji（高 Unicode）时才剥离
        int cp = label.codePointAt(0);
        if (cp >= 0x2600 && cp <= 0x1FAFF) {
            return label.substring(firstSpace + 1);
        }
        return label;
    }

    private static String extractKeyParam(String toolName, String argsJson) {
        try {
            JsonNode node = JSON.readTree(argsJson);
            String key = switch (toolName) {
                case "read_file", "write_file", "list_dir" -> "path";
                case "execute_command" -> "command";
                case "create_project" -> "name";
                case "search_code", "web_search" -> "query";
                case "web_fetch" -> "url";
                case "save_memory" -> "fact";
                default -> null;
            };

            if (key == null) {
                return argsJson != null && argsJson.length() > 80
                        ? argsJson.substring(0, 77) + "..." : argsJson == null ? "" : argsJson;
            }
            String value = node.path(key).asText("");
            if (value.length() > 80) {
                value = value.substring(0, 77) + "...";
            }
            return value;
        } catch (Exception e) {
            if (argsJson == null) {
                return "";
            }
            return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
        }
    }

    private static String compactUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String value = url.trim()
                .replaceFirst("^https?://", "")
                .replaceFirst("/+$", "");
        return value.length() > 80 ? value.substring(0, 77) + "..." : value;
    }
}
