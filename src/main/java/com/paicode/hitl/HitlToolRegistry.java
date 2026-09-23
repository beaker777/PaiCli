package com.paicode.hitl;

import com.paicode.hitl.DTO.ApprovalPolicy;
import com.paicode.hitl.DTO.ApprovalRequest;
import com.paicode.hitl.DTO.ApprovalResult;
import com.paicode.tool.ToolRegistry;
import lombok.Getter;

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
        ApprovalRequest request = ApprovalRequest.of(name, argumentJson, null);
        ApprovalResult result = hitlHandler.requestApproval(request);

        if (result.isRejected()) {
            String reason = result.reason() != null && !result.reason().isBlank() ?
                    result.reason() : "用户拒绝了此操作";
            return "[HITL] 该操作被拒绝: " + reason;
        }
        if (result.isSkipped()) {
            return "[HITL] 该操作被跳过";
        }

        // 获取最终参数
        String effectiveArguments = result.effectiveArguments(argumentJson);
        return super.executeTool(name, effectiveArguments);
    }
}
