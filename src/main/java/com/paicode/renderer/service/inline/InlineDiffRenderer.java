package com.paicode.renderer.service.inline;

import com.paicode.renderer.constant.OpType;
import com.paicode.renderer.entity.DiffOp;
import com.paicode.renderer.entity.Hunk;
import com.paicode.utils.AnsiStyle;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @Author beaker
 * @Date 2026/10/2 03:54
 * @Description 行内 diff 渲染: 红色删除, 绿色添加, 青色 hunk header
 * 使用朴素 LCS 计算 diff O(nm)
 */
public class InlineDiffRenderer {

    private static final String GREEN = "[32m";
    private static final String RED = "[31m";
    private static final String CYAN = "[36m";
    private static final String RESET = "[0m";
    private static final int CONTEXT_LINES = 2;

    private final PrintStream out;

    public InlineDiffRenderer(PrintStream out) {
        this.out = out;
    }

    public void render(String filePath, String before, String after) {
        out.println();
        out.println(AnsiStyle.heading("📝 " + (filePath == null ? "(unnamed)" : filePath)));

        if (before == null && after == null) {
            out.println(AnsiStyle.subtle("  (空 diff)"));
            return;
        }
        // 新建文件
        if (before == null) {
            renderNewFile(after);
            return;
        }
        // 删除文件
        if (after == null) {
            renderDeleteFile(before);
            return;
        }
        // 文件不变
        if (Objects.equals(before, after)) {
            out.println(AnsiStyle.subtle("  (内容未变)"));
            return;
        }

        renderUnifiedDiff(before, after);
    }

    private void renderNewFile(String after) {
        String[] lines = after.split("\n", -1);
        out.println(CYAN + "@@ -0,0 +1," + lines.length + " @@" + RESET);
        for (String line : lines) {
            if (line.isEmpty()) continue;
            out.println(GREEN + "+" + line + RESET);
        }
    }

    private void renderDeleteFile(String before) {
        String[] lines = before.split("\n", -1);
        out.println(CYAN + "@@ -1," + lines.length + " +0,0 @@" + RESET);
        for (String line : lines) {
            if (line.isEmpty()) continue;
            out.println(RED + "-" + line + RESET);
        }
    }

    private void renderUnifiedDiff(String before, String after) {
        String[] beforeLines = before.split("\n", -1);
        String[] afterLines = after.split("\n", -1);

        List<DiffOp> ops = computeDiff(beforeLines, afterLines);
        List<Hunk> hunks = groupIntoHunks(ops, beforeLines, afterLines);
        for (Hunk hunk : hunks) {
            out.println(CYAN + hunk.header() + RESET);
            for (DiffOp op : hunk.ops()) {
                switch (op.type()) {
                    case EQUAL -> out.println(" " + op.text());
                    case ADD -> out.println(GREEN + "+" + op.text() + RESET);
                    case DELETE -> out.println(RED + "-" + op.text() + RESET);
                }
            }
        }
    }

    static List<DiffOp> computeDiff(String[] before, String[] after) {
        int n = before.length;
        int m = after.length;
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (before[i].equals(after[j])) {
                    dp[i][j] = dp[i + 1][j + 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }

        List<DiffOp> ops = new ArrayList<>();
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (before[i].equals(after[j])) {
                ops.add(new DiffOp(OpType.EQUAL, before[i], i, j));
                i++; j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                ops.add(new DiffOp(OpType.DELETE, before[i], i, -1));
                i++;
            } else {
                ops.add(new DiffOp(OpType.ADD, after[j], -1, j));
                j++;
            }
        }
        while (i < n) {
            ops.add(new DiffOp(OpType.DELETE, before[i], i, -1));
            i++;
        }
        while (j < m) {
            ops.add(new DiffOp(OpType.ADD, after[j], -1, j));
            j++;
        }
        return ops;
    }

    /** 把连续的 EQUAL 段切成 hunks，每个 hunk 包含 CONTEXT_LINES 上下行。 */
    private static List<Hunk> groupIntoHunks(List<DiffOp> ops, String[] before, String[] after) {
        List<Hunk> hunks = new ArrayList<>();
        int idx = 0;
        while (idx < ops.size()) {
            // 找到下一个非 EQUAL 操作
            while (idx < ops.size() && ops.get(idx).type() == OpType.EQUAL) {
                idx++;
            }
            if (idx >= ops.size()) break;

            // hunk 从操作的前 2 行开始, 连续遇到 4 行 EQUAL 后结束
            int hunkStart = Math.max(0, idx - CONTEXT_LINES);
            int hunkEnd = idx;
            int equalRun = 0;
            while (hunkEnd < ops.size()) {
                DiffOp op = ops.get(hunkEnd);
                if (op.type() == OpType.EQUAL) {
                    equalRun++;
                    if (equalRun >= 2 * CONTEXT_LINES) {
                        break;
                    }
                } else {
                    equalRun = 0;
                }
                hunkEnd++;
            }
            int hunkClose = Math.min(ops.size(), hunkEnd + CONTEXT_LINES - equalRun);

            // 收集本 hunk 的 ops
            List<DiffOp> hunkOps = new ArrayList<>(ops.subList(hunkStart, hunkClose));
            int beforeStart = firstBeforeIndex(hunkOps);
            int afterStart = firstAfterIndex(hunkOps);
            int beforeCount = (int) hunkOps.stream().filter(o -> o.type() != OpType.ADD).count();
            int afterCount = (int) hunkOps.stream().filter(o -> o.type() != OpType.DELETE).count();
            hunks.add(new Hunk(beforeStart, beforeCount, afterStart, afterCount, hunkOps));
            idx = hunkClose;
        }
        return hunks;
    }

    private static int firstBeforeIndex(List<DiffOp> ops) {
        for (DiffOp op : ops) {
            if (op.beforeIndex() >= 0) return op.beforeIndex();
        }
        return 0;
    }

    private static int firstAfterIndex(List<DiffOp> ops) {
        for (DiffOp op : ops) {
            if (op.afterIndex() >= 0) return op.afterIndex();
        }
        return 0;
    }
}
