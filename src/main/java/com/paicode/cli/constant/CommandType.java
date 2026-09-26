package com.paicode.cli.constant;

/**
 * @Author beaker
 * @Date 2026/9/12 01:04
 * @Description 命令类型
 */
public enum CommandType {

    // 不进行任何操作
    NONE,

    // 未知命令
    UNKNOWN_COMMAND,

    // 取消
    CANCEL,

    // 退出 agent
    EXIT,

    // 清空记忆
    CLEAR,

    // 切换模型
    SWITCH_MODEL,

    // 切换到 plan 模式
    SWITCH_PLAN,

    // 切换到 multi-agent 模式
    SWITCH_TEAM,

    // 切换到 HITL 模式
    SWITCH_HITL,

    // 记忆状态
    MEMORY_STATUS,

    // 保存记忆
    MEMORY_SAVE,

    // 清空记忆
    MEMORY_CLEAR,

    // 建立索引
    INDEX_CODE,

    // 查找代码库
    SEARCH_CODE,

    // 查找关系图谱
    GRAPH_QUERY,

    // 上下文状态
    CONTEXT_STATUS,

    // 策略状态
    POLICY_STATUS,

    // 审计
    AUDIT_TAIL,

    // mcp 列表
    MCP_LIST,

    // mcp 重启
    MCP_RESTART,

    // mcp 日志
    MCP_LOGS,

    // mcp 禁用
    MCP_DISABLE,

    // mcp 启用
    MCP_ENABLE,

    // mcp 资源
    MCP_RESOURCES,

    // mcp prompt
    MCP_PROMPTS,
}
