package com.paicode.plan.constant;

/**
 * @Author beaker
 * @Date 2026/9/9 21:37
 * @Description 任务状态
 */
public enum TaskStatus {

    // 准备执行
    PENDING,

    // 运行中
    RUNNING,

    // 执行完成
    COMPLETED,

    // 执行失败
    FAILED,

    // 跳过
    SKIPPED
}
