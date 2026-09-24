package com.paicode.hitl.service;

import com.paicode.hitl.entity.ApprovalPolicy;
import com.paicode.hitl.entity.ApprovalRequest;
import com.paicode.hitl.entity.ApprovalResult;
import com.paicode.policy.entity.AuditEntry;
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

        // 构建请求并发起审批
        long start = System.nanoTime();
        ApprovalRequest request = ApprovalRequest.of(name, argumentJson, null);
        ApprovalResult result = hitlHandler.requestApproval(request);

        // 被拒绝或跳过记录日志
        if (result.isRejected()) {
            String reason = result.reason() != null && !result.reason().isBlank() ?
                    result.reason() : "用户拒绝了此操作";

            getAuditLog().record(AuditEntry.denyByHitl(name, argumentJson, reason, elapsedMillis(start)));
            return "[HITL] 该操作被拒绝: " + reason;
        }
        if (result.isSkipped()) {
            getAuditLog().record(AuditEntry.denyByHitl(name, argumentJson, "用户跳过", elapsedMillis(start)));
            return "[HITL] 该操作被跳过";
        }

        // 获取最终参数, 后续日志由父类记录
        String effectiveArguments = result.effectiveArguments(argumentJson);
        return super.executeTool(name, effectiveArguments);
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
