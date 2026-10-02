package com.paicode.renderer.entity;

import java.util.List;

/**
 * @Author beaker
 * @Date 2026/10/2 04:09
 * @Description diff hunk
 */
public record Hunk(int beforeStart, int beforeCount, int afterStart, int afterCount, List<DiffOp> ops) {

    public String header() {
        return "@@ -" + (beforeStart + 1) + "," + beforeCount
                + " +" + (afterStart + 1) + "," + afterCount + " @@";
    }
}
