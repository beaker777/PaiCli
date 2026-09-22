package com.paicode.agent.MultiAgent.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * @Author beaker
 * @Date 2026/9/20 23:38
 * @Description Multi-Agent 中的角色
 */
@AllArgsConstructor
@Getter
public enum AgentRole {

    PLANNER("规划者", "负责分析用户需求, 制定执行计划, 将复杂任务拆解为可执行的子任务"),
    WORKER("执行者", "负责执行具体任务步骤, 调用工具完成任务, 进行文件操作, 命令操作等"),
    REVIEWER("检查者", "负责检查执行结果的质量和正确性, 提供改进建议");

    public final String name;
    public final String description;

}
