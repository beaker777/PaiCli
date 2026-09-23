package com.paicode.agent.PlanAndExecute.entity;

import com.paicode.agent.PlanAndExecute.constant.PlanReviewAction;

/**
 * @Author beaker
 * @Date 2026/9/12 22:33
 * @Description 计划检查选择
 */
public record PlanReviewDecision(PlanReviewAction action, String feedback) {

    public static PlanReviewDecision execute() {
        return new PlanReviewDecision(PlanReviewAction.EXECUTE, null);
    }

    public static PlanReviewDecision supplement(String feedback) {
        return new PlanReviewDecision(PlanReviewAction.SUPPLEMENT, feedback);
    }

    public static PlanReviewDecision cancel() {
        return new PlanReviewDecision(PlanReviewAction.CANCEL, null);
    }
}
