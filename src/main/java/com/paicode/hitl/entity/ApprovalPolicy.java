package com.paicode.hitl.entity;

import lombok.Getter;

import java.util.Set;

/**
 * @Author beaker
 * @Date 2026/9/22 23:13
 * @Description 危险操作识别政策, 基于静态识别, MCP 工具默认需要审批
 */
public class ApprovalPolicy {

    // 需要人工确认的工具集合
    private static final Set<String> DANGEROUS_TOOLS = Set.of(
            "write_file",
            "execute_command",
            "create_project"
    );

    /**
     * 判断是否需要进行人工确认
     */
    public static boolean requiresApproval(String toolName) {
        return DANGEROUS_TOOLS.contains(toolName) || isMcpTool(toolName);
    }

    /**
     * 获取危险等级描述
     */
    public static String getDangerLevel(String toolName) {
        return switch (toolName) {
            case "execute_command" -> "🔴 高危";
            case "write_file", "create_project" -> "🟡 中危";
            default -> isMcpTool(toolName) ? "🟡 MCP" : "🟢 安全";
        };
    }

    /**
     * 获取危险操作的风险说明
     */
    public static String getRiskDescription(String toolName) {
        return switch (toolName) {
            case "execute_command" -> "将在系统上执行 Shell 命令，可能修改文件、安装软件或影响系统状态";
            case "write_file" -> "将写入或覆盖文件内容，原有内容将丢失";
            case "create_project" -> "将在磁盘上创建新目录和文件";
            default -> isMcpTool(toolName)
                    ? "将调用外部 MCP server 提供的工具，可能访问网络、文件或第三方服务"
                    : "安全的只读操作";
        };
    }

    public static Set<String> getDangerousTools() {
        return DANGEROUS_TOOLS;
    }

    public static boolean isMcpTool(String toolName) {
        return toolName != null && toolName.startsWith("mcp__");
    }

    public static String mcpServerName(String toolName) {
        if (!isMcpTool(toolName)) {
            return null;
        }

        String[] parts = toolName.split("__", 3);
        return parts.length >= 2 ? parts[1] : null;
    }
}
