package com.paicode.tui.service.manage;

import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.gui2.dialogs.ListSelectDialogBuilder;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogBuilder;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.paicode.hitl.entity.ApprovalRequest;
import com.paicode.hitl.entity.ApprovalResult;
import com.paicode.llm.entity.ToolCall;
import com.paicode.renderer.entity.StatusInfo;
import com.paicode.renderer.service.manage.Renderer;
import com.paicode.tui.service.pane.component.CenterPane;
import com.paicode.tui.service.pane.component.StatusPane;
import org.jetbrains.annotations.NotNull;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @Author beaker
 * @Date 2026/10/1 23:33
 * @Description Lanterna 全屏 TUI 形态的渲染器
 */
public class LanternaRenderer implements Renderer {

    private final LanternaWindow window;
    private final CenterPane centerPane;
    private final StatusPane statusPane;
    private final WindowBasedTextGUI gui;
    private final PrintStream stream;
    private volatile boolean closed;

    public LanternaRenderer(LanternaWindow window) {
        this.window = Objects.requireNonNull(window);
        this.centerPane = window.getRootPane().getCenterPane();
        this.statusPane = window.getRootPane().getStatusPane();
        this.gui = window.getGui();
        this.stream = new PrintStream(new CenterPaneSink(), true, StandardCharsets.UTF_8);
    }

    @Override
    public void start() {
        // GUI 主循环由 LanternaWindow.start() 驱动，本方法 no-op。
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            window.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public PrintStream stream() {
        return stream;
    }

    @Override
    public void appendToolCalls(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }

        for (ToolCall tc : toolCalls) {
            String name = tc.function().name();
            String args = tc.function().arguments();
            window.runOnGuiThread(() -> centerPane.appendToolCall(name, args));
        }
    }

    @Override
    public void appendDiff(String filePath, String before, String after) {
        StringBuilder sb = new StringBuilder();
        sb.append("📝 ").append(filePath == null ? "(unnamed)" : filePath).append("\n");

        if (before == null) {
            sb.append("(新建文件，").append(after == null ? 0 : after.length()).append(" 字符)");
        } else if (after == null) {
            sb.append("(删除文件)");
        } else {
            sb.append(before.length()).append(" → ").append(after.length()).append(" 字符");
        }
        String text = sb.toString();
        window.runOnGuiThread(() -> centerPane.appendSystemMessage(text));
    }

    @Override
    public void updateStatus(StatusInfo status) {
        // StatusPane 当前没有公开的 setStatus 接口；保留 hook 给后续增强。
    }

    @Override
    public ApprovalResult promptApproval(ApprovalRequest request) {
        AtomicReference<ApprovalResult> result = new AtomicReference<>();
        Object lock = new Object();

        // 向 GUI 线程提交任务
        window.runOnGuiThread(() -> {
            ApprovalResult r = doShowApprovalDialog(request);
            synchronized (lock) {
                result.set(r);
                lock.notifyAll();
            }
        });

        // 主线程等待结果
        synchronized (lock) {
            while (result.get() == null) {
                try {
                    lock.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ApprovalResult.reject("审批被中断");
                }
            }
        }
        return result.get();
    }

    private ApprovalResult doShowApprovalDialog(ApprovalRequest request) {
        String title = "⚠️ HITL 审批: " + request.toolName();
        String body = request.toDisplayText() + "\n\n[Yes] 批准  [No] 拒绝  [Cancel] 跳过";
        MessageDialogButton button = new MessageDialogBuilder()
                .setTitle(title)
                .setText(body)
                .addButton(MessageDialogButton.Yes)
                .addButton(MessageDialogButton.No)
                .addButton(MessageDialogButton.Cancel)
                .build()
                .showDialog(gui);
        if (button == null) {
            return ApprovalResult.skip();
        }
        return switch (button) {
            case Yes -> ApprovalResult.approve();
            case No -> ApprovalResult.reject("Lanterna 模态框拒绝");
            default -> ApprovalResult.skip();
        };
    }

    @Override
    public int openPalette(String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return -1;
        }

        AtomicReference<Integer> result = new AtomicReference<>();
        Object lock = new Object();

        // 向 GUI 线程提交任务
        window.runOnGuiThread(() -> {
            ListSelectDialogBuilder<String> builder = new ListSelectDialogBuilder<String>()
                    .setTitle(title == null ? "选择" : title)
                    .setDescription("方向键 + Enter 选择");
            for (String item : items) {
                builder.addListItem(item);
            }
            String selected = builder.build().showDialog(gui);
            int idx = selected == null ? -1 : items.indexOf(selected);

            synchronized (lock) {
                result.set(idx);
                lock.notifyAll();
            }
        });

        // 主线程等待结果
        synchronized (lock) {
            while (result.get() == null) {
                try {
                    lock.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
        }
        return result.get();
    }

    public LanternaWindow window() {
        return window;
    }

    /**
     * 将 Map 风格的面板渲染到 CenterPanel
     */
    public void appendKeyValueBlock(String header, Map<String, String> kv) {
        StringBuilder sb = new StringBuilder(header).append("\n");
        for (var e : kv.entrySet()) {
            sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        }
        String text = sb.toString();
        window.runOnGuiThread(() -> centerPane.appendSystemMessage(text));
    }

    /** 把 stream() 的字节缓冲到 CenterPane（按行 flush）。 */
    private final class CenterPaneSink extends OutputStream {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public synchronized void write(int b) {
            char ch = (char) (b & 0xFF);
            if (ch == '\n') {
                String line = buffer.toString();
                buffer.setLength(0);
                window.runOnGuiThread(() -> centerPane.appendAssistantOutput(line));
            } else {
                buffer.append(ch);
            }
        }

        @Override
        public synchronized void write(@NotNull byte[] b, int off, int len) {
            for (int i = off; i < off + len; i++) {
                write(b[i] & 0xFF);
            }
        }

        @Override
        public synchronized void flush() {
            if (!buffer.isEmpty()) {
                String line = buffer.toString();
                buffer.setLength(0);
                window.runOnGuiThread(() -> centerPane.appendAssistantOutput(line));
            }
        }
    }
}
