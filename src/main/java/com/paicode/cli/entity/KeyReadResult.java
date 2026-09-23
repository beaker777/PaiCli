package com.paicode.cli.entity;

/**
 * @Author beaker
 * @Date 2026/9/20 22:35
 * @Description 按键识别结果
 */
public record KeyReadResult(Integer key, boolean ignoredControlSequence) {

    public static KeyReadResult keyPressed(int key) {
        return new KeyReadResult(key, false);
    }

    public static KeyReadResult ignoredSequence() {
        return new KeyReadResult(null, true);
    }

    public static  KeyReadResult unavailable() {
        return new KeyReadResult(null, false);
    }
}
