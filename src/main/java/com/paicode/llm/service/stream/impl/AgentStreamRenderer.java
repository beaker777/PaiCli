package com.paicode.llm.service.stream.impl;

import com.paicode.llm.service.stream.StreamListener;
import com.paicode.utils.AnsiStyle;
import com.paicode.utils.TerminalMarkdownRenderer;

import java.io.PrintStream;

/**
 * @Author beaker
 * @Date 2026/9/19 21:33
 * @Description ReAct 流式输出渲染器
 */
public class AgentStreamRenderer implements StreamListener {

    private final StringBuilder pendingReasoning = new StringBuilder();
    private final StringBuilder lateReasoning = new StringBuilder();
    private TerminalMarkdownRenderer reasoningRenderer;
    private TerminalMarkdownRenderer contentRenderer;
    private boolean reasoningHeadingPrinted;
    private boolean reasoningStarted;
    private boolean contentStarted;
    private boolean streamedOutput;

    // renderer 传入的 stream
    private final PrintStream boundOut;

    public AgentStreamRenderer() {
        this.boundOut = null;
    }

    public AgentStreamRenderer(PrintStream out) {
        this.boundOut = out;
    }

    private  PrintStream out() {
        return boundOut != null ? boundOut : System.out;
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
            // 还未拥有实质输出内容, 继续等待
            if (pendingReasoning.toString().isBlank()) {
                return;
            }
            // 避免输出空标题, 等待完整的一行后再输出
            if (!containsLineBreak(pendingReasoning)) {
                return;
            }

            printReasoningHeadingIfNeeded();

            reasoningRenderer = new TerminalMarkdownRenderer(out());
            reasoningRenderer.append(pendingReasoning.toString());
            pendingReasoning.setLength(0);
            reasoningStarted = true;
            streamedOutput = true;
        } else {
            reasoningRenderer.append(delta);
        }

        out().flush();
    }

    @Override
    public void onContentDelta(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }

        if (!contentStarted) {
            if (reasoningStarted && reasoningRenderer != null) {
                reasoningRenderer.finish();
                out().println();
            } else if (!pendingReasoning.isEmpty() && !pendingReasoning.toString().isBlank()) {
                printReasoningHeadingIfNeeded();

                TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(out());
                r.append(pendingReasoning.toString());
                r.finish();
                out().println();
                pendingReasoning.setLength(0);
                reasoningStarted = true;
            }

            out().println("回复");
            contentRenderer = new TerminalMarkdownRenderer(out());
            contentStarted = true;
            streamedOutput = true;
        }

        contentRenderer.append(delta);
        out().flush();
    }

    public boolean hasStreamedOutput() {
        return streamedOutput;
    }

    /**
     * 在两次迭代之间调用, 避免出现上一轮的内容和下一轮错位的问题
     */
    public void resetBetweenTwoIterations() {
        if (reasoningRenderer != null) {
            reasoningRenderer.finish();
            reasoningRenderer = null;
        } else {
            flushPendingReasoning();
        }
        if (contentRenderer != null) {
            contentRenderer.finish();
            contentRenderer = null;
        }

        // 直接 flush late reasoning
        String late = lateReasoning.toString().trim();
        if (!late.isEmpty()) {
            out().println();
            out().println(AnsiStyle.heading("补充思考"));
            TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(out());
            r.append(late);
            r.finish();
            lateReasoning.setLength(0);
            streamedOutput = true;
        }

        pendingReasoning.setLength(0);
        reasoningStarted = false;
        contentStarted = false;
        if (streamedOutput) {
            out().println();
        }
    }

    public void finish() {
        if (reasoningRenderer != null) {
            reasoningRenderer.finish();
        } else {
            flushPendingReasoning();
        }
        if (contentRenderer != null) {
            contentRenderer.finish();
        }

        String late = lateReasoning.toString().trim();
        if (!late.isEmpty()) {
            out().println();
            out().println("补充思考");
            TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(out());
            r.append(late);
            r.finish();
            lateReasoning.setLength(0);
            streamedOutput = true;
        }
        if (streamedOutput) {
            out().println();
        }
    }

    private boolean containsLineBreak(CharSequence content) {
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '\n' || ch == '\r') {
                return true;
            }
        }
        return false;
    }

    private void printReasoningHeadingIfNeeded() {
        if (!reasoningHeadingPrinted) {
            out().println(AnsiStyle.heading("🧠 思考过程"));
            reasoningHeadingPrinted = true;
        }
    }

    private void flushPendingReasoning() {
        String pending = pendingReasoning.toString();
        if (pending.isBlank()) {
            pendingReasoning.setLength(0);
            return;
        }

        printReasoningHeadingIfNeeded();
        TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(out());
        renderer.append(pending);
        renderer.finish();
        pendingReasoning.setLength(0);
        streamedOutput = true;
    }
}
