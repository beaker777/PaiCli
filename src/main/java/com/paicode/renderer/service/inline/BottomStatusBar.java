package com.paicode.renderer.service.inline;

import com.paicode.renderer.entity.StatusInfo;
import org.jline.terminal.Terminal;

import java.io.PrintStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @Author beaker
 * @Date 2026/10/2 01:52
 * @Description 基于 DECSTBM 的底部状态栏
 * 启动时把可滚动区域限制在前 n - 1 行, 后台线程每 200ms 刷新一次状态栏
 */
public class BottomStatusBar implements AutoCloseable {

    private static final long REDRAW_PERIOD_MS = 200L;

    private final PrintStream out;
    private final Terminal terminal;
    private final ScheduledExecutorService scheduler;
    private volatile StatusInfo current;
    private volatile boolean started;
    private volatile boolean closed;

    public BottomStatusBar(Terminal terminal) {
        this.terminal = terminal;
        this.out = System.out;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "paicode-statusbar");
            t.setDaemon(true);
            return t;
        });
    }

    /** 安装滚动区域 + 启动后台重绘线程。重复调用无副作用。 */
    public synchronized void start() {
        if (started || closed) {
            return;
        }

        int rows = TerminalCapabilities.safeSize(terminal).getRows();
        synchronized (out) {
            out.print(AnsiSeq.setScrollRegion(1, rows - 1));
            out.print(AnsiSeq.moveCursor(rows - 1, 1));
            out.flush();
        }
        scheduler.scheduleAtFixedRate(this::redraw,
                REDRAW_PERIOD_MS, REDRAW_PERIOD_MS, TimeUnit.MILLISECONDS);
        started = true;
    }

    /** 立即触发一次重绘（不等节流间隔）。 */
    public void flushNow() {
        if (started && !closed) {
            redraw();
        }
    }

    private void redraw() {
        StatusInfo info = current;
        if (info == null || closed || !started) {
            return;
        }

        var size = TerminalCapabilities.safeSize(terminal);
        int rows = size.getRows();
        int cols = size.getColumns();
        synchronized (out) {
            out.print(AnsiSeq.SAVE_CURSOR);
            out.print(AnsiSeq.moveCursor(rows, 1));
            out.print(AnsiSeq.CLEAR_LINE);
            out.print(AnsiSeq.REVERSE_ON);
            out.print(formatStatusLine(info, cols));
            out.print(AnsiSeq.REVERSE_OFF);
            out.print(AnsiSeq.RESTORE_CURSOR);
            out.flush();
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;
        scheduler.shutdownNow();
        if (!started) {
            return;
        }
        int rows = TerminalCapabilities.safeSize(terminal).getRows();
        synchronized (out) {
            out.print(AnsiSeq.RESET_SCROLL_REGION);
            out.print(AnsiSeq.moveCursor(rows, 1));
            out.print(AnsiSeq.CLEAR_LINE);
            out.flush();
        }
    }

    public void update(StatusInfo info) {
        this.current = info;
    }

    private static String formatStatusLine(StatusInfo info, int cols) {
        String model = info.model() == null ? "—" : info.model();
        String tokens = formatTokens(info.totalTokens()) + "/" + formatTokens(info.contextWindow());
        String hitl = info.hitlEnabled() ? "HITL ON" : "HITL OFF";
        StringBuilder sb = new StringBuilder(" ").append(model)
                .append(" │ ").append(tokens)
                .append(" │ ").append(hitl);
        if (info.elapsedMillis() > 0) {
            sb.append(" │ ").append(formatElapsed(info.elapsedMillis()));
        }
        sb.append(" ");
        // 简单按字符数 pad / 截断（状态栏内容预期都是 ASCII）
        int len = sb.length();
        if (len < cols) {
            sb.append(" ".repeat(cols - len));
        } else if (len > cols) {
            sb.setLength(cols);
        }
        return sb.toString();
    }

    private static String formatTokens(long t) {
        if (t >= 1_000_000) {
            return String.format("%.1fM", t / 1_000_000.0);
        }
        if (t >= 1_000) {
            return String.format("%.1fk", t / 1_000.0);
        }
        return String.valueOf(t);
    }

    private static String formatElapsed(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        }
        return String.format("%.1fs", ms / 1000.0);
    }
}
