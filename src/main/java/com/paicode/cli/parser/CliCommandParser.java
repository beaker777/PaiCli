package com.paicode.cli.parser;

import com.paicode.cli.parser.DTO.ParsedCommand;
import com.paicode.cli.parser.constant.CommandType;

/**
 * @Author beaker
 * @Date 2026/9/12 01:04
 * @Description
 */
public class CliCommandParser {

    public static ParsedCommand parse(String input) {
        if (input == null) {
            return ParsedCommand.none();
        }

        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return ParsedCommand.none();
        }

        if (trimmed.equalsIgnoreCase("/exit")
                || trimmed.equalsIgnoreCase("/quit")
                || trimmed.equalsIgnoreCase("exit")
                || trimmed.equalsIgnoreCase("quit")) {
            return new ParsedCommand(CommandType.EXIT, null);
        }

        if (trimmed.equalsIgnoreCase("clear")) {
            return new ParsedCommand(CommandType.CLEAR, null);
        }

        if (trimmed.equalsIgnoreCase("/plan")) {
            return new ParsedCommand(CommandType.SWITCH_PLAN, null);
        }

        if (trimmed.regionMatches(true, 0, "/plan ", 0, 6)) {
            return new ParsedCommand(CommandType.SWITCH_PLAN, trimmed.substring(6).trim());
        }

        if (trimmed.equalsIgnoreCase("/memory") || trimmed.equalsIgnoreCase("/mem")) {
            return new ParsedCommand(CommandType.MEMORY_STATUS, null);
        }

        if (trimmed.regionMatches(true, 0, "/save", 0, 6)) {
            return new ParsedCommand(CommandType.MEMORY_SAVE, trimmed.substring(6).trim());
        }

        if (trimmed.regionMatches(true, 0, "/index", 0, 7)) {
            return new ParsedCommand(CommandType.INDEX_CODE, trimmed.substring(7).trim());
        }

        if (trimmed.regionMatches(true,0, "/search", 0, 8)) {
            return new ParsedCommand(CommandType.SEARCH_CODE, trimmed.substring(8).trim());
        }

        if (trimmed.regionMatches(true, 0, "/graph", 0, 7)) {
            return new ParsedCommand(CommandType.GRAPH_QUERY, trimmed.substring(7).trim());
        }

        return ParsedCommand.none();
    }
}
