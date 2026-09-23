package com.paicode.hitl.entity;

import com.paicode.hitl.constant.ApprovalDecision;

/**
 * @Author beaker
 * @Date 2026/9/22 23:37
 * @Description 审批结果, 用户对一次工具调用审批的结果
 */
public record ApprovalResult(ApprovalDecision decision, String modifiedArguments, String reason) {

    public static ApprovalResult approve() {
        return new ApprovalResult(ApprovalDecision.APPROVED, null, null);
    }

    public static ApprovalResult approveAll() {
        return new ApprovalResult(ApprovalDecision.APPROVED_ALL, null, null);
    }

    public static ApprovalResult reject(String reason) {
        return new ApprovalResult(ApprovalDecision.REJECTED, null, reason);
    }

    public static ApprovalResult modify(String modifiedArguments) {
        return new ApprovalResult(ApprovalDecision.MODIFIED, modifiedArguments, null);
    }

    public static ApprovalResult skip() {
        return new ApprovalResult(ApprovalDecision.SKIPPED, null, null);
    }

    public boolean isApprovedAll() {
        return decision == ApprovalDecision.APPROVED_ALL;
    }

    public boolean isRejected() {
        return decision == ApprovalDecision.REJECTED;
    }

    public boolean isSkipped() {
        return decision == ApprovalDecision.SKIPPED;
    }

    public String effectiveArguments(String originalArguments) {
        if (decision == ApprovalDecision.MODIFIED && modifiedArguments != null && !modifiedArguments.isBlank()) {
            return modifiedArguments;
        }
        return originalArguments;
    }
}
