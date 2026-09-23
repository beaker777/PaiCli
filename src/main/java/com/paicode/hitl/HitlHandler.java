package com.paicode.hitl;

import com.paicode.hitl.DTO.ApprovalRequest;
import com.paicode.hitl.DTO.ApprovalResult;

/**
 * @Author beaker
 * @Date 2026/9/22 23:49
 * @Description HITL 交互接口
 */
public interface HitlHandler {

    /**
     * 向用户展示审批请求并收集决策
     */
    ApprovalResult requestApproval(ApprovalRequest request);

    /**
     * HITL 是否启用
     */
    boolean isEnabled();

    /**
     * 启用 / 禁用 HITL
     */
    void setEnabled(boolean enabled);
}
