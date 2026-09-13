package com.paicode.cli.parser.DTO;

import com.paicode.cli.parser.constant.CommandType;

/**
 * @Author beaker
 * @Date 2026/9/12 01:06
 * @Description 转换后的命令
 */
public record ParsedCommand(CommandType type, String payload) {

    public static ParsedCommand none() {
        return new ParsedCommand(CommandType.NONE, null);
    }
}
