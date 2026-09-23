package com.paicode.hitl.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicode.hitl.entity.ApprovalRequest;
import com.paicode.hitl.entity.ApprovalResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @Author beaker
 * @Date 2026/9/22 23:54
 * @Description 终端 HITL 审批处理器
 */
public class TerminalHitlHandler implements HitlHandler {

    private static final ObjectMapper mapper = new ObjectMapper();

    private volatile boolean enabled;

    // 本次会话中已经批准全部放行的工具集合
    private final Set<String> approvedAllTools = ConcurrentHashMap.newKeySet();

    private final BufferedReader in;
    private final PrintStream out;

    public TerminalHitlHandler(boolean enabled) {
        this(enabled, new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out);
    }

    public TerminalHitlHandler(boolean enabled, BufferedReader in, PrintStream out) {
        this.enabled = enabled;
        this.out = out;
        this.in = in;
    }

    @Override
    public ApprovalResult requestApproval(ApprovalRequest request) {
        if (approvedAllTools.contains(request.toolName())) {
            out.println("  [HITL] " + request.toolName() + " 已在本次会话中全部放行，自动通过");
            return ApprovalResult.approveAll();
        }

        // 显著的视觉分隔符，避免审批框被误认为属于上游的"回复"区
        out.println();
        out.println("────────── ⚠️  HITL 审批请求 ──────────");
        out.println(request.toDisplayText());

        return promptUntilDecision(request);
    }

    /**
     * 主交互循环：无法识别的输入会重新提示而非默认放行（fail-safe）。
     */
    private ApprovalResult promptUntilDecision(ApprovalRequest request) {
        for (int attempt = 0; attempt < 5; attempt++) {
            out.println();
            out.println("请选择操作：[y/Enter] 批准  [a] 全部放行  [n] 拒绝  [s] 跳过  [m] 修改参数");
            out.print("> ");
            out.flush();

            String input;
            try {
                input = in.readLine();
            } catch (IOException e) {
                out.println("  [HITL] 读取用户输入失败, 保守处理为拒绝");
                return ApprovalResult.reject("读取输入失败: " + e.getMessage());
            }
            if (input == null) {
                out.println("  [HITL] 输入流已关闭, 保守处理为拒绝");
                return ApprovalResult.reject("输入流已关闭");
            }

            String normalized = input.trim().toLowerCase();

            // Enter 或 y 等价于批准
            if (normalized.isEmpty() || normalized.equals("y")) {
                out.println("  已批准");
                return ApprovalResult.approve();
            }
            switch (normalized) {
                case "a" -> {
                    approvedAllTools.add(request.toolName());
                    out.println("  已批准，后续 " + request.toolName() + " 操作将自动通过");
                    return ApprovalResult.approveAll();
                }
                case "n" -> {
                    out.print("  拒绝原因（可直接回车跳过）：");
                    out.flush();
                    String reason;
                    try {
                        reason = in.readLine();
                    } catch (IOException e) {
                        reason = "";
                    }
                    return ApprovalResult.reject(reason == null ? "" : reason.trim());
                }
                case "s" -> {
                    out.println("  已跳过本次操作");
                    return ApprovalResult.skip();
                }
                case "m" -> {
                    ApprovalResult modified = promptModifiedArguments(request);
                    if (modified != null) {
                        return modified;
                    }
                    // 修改失败（JSON 非法等）时回到主菜单重新提示
                }
                default -> out.println("  ❓ 无法识别的选项：'" + input + "'，请输入 y/a/n/s/m 之一（Enter 等价于 y）");
            }
        }

        out.println("  [HITL] 连续多次无效输入，保守处理为拒绝");
        return ApprovalResult.reject("连续多次无效输入");
    }

    /**
     * 修改参数子流程：验证用户输入为合法 JSON；非法则返回 null 让主循环重新提示。
     */
    private ApprovalResult promptModifiedArguments(ApprovalRequest request) {
        out.println("  当前参数：" + request.arguments());
        out.print("  请输入修改后的参数（JSON 格式，空行则使用原始参数）：");
        out.flush();

        String modified;
        try {
            modified = in.readLine();
        } catch (IOException e) {
            out.println("  读取失败，回到主菜单");
            return null;
        }
        if (modified == null || modified.isBlank()) {
            out.println("  输入为空，改为批准原始参数");
            return ApprovalResult.approve();
        }

        String trimmed = modified.trim();
        try {
            mapper.readTree(trimmed);
        } catch (Exception e) {
            out.println("  ❌ 修改后的参数不是合法 JSON：" + e.getMessage());
            return null;  // 回到主菜单
        }
        return ApprovalResult.modify(trimmed);
    }

    public void clearApprovedAll() {
        approvedAllTools.clear();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
