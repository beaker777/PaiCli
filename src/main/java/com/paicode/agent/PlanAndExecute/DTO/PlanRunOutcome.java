package com.paicode.agent.PlanAndExecute.DTO;

/**
 * @Author beaker
 * @Date 2026/9/19 21:54
 * @Description plan 运行结果
 */
public record PlanRunOutcome(String result, boolean persistAssistantMessage, boolean extractFacts) {

    public static PlanRunOutcome executed(String result) {
        return new PlanRunOutcome(result, true, true);
    }

    public static PlanRunOutcome canceled(String result) {
        return new PlanRunOutcome(result, false, false);
    }

    public static PlanRunOutcome failed(String result) {
        return new PlanRunOutcome(result, true, false);
    }
}
