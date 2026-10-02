package com.paicode.tui.constant;

/**
 * @Author beaker
 * @Date 2026/10/2 00:33
 * @Description 运行模式
 */
public enum RunMode {

    REACT("ReAct"),
    PLAN("Plan"),
    TEAM("Team");

    public final String label;

    RunMode(String label) {
        this.label = label;
    }
}
