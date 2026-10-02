package com.paicode.hitl.service.handler.impl;

import com.paicode.hitl.entity.ApprovalRequest;
import com.paicode.hitl.entity.ApprovalResult;
import com.paicode.hitl.service.handler.HitlHandler;
import lombok.Getter;

import java.util.Objects;

/**
 * @Author beaker
 * @Date 2026/10/2 01:02
 * @Description 委托 HITL 交互给当前活跃的 UI
 */
@Getter
public class SwitchableHitlHandler implements HitlHandler {

    private volatile HitlHandler delegate;

    public SwitchableHitlHandler(HitlHandler delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public void setDelegate(HitlHandler delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public ApprovalResult requestApproval(ApprovalRequest request) {
        return delegate.requestApproval(request);
    }

    @Override
    public boolean isEnabled() {
        return delegate.isEnabled();
    }

    @Override
    public void setEnabled(boolean enabled) {
        delegate.setEnabled(enabled);
    }

    @Override
    public boolean isApprovedAllByTool(String toolName) {
        return delegate.isApprovedAllByTool(toolName);
    }

    @Override
    public boolean isApprovedAllByServer(String serverName) {
        return delegate.isApprovedAllByServer(serverName);
    }

    @Override
    public void clearApprovedAll() {
        delegate.clearApprovedAll();
    }

    @Override
    public void clearApprovedAllForServer(String serverName) {
        delegate.clearApprovedAllForServer(serverName);
    }
}
