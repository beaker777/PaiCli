package com.paicode.cli.entity;

/**
 * @Author beaker
 * @Date 2026/9/13 16:41
 * @Description 提示词
 */
public record PromptInput(String text, boolean canceled) {

    public static PromptInput submitted(String text) {
        return new PromptInput(text, false);
    }

    public static PromptInput canceledInput() {
        return new PromptInput("", true);
    }
}
