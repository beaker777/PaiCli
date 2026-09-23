package com.paicode.llm.service.stream.impl;

import com.paicode.llm.entity.StreamState;
import com.paicode.llm.service.stream.StreamListener;
import com.paicode.utils.AnsiStyle;
import com.paicode.utils.TerminalMarkdownRenderer;

import java.io.PrintStream;

/**
 * @Author beaker
 * @Date 2026/9/20 20:31
 * @Description 任务流式输出渲染器
 */
public class TaskStreamRender implements StreamListener {

    private final String taskId;
    private final StreamState streamState;
    private final PrintStream out;
    private final StringBuilder pendingReasoning = new StringBuilder();
    private final StringBuilder lateReasoning = new StringBuilder();
    private TerminalMarkdownRenderer reasoningRenderer;
    private TerminalMarkdownRenderer contentRenderer;
    private boolean reasoningStarted;
    private boolean contentStarted;
    private boolean streamedOutput;

    public TaskStreamRender(String taskId, StreamState streamState, PrintStream out) {
        this.taskId = taskId;
        this.streamState = streamState;
        this.out = out;
    }

    @Override
    public void onReasoningDelta(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }

        if (contentStarted) {
            lateReasoning.append(delta);
            return;
        }
        if (!reasoningStarted) {
            pendingReasoning.append(delta);
            if (pendingReasoning.toString().isBlank()) {
                return;
            }

            out.println(AnsiStyle.heading("🧠 任务思考 [" + taskId + "]"));
            reasoningRenderer = new TerminalMarkdownRenderer(out);
            reasoningRenderer.append(pendingReasoning.toString());
            pendingReasoning.setLength(0);
            reasoningStarted = true;
            streamedOutput = true;
            streamState.markStreamed();
        } else {
            reasoningRenderer.append(delta);
        }
        out.flush();
    }

    @Override
    public void onContentDelta(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }

        if (!contentStarted) {
            if (reasoningStarted && reasoningRenderer != null) {
                reasoningRenderer.finish();
                out.println();
            } else if (!pendingReasoning.isEmpty() && !pendingReasoning.toString().isBlank()) {
                out.println(AnsiStyle.heading("🧠 任务思考 [" + taskId + "]"));
                TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(out);
                r.append(pendingReasoning.toString());
                r.finish();
                out.println();
                pendingReasoning.setLength(0);
                reasoningStarted = true;
            }

            out.println(AnsiStyle.section("🤖 任务输出 [" + taskId + "]"));
            contentRenderer = new TerminalMarkdownRenderer(out);
            contentStarted = true;
            streamedOutput = true;
            streamState.markStreamed();
        }
        contentRenderer.append(delta);
        out.flush();
    }

    // 在两次 iteration 之间调用
    public synchronized void resetBetweenIterations() {
        if (reasoningRenderer != null) {
            reasoningRenderer.finish();
            reasoningRenderer = null;
        }
        if (contentRenderer != null) {
            contentRenderer.finish();
            contentRenderer = null;
        }

        flushLateReasoning();
        pendingReasoning.setLength(0);
        reasoningStarted = false;
        contentStarted = false;
        if (streamedOutput) {
            out.println();
        }
    }

    public synchronized void finish() {
        if (streamedOutput) {
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
            }

            flushLateReasoning();
            out.println("\n");
        }
    }

    private void flushLateReasoning() {
        String late = lateReasoning.toString().trim();
        if (late.isEmpty()) {
            lateReasoning.setLength(0);
            return;
        }
        out.println();
        out.println(AnsiStyle.heading("🧠 补充思考 [" + taskId + "]"));
        TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(out);
        renderer.append(late);
        renderer.finish();
        lateReasoning.setLength(0);
    }

    public synchronized boolean hasStreamedOutput() {
        return streamedOutput;
    }
}
