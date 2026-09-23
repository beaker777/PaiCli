package com.paicode.tool.service.tools;

import com.paicode.tool.entity.Param;
import com.paicode.tool.entity.ToolDefinition;
import com.paicode.tool.service.register.ToolSchema;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * @Author beaker
 * @Date 2026/9/8 23:39
 * @Description 代码工具
 */
public class CodeTools {

    public static List<ToolDefinition> create() {
        return List.of(createCodeTool());
    }

    private static ToolDefinition createCodeTool() {
        return new ToolDefinition(
                "create_project",
                "创建新项目结构",
                ToolSchema.createParameters(
                        new Param("name", "string", "项目名称", true),
                        new Param("type", "string", "项目类型 (java/python/node)", true)
                ),
                args -> {
                    String name = args.get("name");
                    String type = args.get("type");

                    try {
                        Path projectPath = Paths.get(name);
                        Files.createDirectories(projectPath);

                        switch (type.toLowerCase()) {
                            case "java" -> {
                                Files.createDirectories(projectPath.resolve("src/main/java"));
                                Files.createDirectories(projectPath.resolve("src/main/resources"));
                                Files.writeString(projectPath.resolve("pom.xml"),
                                        String.format("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                                                "<project>\n" +
                                                "    <modelVersion>4.0.0</modelVersion>\n" +
                                                "    <groupId>com.example</groupId>\n" +
                                                "    <artifactId>%s</artifactId>\n" +
                                                "    <version>1.0</version>\n" +
                                                "</project>", name));
                            }
                            case "python" -> {
                                Files.createDirectories(projectPath.resolve(name));
                                Files.writeString(projectPath.resolve("main.py"), "# 主程序入口\n");
                                Files.writeString(projectPath.resolve("requirements.txt"), "# 依赖列表\n");
                            }
                            case "node" -> {
                                Files.writeString(projectPath.resolve("package.json"),
                                        String.format("{\"name\": \"%s\", \"version\": \"1.0.0\"}", name));
                            }
                        }

                        return "项目已创建: " + name + " (类型: " + type + ")";
                    } catch (Exception e) {
                        return "创建项目失败: " + e.getMessage();
                    }
                }
        );
    }
}