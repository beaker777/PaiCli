package com.paicode.tui.service.hitl;

import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogBuilder;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.paicode.hitl.entity.ApprovalPolicy;
import com.paicode.hitl.entity.ApprovalRequest;
import com.paicode.hitl.entity.ApprovalResult;
import com.paicode.hitl.service.handler.HitlHandler;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @Author beaker
 * @Date 2026/10/1 19:25
 * @Description TUI 模式下的 HITL 审批处理器, 使用窗口让用户确认
 */
public class TuiHitlHandler implements HitlHandler {

    private volatile boolean enabled = true;

    private final Set<String> approvedAllByTool = ConcurrentHashMap.newKeySet();
    private final Set<String> approvedAllByServer = ConcurrentHashMap.newKeySet();

    private final WindowBasedTextGUI gui;

    public TuiHitlHandler(WindowBasedTextGUI gui) {
        this.gui = gui;
    }

    @Override
    public ApprovalResult requestApproval(ApprovalRequest request) {
        if (!enabled) {
            return ApprovalResult.approve();
        }

        String mcpServer = ApprovalPolicy.mcpServerName(request.toolName());
        boolean sensitivePerCall = request.sensitiveNotice() != null && !request.sensitiveNotice().isBlank();

        // 检查是否全部放行
        if (!sensitivePerCall && isApprovedAllByTool(request.toolName())) {
            return ApprovalResult.approveAll();
        }
        if (!sensitivePerCall && isApprovedAllByServer(mcpServer)) {
            return ApprovalResult.approveAllByServer();
        }

        String title = "⚠️  HITL 审批请求";
        String content = request.toDisplayText()
                + "\n\nYes: 批准本次  No: 拒绝  Cancel: 跳过";

        MessageDialogBuilder dialog = new MessageDialogBuilder()
                .setTitle(title)
                .setText(content)
                .addButton(MessageDialogButton.Yes)
                .addButton(MessageDialogButton.No)
                .addButton(MessageDialogButton.Cancel);
        MessageDialogButton decision = dialog.build().showDialog(gui);

        if (decision == MessageDialogButton.Yes) {
            return ApprovalResult.approve();
        }
        if (decision == MessageDialogButton.Cancel) {
            return ApprovalResult.skip();
        }
        return ApprovalResult.reject("用户在 TUI 中拒绝了本次请求");
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean isApprovedAllByTool(String toolName) {
        return toolName != null && approvedAllByTool.contains(toolName);
    }

    @Override
    public boolean isApprovedAllByServer(String serverName) {
        return serverName != null && approvedAllByServer.contains(serverName);
    }

    @Override
    public void clearApprovedAll() {
        approvedAllByTool.clear();
        approvedAllByServer.clear();
    }

    @Override
    public void clearApprovedAllForServer(String serverName) {
        if (serverName != null && !serverName.isBlank()) {
            approvedAllByServer.remove(serverName);
        }
    }
}
