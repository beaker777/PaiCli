package com.paicode.tool.service;

import com.paicode.tool.DTO.Param;
import com.paicode.tool.ToolDefinition;
import com.paicode.tool.ToolSchema;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * @Author beaker
 * @Date 2026/9/8 22:13
 * @Description 文件工具
 */
public class FileTools {

    public static List<ToolDefinition> create() {
        return List.of(
                createReadFileTool(),
                createWriteFileTool(),
                createListDirFileTool()
        );
    }

    // read_file
    private static ToolDefinition createReadFileTool() {
        return new ToolDefinition(
                "read_file",
                "读取文件内容",
                ToolSchema.createParameters(new Param("path", "string", "文件路径", true)),
                args -> {
                    String path = args.get("path");
                    if (path == null || path.isBlank()) {
                        return "文件读取失败, path 为空";
                    }

                    try {
                        return "文件内容:\n" + Files.readString(Path.of(path));
                    } catch (IOException e) {
                        return "文件读取失败: " + e.getMessage();
                    }
                }
        );
    }

    // write_file
    private static ToolDefinition createWriteFileTool() {
        return new ToolDefinition(
                "write_file",
                "写入文件内容",
                ToolSchema.createParameters(
                        new Param("path", "string", "文件路径", true),
                        new Param("content", "string", "文件内容", true)
                ),
                args -> {
                    String path = args.get("path");
                    String content = args.get("content");

                    try {
                        // 确保目录存在
                        Path parent = Path.of(path).getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }

                        Files.writeString(Path.of(path), content);
                        return "文件写入完成:\n" + path;
                    } catch (IOException e) {
                        return "文件写入失败: " + e.getMessage();
                    }
                }
        );
    }

    // list_dir
    private static ToolDefinition createListDirFileTool() {
        return new ToolDefinition(
                "list_dir",
                "列出文件目录",
                ToolSchema.createParameters(new Param("path", "string", "目录路径", true)),
                args -> {
                    String path = args.get("path");

                    try {
                        File dir = new File(path);
                        File[] files = dir.listFiles();
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
