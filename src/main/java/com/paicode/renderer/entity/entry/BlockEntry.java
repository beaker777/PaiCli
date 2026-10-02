package com.paicode.renderer.entity.entry;

import com.paicode.renderer.service.inline.FoldableBlock;

/**
 * @Author beaker
 * @Date 2026/10/3 06:09
 * @Description ToolCall 块条目
 */
public record BlockEntry(FoldableBlock block) implements TranscriptEntry {

    @Override
    public String render() {
        return String.join(System.lineSeparator(), block.currentLines()) + System.lineSeparator();
    }
}
