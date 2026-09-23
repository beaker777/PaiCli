package com.paicode.agent.PlanAndExecute.service;

import com.paicode.agent.PlanAndExecute.entity.PlanReviewDecision;
import com.paicode.plan.entity.ExecutionPlan;

/**
 * @Author beaker
 * @Date 2026/9/12 22:29
 * @Description 计划检查处理器
 */
public interface PlanReviewHandler {

    PlanReviewDecision review(String goal, ExecutionPlan plan);
}
