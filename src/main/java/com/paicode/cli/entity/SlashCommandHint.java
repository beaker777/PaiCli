package com.paicode.cli.entity;

import java.util.List;

/**
 * @Author beaker
 * @Date 2026/9/29 22:46
 * @Description 完整命令
 */
public record SlashCommandHint(String insertText, String display, String description) {

    public static List<SlashCommandHint> slashCommandHints() {
        return List.of(
                new SlashCommandHint("/model", "/model", "查看当前模型"),
                new SlashCommandHint("/model glm", "/model glm", "切换到 GLM-5.1"),
                new SlashCommandHint("/model deepseek", "/model deepseek", "切换到 DeepSeek V4"),
                new SlashCommandHint("/plan", "/plan", "下一条任务使用 Plan-and-Execute 模式"),
                new SlashCommandHint("/plan ", "/plan <任务内容>", "直接用计划模式执行这条任务"),
                new SlashCommandHint("/team", "/team", "下一条任务使用 Multi-Agent 协作模式"),
                new SlashCommandHint("/team ", "/team <任务内容>", "直接用多 Agent 协作执行这条任务"),
                new SlashCommandHint("/hitl", "/hitl", "查看 HITL 状态"),
                new SlashCommandHint("/hitl on", "/hitl on", "启用危险操作人工审批"),
                new SlashCommandHint("/hitl off", "/hitl off", "关闭 HITL 审批"),
                new SlashCommandHint("/browser", "/browser", "查看浏览器会话状态"),
                new SlashCommandHint("/browser connect", "/browser connect", "复用已允许远程调试的登录态 Chrome"),
                new SlashCommandHint("/browser connect ", "/browser connect <port>", "旧式 CDP 端口连接"),
                new SlashCommandHint("/browser status", "/browser status", "查看浏览器会话状态"),
                new SlashCommandHint("/browser tabs", "/browser tabs", "查看 shared 模式真实 Chrome tab"),
                new SlashCommandHint("/browser disconnect", "/browser disconnect", "切回 isolated 浏览器模式"),
                new SlashCommandHint("/mcp", "/mcp", "查看 MCP server 状态"),
                new SlashCommandHint("/mcp restart ", "/mcp restart <name>", "重启 MCP server"),
                new SlashCommandHint("/mcp logs ", "/mcp logs <name>", "查看 MCP server 日志"),
                new SlashCommandHint("/mcp disable ", "/mcp disable <name>", "禁用 MCP server"),
                new SlashCommandHint("/mcp enable ", "/mcp enable <name>", "启用 MCP server"),
                new SlashCommandHint("/mcp resources ", "/mcp resources <name>", "查看 MCP resources"),
                new SlashCommandHint("/mcp prompts ", "/mcp prompts <name>", "查看 MCP prompts"),
                new SlashCommandHint("/skill", "/skill", "查看 skill 列表"),
                new SlashCommandHint("/skill list", "/skill list", "查看 skill 列表"),
                new SlashCommandHint("/skill show ", "/skill show <name>", "查看 SKILL.md 全文"),
                new SlashCommandHint("/skill on ", "/skill on <name>", "启用 skill"),
                new SlashCommandHint("/skill off ", "/skill off <name>", "禁用 skill"),
                new SlashCommandHint("/skill reload", "/skill reload", "重新扫描 skill 目录"),
                new SlashCommandHint("/policy", "/policy", "查看安全策略状态"),
                new SlashCommandHint("/audit", "/audit", "查看今日最近 10 条危险工具审计"),
                new SlashCommandHint("/audit ", "/audit [N]", "查看今日最近 N 条危险工具审计"),
                new SlashCommandHint("/index", "/index", "索引当前代码库"),
                new SlashCommandHint("/index ", "/index [路径]", "索引指定路径代码库"),
                new SlashCommandHint("/search ", "/search <查询>", "语义检索代码"),
                new SlashCommandHint("/graph ", "/graph <类名>", "查看代码关系图谱"),
                new SlashCommandHint("/clear", "/clear", "清空当前对话历史"),
                new SlashCommandHint("/context", "/context", "查看上下文和记忆状态"),
                new SlashCommandHint("/memory", "/memory", "查看记忆状态"),
                new SlashCommandHint("/memory clear", "/memory clear", "清空长期记忆"),
                new SlashCommandHint("/save ", "/save <事实内容>", "手动保存关键事实到长期记忆"),
                new SlashCommandHint("/exit", "/exit", "退出 PaiCLI"),
                new SlashCommandHint("/quit", "/quit", "退出 PaiCLI")
        );
    }
}
