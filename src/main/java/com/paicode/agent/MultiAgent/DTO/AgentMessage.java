package com.paicode.agent.MultiAgent.DTO;

import com.paicode.agent.MultiAgent.constant.AgentMessageType;
import com.paicode.agent.MultiAgent.constant.AgentRole;

/**
 * @Author beaker
 * @Date 2026/9/20 23:43
 * @Description Agent 间进行通信的基本单元
 */
public record AgentMessage(String fromAgent, AgentRole fromRole, String content, AgentMessageType type) {

    /**
     * 创建任务消息（主控 -> 子代理）
     */
    public static AgentMessage task(String fromAgent, String content) {
        return new AgentMessage(fromAgent, null, content, AgentMessageType.TASK);
    }

    /**
     * 创建结果消息（子代理 -> 主控）
     */
    public static AgentMessage result(String fromAgent, AgentRole role, String content) {
        return new AgentMessage(fromAgent, role, content, AgentMessageType.RESULT);
    }

    /**
     * 创建反馈消息（检查者 -> 主控）
     */
    public static AgentMessage feedback(String fromAgent, String content) {
        return new AgentMessage(fromAgent, AgentRole.REVIEWER, content, AgentMessageType.FEEDBACK);
    }

    /**
     * 创建审批通过消息
     */
    public static AgentMessage approval(String fromAgent, String content) {
        return new AgentMessage(fromAgent, AgentRole.REVIEWER, content, AgentMessageType.APPROVAL);
    }

    /**
     * 创建拒绝消息（检查者认为结果不合格）
     */
    public static AgentMessage rejection(String fromAgent, String content) {
        return new AgentMessage(fromAgent, AgentRole.REVIEWER, content, AgentMessageType.REJECTION);
    }

    /**
     * 创建错误消息（子代理在执行过程中遇到系统级错误）
     */
    public static AgentMessage error(String fromAgent, AgentRole role, String content) {
        return new AgentMessage(fromAgent, role, content, AgentMessageType.ERROR);
    }
}
