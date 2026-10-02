package com.paicode.renderer.service.inline;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * @Author beaker
 * @Date 2026/10/2 01:46
 * @Description 活动注册表 (FoldableBlock)
 *
 * 维护一个双端队列, 新注册的块成为 "队尾活跃块", 之前的块被 freeze（因为新输出意味着它们已经向上滚走，无法再覆盖重绘）。
 */
public class BlockRegistry {

    private final Deque<FoldableBlock> blocks = new ArrayDeque<>();

    /** 注册新块；之前的所有块进入 frozen 状态。 */
    public synchronized void register(FoldableBlock block) {
        for (FoldableBlock existing : blocks) {
            existing.freeze();
        }
        blocks.addLast(block);
    }

    /** Toggle 队尾活跃块（即最近一次 register 的块）。返回是否生效。 */
    public synchronized boolean toggleLast() {
        FoldableBlock last = blocks.peekLast();
        if (last == null) {
            return false;
        }
        return last.toggle();
    }

    /** 清空注册表（如 /clear 时）。 */
    public synchronized void clear() {
        blocks.clear();
    }

    /** 当前注册数量（含 frozen）。 */
    public synchronized int size() {
        return blocks.size();
    }
}
