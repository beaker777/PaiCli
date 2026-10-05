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
 * @Description 跟随输入的状态栏
 *
 * 状态栏紧贴在输入区下方, 而不是固定在屏幕底部, 避免出现大片空白
 */
public class BottomStatusBar implements AutoCloseable {

    private static final int STATUS_ROWS = 2;
    private static final int STATUS_GAP_ROWS = 1;
    private static final int ROWS_AFTER_PROMPT = STATUS_GAP_ROWS + STATUS_ROWS;

    private final Terminal terminal;
    private final PrintStream out;
    private volatile StatusInfo current;
    private volatile boolean started;
    private volatile boolean closed;
    private volatile boolean rendered;

    public BottomStatusBar(Terminal terminal) {
        this.terminal = terminal;
        this.out = System.out;
    }

    /** 安装滚动区域 + 启动后台重绘线程。重复调用无副作用。 */
    public synchronized void start() {
        if (started || closed) {
            return;
        }
        started = true;
    }

    /** 立即触发一次重绘（不等节流间隔）。 */
    public void flushNow() {
        // do nothing
    }

    /** 在即将读取输入时，把状态区画在 prompt 下方并把光标移回 prompt 行。 */
    public void prepareInputLine() {
        if (!started || closed) {
            return;
        }
        drawInlineStatus();
    }

    /** 输入提交后清掉 inline 状态区和它下面的空白，让下一段 transcript 紧跟输入行。 */
    public void finishInputLine() {
        if (!started || closed) {
            return;
        }
        clearInlineStatusAndGap();
    }

    private void drawInlineStatus() {
        StatusInfo info = current;
        if (info == null || closed || !started) {
            return;
        }

        int cols = TerminalCapabilities.safeSize(terminal).getColumns();
        synchronized (out) {
            out.print("\n".repeat(STATUS_GAP_ROWS + 1));
            out.print(AnsiSeq.REVERSE_ON);
            out.print(formatStatusLine(info, cols));
            out.print(AnsiSeq.RESET);

            out.print("\n");
            out.print(AnsiSeq.DIM);
            out.print(formatFooterLine(cols));
            out.print(AnsiSeq.RESET);
            out.print(AnsiSeq.moveUp(ROWS_AFTER_PROMPT));

            out.print("\r");
            out.flush();
            rendered = true;
        }
    }

    private void clearInlineStatusAndGap() {
        if (!rendered) {
            return;
        }
        synchronized (out) {
            out.print("\r");
            out.print(AnsiSeq.CLEAR_TO_EOS);
            out.flush();
            rendered = false;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        clearInlineStatusAndGap();
    }

    public void update(StatusInfo info) {
        this.current = mergeEnvironment(info, current);
    }

    private static String formatStatusLine(StatusInfo info, int cols) {
        String model = info.model() == null ? "—" : info.model();
        String phase = info.phase() == null || info.phase().isBlank() ? "idle" : info.phase();
        String tokens = formatTokens(info.totalTokens()) + "/" + formatTokens(info.contextWindow());
        String hitl = info.hitlEnabled() ? "HITL ON" : "HITL OFF";

        StringBuilder sb = new StringBuilder(" PaiCode");
        appendField(sb, phase);
        appendField(sb, info.mcpSummary());
        appendField(sb, info.skillSummary());
        appendField(sb, hitl);
        appendField(sb, model);
        appendField(sb, "ctx " + tokens);
        if (info.inputTokens() > 0 || info.outputTokens() > 0 || info.cachedInputTokens() > 0) {
            appendField(sb, "in " + formatTokens(info.inputTokens()) + " out " + formatTokens(info.outputTokens()));
            if (info.cachedInputTokens() > 0) {
                sb.append(" cache ").append(formatTokens(info.cachedInputTokens()));
            }
            if (info.estimatedCost() != null && !info.estimatedCost().isBlank()) {
                sb.append("  ").append(info.estimatedCost());
            }
        }
        if (info.elapsedMillis() > 0) {
            sb.append("  ").append(formatElapsed(info.elapsedMillis()));
        }
        return fitToColumns(sb.toString(), cols);
    }

    private static String formatFooterLine(int cols) {
        return fitToColumns(" Auto Model · / commands · @path/@image · Ctrl+O fold · ESC clear", cols);
    }

    private static StatusInfo mergeEnvironment(StatusInfo next, StatusInfo previous) {
        if (next == null || previous == null) {
            return next;
        }

        String mcp = next.mcpSummary() == null || next.mcpSummary().isBlank()
                ? previous.mcpSummary()
                : next.mcpSummary();
        String skill = next.skillSummary() == null || next.skillSummary().isBlank()
                ? previous.skillSummary()
                : next.skillSummary();
        if (mcp.equals(next.mcpSummary()) && skill.equals(next.skillSummary())) {
            return next;
        }
        return next.withEnvironment(mcp, skill);
    }

    private static void appendField(StringBuilder sb, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append("  ").append(value.trim());
    }

    private static String fitToColumns(String text, int cols) {
        if (cols <= 0) {
            return "";
        }
        String safe = text == null ? "" : text;
        if (safe.length() > cols) {
            return safe.substring(0, cols);
        }
        return safe + " ".repeat(cols - safe.length());
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
