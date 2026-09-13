package com.paicode.cli.parser.DTO;

import com.paicode.cli.parser.constant.DecisionType;

/**
 * @Author beaker
 * @Date 2026/9/13 16:21
 * @Description 决策
 */
public record Decision(DecisionType type, String feedback) {
}
