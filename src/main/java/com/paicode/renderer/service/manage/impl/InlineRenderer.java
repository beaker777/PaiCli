package com.paicode.renderer.service.manage.impl;

import com.paicode.hitl.entity.ApprovalRequest;
import com.paicode.hitl.entity.ApprovalResult;
import com.paicode.llm.entity.ToolCall;
import com.paicode.renderer.entity.StatusInfo;
import com.paicode.renderer.service.inline.*;
import com.paicode.renderer.service.manage.Renderer;
import lombok.Getter;
import org.jline.terminal.Terminal;

import java.io.PrintStream;
import java.util.List;

/**
 * @Author beaker
 * @Date 2026/10/2 14:22
 * @Description Inline 流式渲染器
 */
@Getter
public class InlineRenderer implements Renderer {

    private final Terminal terminal;
    private final PlainRenderer fallback;
    private final BottomStatusBar statusBar;
    private final BlockRegistry blockRegistry;
    private final ToolCallRenderer toolCallRenderer;
    private volatile boolean started;
    private volatile boolean closed;

    public InlineRenderer(Terminal terminal) {
        this.terminal = terminal;
        this.fallback = new PlainRenderer();
        this.statusBar = TerminalCapabilities.supportsScrollRegion(terminal)
                ? new BottomStatusBar(terminal)
                : null;
        this.blockRegistry = new BlockRegistry();
        this.toolCallRenderer = new ToolCallRenderer(System.out, blockRegistry);
    }

    @Override
    public void start() {
        if (started || closed) {
            return;
        }
        if (statusBar != null) {
            statusBar.start();
        }
        started = true;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (statusBar != null) {
            statusBar.close();
        }
        fallback.close();
    }

    @Override
    public PrintStream stream() {
        return fallback.stream();
    }

    @Override
    public void appendToolCalls(List<ToolCall> toolCalls) {
        toolCallRenderer.render(toolCalls);
    }

    @Override
    public void appendDiff(String filePath, String before, String after) {
        new InlineDiffRenderer(System.out).render(filePath, before, after);
    }

    @Override
    public void updateStatus(StatusInfo status) {
        if (statusBar != null) {
            statusBar.update(status);
        }
    }

    @Override
    public ApprovalResult promptApproval(ApprovalRequest request) {
        if (terminal == null) {
            return fallback.promptApproval(request);
        }
        return new InlineApprovalPrompter(System.out, terminal).prompt(request);
    }

    @Override
    public int openPalette(String title, List<String> items) {
        if (terminal == null) {
            return fallback.openPalette(title, items);
        }
        return new SlashPalette(System.out, terminal).open(title, items);
    }
}
