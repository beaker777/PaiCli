package com.paicode.tool.service.tools;

import com.paicode.lsp.service.LspManager;
import com.paicode.policy.exception.PolicyException;
import com.paicode.policy.service.guard.PathGuard;
import com.paicode.tool.entity.*;
import com.paicode.tool.service.register.ToolRegistry;
import com.paicode.tool.service.tools.code.impl.RipgrepCodeSearchEngine;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * @Author beaker
 * @Date 2026/9/8 22:13
 * @Description 文件工具
 */
public class FileTools {

    // write_file 单次写入字节数上限, LLM 想塞超大内容时通常是误生成 (重复粘贴 / hallucinate 大段日志)
    private static final int MAX_WRITE_FILE_BYTES = 5 * 1024 * 1024;

    private static final int MAX_READ_FILE_LINES = 2_000;
    private static final int MAX_GREP_RESULTS = 200;
    private static final int MAX_GREP_CONTEXT_LINES = 5;
    private static final int DEFAULT_GREP_MAX_CHARS = 24_000;
    private static final int MAX_GREP_MAX_CHARS = 60_000;
    private static final int DEFAULT_GREP_HEAD_LIMIT = 20;
    private static final Set<String> SEARCH_EXCLUDED_DIRS = Set.of(
            ".git", ".paicode", "target", "node_modules", "dist", "build", "coverage", ".idea", ".gradle"
    );
    private final Supplier<PathGuard> pathGuardSupplier;

    public FileTools(Supplier<PathGuard> pathGuardSupplier) {
        this.pathGuardSupplier = pathGuardSupplier;
    }

    public List<ToolDefinition> create(BiConsumer<String, String[]> observer, LspManager lspManager) {
        return List.of(
                createReadFileTool(),
                createWriteFileTool(observer, lspManager),
                createListDirFileTool(),
                createGlobFilesTool(),
                createGrepCodeTool()
        );
    }

    // read_file
    private ToolDefinition createReadFileTool() {
        return new ToolDefinition(
                "read_file",
                "读取文件内容 (仅限当前项目的根目录之内), 可用 offset/limit 按行读取, 避免把大文件整个塞入上下文",
                ToolSchema.createParameters(
                        new Param("path", "string", "文件路径", true),
                        new Param("offset", "integer", "起始行号, 1 表示第一行, 省略时读取全文内容", false),
                        new Param("limit", "integer", "最多读取行数, 省略时读取全文, 最大 2000 行", false)
                ),
                args -> {
                    Path safe = pathGuardSupplier.get().resolveSafe(args.get("path"));

                    try {
                        return readFileForTool(safe, args);
                    } catch (IOException e) {
                        return "文件读取失败: " + e.getMessage();
                    }
                }
        );
    }

    // write_file
    private ToolDefinition createWriteFileTool(BiConsumer<String, String[]> observer, LspManager lspManager) {
        return new ToolDefinition(
                "write_file",
                "写入文件内容 (仅限当前项目根目录内, 大小不超过 5MB)",
                ToolSchema.createParameters(
                        new Param("path", "string", "文件路径", true),
                        new Param("content", "string", "文件内容", true)
                ),
                args -> {
                    String path = args.get("path");
                    String content = args.get("content") == null ? "" : args.get("content");
                    int length = content.getBytes(StandardCharsets.UTF_8).length;
                    if (length > MAX_WRITE_FILE_BYTES) {
                        throw new PolicyException("写入内容 " + length + " 字节超过 "
                                + (MAX_WRITE_FILE_BYTES / 1024 / 1024) + "MB 上限");
                    }

                    Path safe = pathGuardSupplier.get().resolveSafe(path);
                    String before = null;
                    try {
                        if (Files.exists(safe) && Files.isRegularFile(safe)) {
                            before = Files.readString(safe);
                        }
                    } catch (IOException e) {
                        // 当无法读取文件内容时按 NULL 处理
                    }

                    try {
                        // 确保目录存在
                        Path parent = safe.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        Files.writeString(safe, content);

                        try {
                            observer.accept(path, new String[]{before, content});
                        } catch (Exception ignored) {
                            // observer 失败不影响主流程
                        }

                        runPostEditLspHook(path, safe, lspManager);
                        return "文件写入完成:\n" + path;
                    } catch (IOException e) {
                        return "文件写入失败: " + e.getMessage();
                    }
                }
        );
    }

    // list_dir
    private ToolDefinition createListDirFileTool() {
        return new ToolDefinition(
                "list_dir",
                "列出文件目录 (仅限当前项目的根目录之内)",
                ToolSchema.createParameters(new Param("path", "string", "目录路径", true)),
                args -> {
                    Path safe = pathGuardSupplier.get().resolveSafe(args.get("path"));

                    try {
                        File[] files = safe.toFile().listFiles();
                        if (files == null) {
                            return "目录为空或不存在";
                        }

                        StringBuilder stringBuilder = new StringBuilder("目录内容:\n");
                        for (File file : files) {
                            stringBuilder.append(file.isDirectory() ? "[D]" : "[F]")
                                    .append(file.getName())
                                    .append("\n");
                        }

                        return stringBuilder.toString();
                    } catch (Exception e) {
                        return "列出目录失败: " + e.getMessage();
                    }
                }
        );
    }

    // glob_files
    private ToolDefinition createGlobFilesTool() {
        return new ToolDefinition("glob_files",
                "按文件名 glob 查找项目内文件, 适合先定位候选文件, 例如 **/*Service.java",
                ToolSchema.createParameters(
                        new Param("pattern", "string", "glob 模式, 例如 **/*.java、**/*Controller*、README.md", true),
                        new Param("path", "string", "搜索起始目录, 默认 .", false),
                        new Param("max_results", "integer", "最多返回结果数, 默认 50, 最大 200", false)
                ),
                args -> globFiles(args)
        );
    }

    // grep_code
    private ToolDefinition createGrepCodeTool() {
        return new ToolDefinition("grep_tool",
                "在项目内按关键字或正则实时搜索代码（只读、优先 ripgrep、返回文件和行号）；适合精确符号/字符串定位，找到后再 read_file 读取上下文",
                ToolSchema.createParameters(
                        new Param("pattern", "string", "要搜索的关键字或正则", true),
                        new Param("path", "string", "搜索起始目录，默认 .", false),
                        new Param("glob", "string", "可选文件 glob 过滤，例如 **/*.java", false),
                        new Param("regex", "boolean", "是否按 Java 正则解释 pattern，默认 false 表示字面量搜索", false),
                        new Param("case_sensitive", "boolean", "是否大小写敏感，默认 true", false),
                        new Param("context_lines", "integer", "每条命中前后上下文行数，默认 0，上限 5", false),
                        new Param("max_results", "integer", "最多返回命中数，默认 50，上限 200", false),
                        new Param("head_limit", "integer", "单个文件最多返回多少条命中，默认 20，上限 50", false),
                        new Param("max_chars", "integer", "单次工具结果字符预算，默认 24000，上限 60000", false)
                ),
                args -> grepCode(args)
        );
    }

    private void runPostEditLspHook(String displayPath, Path safePath, LspManager lspManager) {
        try {
            if (lspManager != null) {
                lspManager.runPostEditLspHook(displayPath, safePath);
            }
        } catch (Exception ignored) {

        }
    }

    private String readFileForTool(Path file, Map<String, String> args) throws IOException {
        if (!Files.isRegularFile(file)) {
            return "读取文件失败: 不是普通文件";
        }
        boolean ranged = args.containsKey("offset") || args.containsKey("limit");
        if (!ranged) {
            return "文件内容:\n" + Files.readString(file);
        }

        int offset = Math.max(1, parseInt(args.get("offset"), 1));
        int limit = Math.max(1, Math.min(parseInt(args.get("limit"), 200), MAX_READ_FILE_LINES));
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int total = lines.size();
        if (offset > total) {
            return "文件内容: " + file.getFileName() + " 共 " + total + " 行，offset 超出范围";
        }

        int from = offset - 1;
        int to = Math.min(from + limit, total);
        StringBuilder sb = new StringBuilder();
        sb.append("文件内容: ").append(file.getFileName())
                .append(" (lines ").append(offset).append("-").append(to)
                .append(" of ").append(total).append(")\n");
        for (int i = from; i < to; i++) {
            sb.append(String.format("%5d | %s%n", i + 1, lines.get(i)));
        }
        if (to < total) {
            sb.append("...(已截断，可用 offset=").append(to + 1).append(" 继续读取)");
        }
        return sb.toString().trim();
    }

    private String globFiles(Map<String, String> args) {
        String pattern = args.get("pattern");
        if (pattern == null || pattern.isBlank()) {
            return "文件匹配失败: pattern 不能为空";
        }
        Path root = pathGuardSupplier.get().resolveSafe(args.getOrDefault("path", "."));
        int maxResults = clamp(parseInt(args.get("max_results"), 50), 1, MAX_GREP_RESULTS);
        Path projectRoot = pathGuardSupplier.get().getRootPath();
        PathMatcher matcher = projectRoot.getFileSystem().getPathMatcher("glob:" + normalizeGlob(pattern));
        PathMatcher fileNameMatcher = projectRoot.getFileSystem().getPathMatcher("glob:" + normalizeFileNameGlob(pattern));
        List<String> matches = new ArrayList<>();

        try {
            Files.walkFileTree(root, new SearchFileVisitor(projectRoot, path -> {
                if (matches.size() >= maxResults) {
                    return;
                }
                Path relative = projectRoot.relativize(path);
                if (matcher.matches(relative) || fileNameMatcher.matches(path.getFileName())) {
                    matches.add(relative.toString());
                }
            }));
        } catch (Exception e) {
            return "文件匹配失败: " + e.getMessage();
        }

        if (matches.isEmpty()) {
            return "未找到匹配文件: " + pattern;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("匹配文件 ").append(matches.size()).append(" 个");
        if (matches.size() >= maxResults) {
            sb.append("（已达到上限 ").append(maxResults).append("）");
        }
        sb.append(":\n");
        for (int i = 0; i < matches.size(); i++) {
            sb.append(i + 1).append(". ").append(matches.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    private String grepCode(Map<String, String> args) {
        String query = args.get("pattern");
        if (query == null || query.isBlank()) {
            return "代码搜索失败: pattern 不能为空";
        }
        Path root = pathGuardSupplier.get().resolveSafe(args.getOrDefault("path", "."));
        Path projectRoot = pathGuardSupplier.get().getRootPath();
        int maxResults = clamp(parseInt(args.get("max_results"), 50), 1, MAX_GREP_RESULTS);
        int contextLines = clamp(parseInt(args.get("context_lines"), 0), 0, MAX_GREP_CONTEXT_LINES);
        boolean regex = parseBoolean(args.get("regex"), false);
        boolean caseSensitive = parseBoolean(args.get("case_sensitive"), true);
        int headLimit = clamp(parseInt(args.get("head_limit"), DEFAULT_GREP_HEAD_LIMIT), 1, 50);
        int maxChars = clamp(parseInt(args.get("max_chars"), DEFAULT_GREP_MAX_CHARS), 1_000, MAX_GREP_MAX_CHARS);
        CodeSearchRequest request = new CodeSearchRequest(
                query,
                root,
                projectRoot,
                args.get("glob"),
                regex,
                caseSensitive,
                contextLines,
                maxResults,
                headLimit
        );
        CodeSearchResult result = new RipgrepCodeSearchEngine(SEARCH_EXCLUDED_DIRS).search(request);

        if (!result.partialReason().isBlank() && result.matches().isEmpty()) {
            return "代码搜索失败: " + result.partialReason();
        }
        if (result.matches().isEmpty()) {
            return "未找到匹配内容: " + query;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("匹配结果 ").append(result.matches().size()).append(" 条")
                .append(" (engine=").append(result.engine()).append(")");
        if (result.partial()) {
            sb.append("（partial: ").append(result.partialReason()).append("）");
        }
        sb.append(":\n");
        boolean truncatedByChars = false;
        int rendered = 0;
        for (int i = 0; i < result.matches().size(); i++) {
            GrepMatch match = result.matches().get(i);
            String matchHeader = (i + 1) + ". " + match.file() + ":" + match.lineNumber() + "\n";
            if (sb.length() + matchHeader.length() > maxChars) {
                truncatedByChars = true;
                break;
            }
            sb.append(i + 1).append(". ").append(match.file()).append(":").append(match.lineNumber()).append("\n");
            for (ContextLine line : match.context()) {
                String marker = line.lineNumber() == match.lineNumber() ? ">" : " ";
                String contextLine = String.format("   %s%5d | %s%n", marker, line.lineNumber(), line.text());
                if (sb.length() + contextLine.length() > maxChars) {
                    truncatedByChars = true;
                    break;
                }
                sb.append(contextLine);
            }
            rendered++;
            if (truncatedByChars) {
                break;
            }
        }
        if (truncatedByChars) {
            sb.append("\npartial: true（已达到 max_chars=").append(maxChars).append("，请缩小 path/glob/pattern 或提高 offset 后 read_file）");
        } else if (result.partial()) {
            sb.append("\npartial: true（").append(result.partialReason()).append("，请缩小 path/glob/pattern 继续搜索）");
        }
        appendSuggestedReads(sb, result.matches().subList(0, Math.min(rendered, result.matches().size())));
        return sb.toString().trim();
    }

    private void appendSuggestedReads(StringBuilder sb, List<GrepMatch> matches) {
        if (matches.isEmpty()) {
            return;
        }
        sb.append("\nsuggested_reads:");
        Set<String> seen = new LinkedHashSet<>();
        for (GrepMatch match : matches) {
            if (seen.size() >= 3 || !seen.add(match.file())) {
                continue;
            }
            int offset = Math.max(1, match.lineNumber() - 20);
            sb.append("\n- read_file {\"path\":\"")
                    .append(match.file().replace("\\", "\\\\").replace("\"", "\\\""))
                    .append("\",\"offset\":").append(offset)
                    .append(",\"limit\":80}");
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static String normalizeGlob(String pattern) {
        String normalized = pattern == null ? "**/*" : pattern.replace('\\', '/').trim();
        if (normalized.isEmpty()) {
            return "**/*";
        }
        if (!normalized.contains("/") && !normalized.startsWith("**")) {
            return "**/" + normalized;
        }
        return normalized;
    }

    private static String normalizeFileNameGlob(String pattern) {
        String normalized = pattern == null ? "*" : pattern.replace('\\', '/').trim();
        if (normalized.isEmpty()) {
            return "*";
        }
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim())
                || "yes".equalsIgnoreCase(value.trim());
    }

    private static final class SearchFileVisitor extends SimpleFileVisitor<Path> {
        private final Path projectRoot;
        private final Consumer<Path> fileConsumer;

        private SearchFileVisitor(Path projectRoot, Consumer<Path> fileConsumer) {
            this.projectRoot = projectRoot;
            this.fileConsumer = fileConsumer;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
            if (!dir.equals(projectRoot) && SEARCH_EXCLUDED_DIRS.contains(name)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            fileConsumer.accept(file);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
        }
    }
}
