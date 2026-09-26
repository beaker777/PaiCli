package com.paicode.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @Author beaker
 * @Date 2026/9/26 19:16
 * @Description 取消条目
 */
public class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }
}
