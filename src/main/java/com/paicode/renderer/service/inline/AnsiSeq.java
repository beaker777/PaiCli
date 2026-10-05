package com.paicode.renderer.service.inline;

/**
 * @Author beaker
 * @Date 2026/10/2 01:29
 * @Description 原生 ANSI 控制序列常量与工具方法
 */
public class AnsiSeq {

    public static final String ESC = "";

    // === 光标 ===
    /** 保存光标位置。 */
    public static final String SAVE_CURSOR = ESC + "7";
    /** 恢复光标位置。 */
    public static final String RESTORE_CURSOR = ESC + "8";
    /** 隐藏光标。 */
    public static final String HIDE_CURSOR = ESC + "[?25l";
    /** 显示光标。 */
    public static final String SHOW_CURSOR = ESC + "[?25h";

    // === 清除 ===
    /** 清除当前行。 */
    public static final String CLEAR_LINE = ESC + "[2K";
    /** 清除从光标到行尾。 */
    public static final String CLEAR_TO_EOL = ESC + "[K";
    /** 清除从光标到屏幕末尾。 */
    public static final String CLEAR_TO_EOS = ESC + "[J";

    // === 滚动区域 ===
    /** 重置滚动区域为整个屏幕。 */
    public static final String RESET_SCROLL_REGION = ESC + "[r";

    // === 文本样式 ===
    /** 开启反白, 即交换文本和背景的颜色 */
    public static final String REVERSE_ON = ESC + "[7m";
    /** 关闭反白, 即交换文本和背景的颜色 */
    public static final String REVERSE_OFF = ESC + "[27m";
    public static final String RESET = ESC + "[0m";
    public static final String BOLD = ESC + "[1m";
    /** 开启暗色模式 */
    public static final String DIM = ESC + "[2m";

    private AnsiSeq() {
    }

    /** 设置滚动区域为第 {@code top}..{@code bottom} 行（1-based，闭区间）。 */
    public static String setScrollRegion(int top, int bottom) {
        return ESC + "[" + top + ";" + bottom + "r";
    }

    /** 移动光标到 ({@code row}, {@code col})（1-based）。 */
    public static String moveCursor(int row, int col) {
        return ESC + "[" + row + ";" + col + "H";
    }

    /** 上移 {@code n} 行。 */
    public static String moveUp(int n) {
        return ESC + "[" + n + "A";
    }

    /** 下移 {@code n} 行。 */
    public static String moveDown(int n) {
        return ESC + "[" + n + "B";
    }
}
