package com.paicode.tool.entity;

import com.paicode.llm.entity.ContentPart;

import java.util.List;

/**
 * @Author beaker
 * @Date 2026/10/4 21:51
 * @Description 工具输出
 */
public record ToolOutput(String text, List<ContentPart> imageParts) {

    public ToolOutput {
        text = text == null ? "" : text;
        imageParts = imageParts == null ? List.of() : List.copyOf(imageParts);
    }

    public static ToolOutput text(String text) {
        return new ToolOutput(text, List.of());
    }

    public boolean hasImageParts() {
        return !imageParts.isEmpty();
    }
}
