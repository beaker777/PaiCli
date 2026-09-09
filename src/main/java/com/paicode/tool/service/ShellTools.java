package com.paicode.tool.service;

import com.paicode.tool.DTO.Param;
import com.paicode.tool.ToolDefinition;
import com.paicode.tool.ToolSchema;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

/**
 * @Author beaker
 * @Date 2026/9/8 23:12
 * @Description 命令行工具
 */
public class ShellTools {

    public static List<ToolDefinition> create() {
        return List.of(createShellTool());
    }

    private static ToolDefinition createShellTool() {
        return new ToolDefinition(
                "execute_command",
                "执行 Shell 命令",
                ToolSchema.createParameters(new Param("command", "string", "要执行的命令", true)),
                args -> {
                    String command = args.get("command");

                    try {
                        ProcessBuilder processBuilder = new ProcessBuilder("bash", "-c", command);
                        processBuilder.redirectErrorStream(true);
                        Process process = processBuilder.start();

                        StringBuilder output = new StringBuilder();
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                output.append(line).append("\n");
                            }
                        }

                        int exitCode = process.waitFor();
                        return String.format("命令执行完成 (exit code: %d)\n%s", exitCode, output);
                    } catch (Exception e) {
                        return "执行命令失败: " + e.getMessage();
                    }
                }
        );
    }
}
