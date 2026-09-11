package com.paicode.plan.constant;

/**
 * @Author beaker
 * @Date 2026/9/10 17:19
 * @Description 计划状态
 */
public enum PlanStatus {

    // 刚被创建
    CREATED,

    // 正在运行
    RUNNING,

    // 运行完成
    COMPLETED,

    // 运行失败
    FAILED,

    // 被取消
    CANCELED
}
