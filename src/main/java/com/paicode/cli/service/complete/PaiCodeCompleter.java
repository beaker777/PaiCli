package com.paicode.cli.service.complete;

import com.paicode.cli.entity.SlashCommandHint;
import com.paicode.mcp.entity.resource.McpResourceDescription;
import com.paicode.mcp.service.mention.AtMentionCompleter;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;
import java.util.function.Supplier;

import static com.paicode.cli.entity.SlashCommandHint.slashCommandHints;

/**
 * @Author beaker
 * @Date 2026/9/29 22:41
 * @Description 自动补全
 */
public class PaiCodeCompleter implements Completer {

    private final Supplier<List<McpResourceDescription>> resourceSupplier;

    public PaiCodeCompleter(Supplier<List<McpResourceDescription>> resourceSupplier) {
        this.resourceSupplier = resourceSupplier;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        if (line == null || candidates == null) {
            return;
        }

        // 输入 "/" 开头, 补全命令
        String input = line.line() == null ? "" : line.line();
        if (input.startsWith("/")) {
            completeSlashCommand(line, candidates);
            return;
        }

        // 正常补全内容
        new AtMentionCompleter(resourceSupplier).complete(reader, line, candidates);
    }

    private void completeSlashCommand(ParsedLine line, List<Candidate> candidates) {
        String input = line.line();
        int cursor = Math.max(0, Math.min(line.cursor(), input.length()));
        String prefix = input.substring(0, cursor);
        String word = line.word() == null ? "" : line.word();
        int replacementStart = Math.max(0, prefix.length() - word.length());

        for (SlashCommandHint hint : slashCommandHints()) {
            String command = hint.insertText();
            if (!command.startsWith(prefix)) {
                continue;
            }

            String value = command.substring(Math.min(replacementStart, command.length()));
            candidates.add(new Candidate(
                    value,
                    hint.display(),
                    "PaiCode 命令",
                    hint.description(),
                    null,
                    null,
                    true
            ));
        }
    }
}
