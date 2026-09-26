package com.paicode.mcp.service.mention;

import com.paicode.mcp.entity.resource.McpResourceDescription;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;
import java.util.function.Supplier;

/**
 * @Author beaker
 * @Date 2026/9/26 16:21
 * @Description 当用户输入 Tab 时自动补全以 @ 开头的内容
 */
public class AtMentionCompleter implements Completer {

    private final Supplier<List<McpResourceDescription>> resourceSupplier;

    public AtMentionCompleter(Supplier<List<McpResourceDescription>> resourceSupplier) {
        this.resourceSupplier = resourceSupplier;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        if (line == null || candidates == null) {
            return;
        }

        String word = line.word() == null ? "" : line.word();
        if (!word.startsWith("@")) {
            return;
        }

        String prefix = word.substring(1);
        for (McpResourceDescription resource : resourceSupplier.get()) {
            String value = "@" + resource.serverName() + ":" + resource.uri();
            if (!prefix.isBlank() && !value.substring(1).startsWith(prefix)) {
                continue;
            }

            String description = resource.description() == null || resource.description().isBlank() ?
                    resource.mimeType() : resource.description();
            candidates.add(new Candidate(
                    value,
                    value,
                    resource.displayName(),
                    description,
                    null,
                    null,
                    true
            ));
        }
    }
}
