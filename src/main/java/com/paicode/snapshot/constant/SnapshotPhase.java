package com.paicode.snapshot.constant;

/**
 * @Author beaker
 * @Date 2026/10/3 19:41
 * @Description 快照阶段
 */
public enum SnapshotPhase {

    PRE_TURN("pre-turn"),
    POST_TURN("post-turn"),
    PRE_RESTORE("pre-store");

    private final String label;

    SnapshotPhase(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
