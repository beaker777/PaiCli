package com.paicode.tool.service.tools;

import com.paicode.snapshot.entity.RestoreResult;
import com.paicode.snapshot.service.SnapshotService;
import com.paicode.tool.entity.Param;
import com.paicode.tool.entity.ToolDefinition;
import com.paicode.tool.entity.ToolSchema;

import java.util.List;

/**
 * @Author beaker
 * @Date 2026/10/3 23:02
 * @Description 快照工具
 */
public class SnapshotTools {

    public static List<ToolDefinition> create(SnapshotService snapshotService) {
        return List.of(
                createRevertTurnTools(snapshotService)
        );
    }

    private static ToolDefinition createRevertTurnTools(SnapshotService snapshotService) {
        return new ToolDefinition("revert_turn",
                "恢复到 Side-Git 记录的最近第 N 个 pre-turn 快照。会先记录 pre-restore 快照；属于高危写入操作，必须经 HITL 审批。",
                ToolSchema.createParameters(
                        new Param("offset", "integer", "要恢复到的 pre-turn 快照序号, 1 表示最近一次任务开始前", true)
                ),
                args -> {
                    int offset = parseInt(args.get("offset"), 1);
                    try {
                        RestoreResult result = snapshotService.restorePreTurn(Math.max(1, offset));
                        return result.formatForCli();
                    } catch (Exception e) {
                        return "恢复快照失败: " + e.getMessage();
                    }
                });
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
