package com.paicode.runtime;

import java.util.concurrent.atomic.AtomicReference;

/**
 * @Author beaker
 * @Date 2026/9/26 19:15
 * @Description 取消上下文
 */
public class CancellationContext {

    private final static AtomicReference<CancellationToken> CURRENT = new AtomicReference<>();
    private final static InheritableThreadLocal<CancellationToken> LOCAL = new InheritableThreadLocal<>();

    public static CancellationToken startRun() {
        CancellationToken token = new CancellationToken();
        CURRENT.set(token);
        LOCAL.set(token);

        return token;
    }

    public static CancellationToken current() {
        CancellationToken token = LOCAL.get();
        return token == null ? CURRENT.get() : token;
    }

    public static boolean isCancelled() {
        CancellationToken token = current();
        return token != null && token.isCancelled();
    }

    public static void clear(CancellationToken token) {
        if (LOCAL.get() == token) {
            LOCAL.remove();
        }
        CURRENT.compareAndSet(token, null);
    }
}
