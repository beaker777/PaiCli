package com.paicode.plan;

import com.paicode.plan.DTO.PlanReviewDecision;

/**
 * @Author beaker
 * @Date 2026/9/12 22:29
 * @Description 计划检查处理器
 */
public interface PlanReviewHandler {

    PlanReviewDecision review(String goal, ExecutionPlan plan);
}
