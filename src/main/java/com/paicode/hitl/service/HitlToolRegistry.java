package com.paicode.hitl.service;

import com.paicode.browser.entity.BrowserCheckResult;
import com.paicode.hitl.entity.ApprovalPolicy;
import com.paicode.hitl.entity.ApprovalRequest;
import com.paicode.hitl.entity.ApprovalResult;
import com.paicode.policy.entity.AuditEntry;
import com.paicode.policy.service.audit.AuditLog;
import com.paicode.tool.service.register.ToolRegistry;
import lombok.Getter;

import java.util.concurrent.TimeUnit;

/**
 * @Author beaker
 * @Date 2026/9/23 15:57
 * @Description HITL 工具注册表
 */
@Getter
public class HitlToolRegistry extends ToolRegistry {

    private final HitlHandler hitlHandler;

    public HitlToolRegistry(HitlHandler hitlHandler) {
        super();
        this.hitlHandler = hitlHandler;
    }

    @Override
    public String executeTool(String name, String argumentJson) {
        // HITL 未启用, 或该工具不需要审批, 直接放行
        if (!hitlHandler.isEnabled() || !ApprovalPolicy.requiresApproval(name)) {
            return super.executeTool(name, argumentJson);
        }

        // 进行敏感网页检测
        BrowserCheckResult browserCheck = checkBrowserTool(name, argumentJson, true);
        if (browserCheck.blocked()) {
            return super.executeTool(name, argumentJson);
        }
        if (browserCheck.requiresPerCallApproval()) {
            return executeAfterExplicitApproval(name, argumentJson, browserCheck.sensitiveNotice());
        }

        // 该工具已设置为直接放行
        String mcpServer = ApprovalPolicy.mcpServerName(name);
        if (hitlHandler.isApprovedAllByTool(name) || hitlHandler.isApprovedAllByServer(mcpServer)) {
            return super.executeTool(name, argumentJson);
        }

        return executeAfterExplicitApproval(name, argumentJson, null);
    }

    /**
     * 执行需要审批的网页操作
     */
    private String executeAfterExplicitApproval(String name, String argumentsJson, String sensitiveNotice) {
        // 构建请求并发起审批
        long start = System.nanoTime();
        ApprovalRequest request = ApprovalRequest.of(name, argumentsJson, null, null, sensitiveNotice);
        ApprovalResult result = hitlHandler.requestApproval(request);

        // 被拒绝或跳过记录日志
        if (result.isRejected()) {
            String reason = result.reason() != null && !result.reason().isBlank() ? result.reason() : "用户拒绝了此操作";
            getAuditLog().record(AuditEntry.denyByHitl(name, argumentsJson, reason, elapsedMillis(start)));
            return "[HITL] 操作已被拒绝: " + reason;
        }
        if (result.isSkipped()) {
            getAuditLog().record(AuditEntry.denyByHitl(name, argumentsJson, "用户跳过", elapsedMillis(start)));
            return "[HITL] 操作已被跳过";
        }

        // 获取最终参数, 后续日志由父类记录
        String effectiveArgs = result.effectiveArguments(argumentsJson);
        return super.executeTool(name, effectiveArgs);
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
