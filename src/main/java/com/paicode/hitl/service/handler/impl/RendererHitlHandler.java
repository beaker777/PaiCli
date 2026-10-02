package com.paicode.hitl.service.handler.impl;

import com.paicode.hitl.entity.ApprovalPolicy;
import com.paicode.hitl.entity.ApprovalRequest;
import com.paicode.hitl.entity.ApprovalResult;
import com.paicode.hitl.service.handler.HitlHandler;
import com.paicode.renderer.service.manage.Renderer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * @Author beaker
 * @Date 2026/10/2 01:09
 * @Description 与 Renderer 配合使用的 hitl 处理器, 不同类型的 Renderer 只需要使用该 Handler
 */
public class RendererHitlHandler implements HitlHandler {

    private final Renderer renderer;
    private volatile boolean enabled;
    private final Set<String> approvedAllByTool = ConcurrentHashMap.newKeySet();
    private final Set<String> approvedAllByServer = ConcurrentHashMap.newKeySet();

    public RendererHitlHandler(Renderer renderer, boolean enabled) {
        this.renderer = renderer;
        this.enabled = enabled;
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
    public synchronized ApprovalResult requestApproval(ApprovalRequest request) {
        String mcpServer = ApprovalPolicy.mcpServerName(request.toolName());
        boolean sensitivePerCall = request.sensitiveNotice() != null && !request.sensitiveNotice().isBlank();

        if (!sensitivePerCall && isApprovedAllByTool(request.toolName())) {
            renderer.stream().println("  [HITL] " + request.toolName() + " 已在本次会话中全部放行，自动通过");
            return ApprovalResult.approveAll();
        }
        if (!sensitivePerCall && isApprovedAllByServer(mcpServer)) {
            renderer.stream().println("  [HITL] MCP server " + mcpServer + " 已在本次会话中全部放行，自动通过");
            return ApprovalResult.approveAllByServer();
        }

        ApprovalResult result = renderer.promptApproval(request);
        if (result == null) {
            return ApprovalResult.reject("渲染器返回 null");
        }
        if (result.isApprovedAllForTool()) {
            approvedAllByTool.add(request.toolName());
        } else if (result.isApprovedAllForServer() && mcpServer != null) {
            approvedAllByServer.add(mcpServer);
        }
        return result;
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
        if (serverName != null) {
            approvedAllByServer.remove(serverName);
        }
    }
}
