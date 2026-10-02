package com.paicode.renderer.entity.entry;

/**
 * @Author beaker
 * @Date 2026/10/3 06:08
 * @Description 文本条目
 */
public record TextEntry(String text) implements TranscriptEntry {

    @Override
    public String render() {
        return text;
    }
}
