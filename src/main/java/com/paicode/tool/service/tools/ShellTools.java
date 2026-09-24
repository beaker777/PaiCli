package com.paicode.tool.service.tools;

import com.paicode.tool.entity.Param;
import com.paicode.tool.entity.ToolDefinition;
import com.paicode.tool.entity.ToolSchema;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;

import static com.paicode.tool.service.register.ToolRegistry.MAX_COMMAND_OUTPUT_CHARS;

/**
 * @Author beaker
 * @Date 2026/9/8 23:12
 * @Description 命令行工具
 */
public class ShellTools {

    public static List<ToolDefinition> create(String projectPath, long commandTimeoutSeconds) {
        return List.of(createShellTool(projectPath, commandTimeoutSeconds));
    }

    private static ToolDefinition createShellTool(String projectPath, long commandTimeoutSeconds) {
        return new ToolDefinition(
                "execute_command",
                "在当前项目目录中执行 Shell 命令 (默认 60s 超时, 不允许全盘扫描)",
                ToolSchema.createParameters(new Param("command", "string", "要执行的命令", true)),
                args -> executeCommand(args.get("command"), projectPath, commandTimeoutSeconds)
        );
    }

    private static String executeCommand(String command, String projectPath, long commandTimeoutSeconds) {
        String normalized = command == null ? "" : command.trim();
        if (normalized.isEmpty()) {
            return "执行命令失败: 命令不能为空";
        }
        if (isDisallowedBroadScan(normalized)) {
            return "拒绝执行命令: 不允许扫描 /、~ 或整个文件系统。请改用项目内相对路径，或优先使用 read_file、list_dir、search_code";
        }

        ExecutorService outputReaderExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "paicode-command-output");
            thread.setDaemon(true);
            return thread;
        });

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", normalized);
            pb.directory(new File(projectPath));
            pb.redirectErrorStream(true);
            process = pb.start();

            Process runningProcess = process;
            Future<String> outputFuture = outputReaderExecutor.submit(() -> readProcessOutput(runningProcess));

            boolean finished = process.waitFor(commandTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                outputFuture.cancel(true);
                return "命令执行超时（" + commandTimeoutSeconds + "秒），已强制终止";
            }

            String output = getCommandOutput(outputFuture);
            int exitCode = process.exitValue();
            return String.format("命令执行完成 (exit code: %d)\n%s", exitCode, output);
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return "执行命令失败: " + e.getMessage();
        } finally {
            outputReaderExecutor.shutdownNow();
        }
    }

    private static boolean isDisallowedBroadScan(String command) {
        String normalized = command.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        return normalized.contains("find /")
                || normalized.contains("find ~")
                || normalized.contains("find $home");
    }

    private static String readProcessOutput(Process process) throws Exception {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < MAX_COMMAND_OUTPUT_CHARS) {
                    int remaining = MAX_COMMAND_OUTPUT_CHARS - output.length();
                    if (line.length() > remaining) {
                        output.append(line, 0, remaining);
                    } else {
                        output.append(line);
                    }
                    output.append("\n");
                }
            }
        }
        if (output.length() >= MAX_COMMAND_OUTPUT_CHARS) {
            return output.substring(0, MAX_COMMAND_OUTPUT_CHARS) + "\n...(输出已截断)";
        }
        return output.toString();
    }

    private static String getCommandOutput(Future<String> outputFuture) throws Exception {
        try {
            return outputFuture.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            outputFuture.cancel(true);
            return "(命令已结束, 但输出读取超时)";
        }
    }
}
