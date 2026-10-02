package com.paicode.renderer.service.manage;

import com.paicode.hitl.entity.ApprovalRequest;
import com.paicode.hitl.entity.ApprovalResult;
import com.paicode.llm.entity.ToolCall;
import com.paicode.renderer.entity.StatusInfo;

import java.io.PrintStream;
import java.util.List;

/**
 * @Author beaker
 * @Date 2026/10/1 23:37
 * @Description 终端渲染器抽象
 */
public interface Renderer extends AutoCloseable {

    /** 启动渲染器（例如设置滚动区域、启动 GUI 主循环）。Main 必须先调用一次。 */
    void start();

    /**
     * 流式输出的目标 PrintStream。
     */
    PrintStream stream();

    /**
     * 渲染一组工具调用的标签和关键参数。
     */
    void appendToolCalls(List<ToolCall> toolCalls);

    /**
     * 渲染一个文件 diff 块。
     *
     * @param filePath 文件路径
     * @param before   修改前内容（null 表示新建）
     * @param after    修改后内容（null 表示删除）
     */
    void appendDiff(String filePath, String before, String after);

    /** 更新底部状态栏 / StatusPane。允许频繁调用，渲染器内部自行节流。 */
    void updateStatus(StatusInfo status);

    /**
     * 同步阻塞地展示 HITL 审批请求并收集决策。
     */
    ApprovalResult promptApproval(ApprovalRequest request);

    /**
     * 显示一个临时浮起的选择列表，等待用户选定一项或取消。
     */
    int openPalette(String title, List<String> items);

    @Override
    void close();
}
