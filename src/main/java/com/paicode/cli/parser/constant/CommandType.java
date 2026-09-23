package com.paicode.cli.parser.constant;

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

    // 退出 agent
    EXIT,

    // 清空记忆
    CLEAR,

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
}
