package com.paicode.tool.service.tools;

import com.paicode.policy.exception.PolicyException;
import com.paicode.policy.service.guard.PathGuard;
import com.paicode.tool.entity.Param;
import com.paicode.tool.entity.ToolDefinition;
import com.paicode.tool.entity.ToolSchema;
import com.paicode.tool.service.register.ToolRegistry;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * @Author beaker
 * @Date 2026/9/8 22:13
 * @Description 文件工具
 */
public class FileTools {

    // write_file 单次写入字节数上限, LLM 想塞超大内容时通常是误生成 (重复粘贴 / hallucinate 大段日志)
    private static final int MAX_WRITE_FILE_BYTES = 5 * 1024 * 1024;
    private final Supplier<PathGuard> pathGuardSupplier;

    public FileTools(Supplier<PathGuard> pathGuardSupplier) {
        this.pathGuardSupplier = pathGuardSupplier;
    }

    public List<ToolDefinition> create() {
        return List.of(
                createReadFileTool(),
                createWriteFileTool(),
                createListDirFileTool()
        );
    }

    // read_file
    private ToolDefinition createReadFileTool() {
        return new ToolDefinition(
                "read_file",
                "读取文件内容 (仅限当前项目的根目录之内)",
                ToolSchema.createParameters(new Param("path", "string", "文件路径", true)),
                args -> {
                    Path safe = pathGuardSupplier.get().resolveSafe(args.get("path"));

                    try {
                        return "文件内容:\n" + Files.readString(safe);
                    } catch (IOException e) {
                        return "文件读取失败: " + e.getMessage();
                    }
                }
        );
    }

    // write_file
    private ToolDefinition createWriteFileTool() {
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
                    try {
                        // 确保目录存在
                        Path parent = safe.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }

                        Files.writeString(safe, content);
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
}
