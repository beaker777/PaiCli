package com.paicode.cli.service;

import com.paicode.cli.entity.Decision;
import com.paicode.cli.constant.DecisionType;

/**
 * @Author beaker
 * @Date 2026/9/13 16:16
 * @Description 计划检查解析器
 */
public class PlanReviewInputParser {

    public static Decision parse(String input) {
        if (input != null && input.equals("\u001B")) {
            return new Decision(DecisionType.CANCEL, null);
        }

        String trimmed = input == null ? "" : input.trim();
        if (trimmed.isEmpty()
                || trimmed.equalsIgnoreCase("y")
                || trimmed.equalsIgnoreCase("yes")
                || trimmed.equalsIgnoreCase("run")
                || trimmed.equalsIgnoreCase("/run")) {
            return new Decision(DecisionType.EXECUTE, null);
        }

        if (trimmed.equalsIgnoreCase("cancel")
                || trimmed.equalsIgnoreCase("esc")
                || trimmed.equalsIgnoreCase("/cancel")) {
            return new Decision(DecisionType.CANCEL, null);
        }

        return new Decision(DecisionType.SUPPLEMENT, trimmed);
    }
}
