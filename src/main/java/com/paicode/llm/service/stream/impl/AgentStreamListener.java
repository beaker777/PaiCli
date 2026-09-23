package com.paicode.llm.service.stream.impl;

import com.paicode.llm.service.stream.StreamListener;
import com.paicode.utils.AnsiStyle;
import com.paicode.utils.TerminalMarkdownRenderer;

/**
 * @Author beaker
 * @Date 2026/9/19 21:33
 * @Description ReAct 流式输出监听器
 */
public class AgentStreamListener implements StreamListener {

    private final StringBuilder pendingReasoning = new StringBuilder();
    private final StringBuilder lateReasoning = new StringBuilder();
    private TerminalMarkdownRenderer reasoningRenderer;
    private TerminalMarkdownRenderer contentRenderer;
    private boolean reasoningHeadingPrinted;
    private boolean reasoningStarted;
    private boolean contentStarted;
    private boolean streamedOutput;

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
            if (!containsLineBreak(pendingReasoning)) {
                return;
            }

            printReasoningHeadingIfNeeded();

            reasoningRenderer = new TerminalMarkdownRenderer(System.out);
            reasoningRenderer.append(pendingReasoning.toString());
            pendingReasoning.setLength(0);
            reasoningStarted = true;
            streamedOutput = true;
        } else {
            reasoningRenderer.append(delta);
        }

        System.out.flush();
    }

    @Override
    public void onContentDelta(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }

        if (!contentStarted) {
            if (reasoningStarted && reasoningRenderer != null) {
                reasoningRenderer.finish();
                System.out.println();
            } else if (!pendingReasoning.isEmpty() && !pendingReasoning.toString().isBlank()) {
                printReasoningHeadingIfNeeded();

                TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(System.out);
                r.append(pendingReasoning.toString());
                r.finish();
                System.out.println();
                pendingReasoning.setLength(0);
                reasoningStarted = true;
            }

            System.out.println("回复");
            contentRenderer = new TerminalMarkdownRenderer(System.out);
            contentStarted = true;
            streamedOutput = true;
        }

        contentRenderer.append(delta);
        System.out.flush();
    }

    private boolean hasStreamedOutput() {
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
            System.out.println();
            System.out.println(AnsiStyle.heading("补充思考"));
            TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(System.out);
            r.append(late);
            r.finish();
            lateReasoning.setLength(0);
            streamedOutput = true;
        }

        pendingReasoning.setLength(0);
        reasoningStarted = false;
        contentStarted = false;
        if (streamedOutput) {
            System.out.println();
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
            System.out.println();
            System.out.println("补充思考");
            TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(System.out);
            r.append(late);
            r.finish();
            lateReasoning.setLength(0);
            streamedOutput = true;
        }
        if (streamedOutput) {
            System.out.println();
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
            System.out.println(AnsiStyle.heading("🧠 思考过程"));
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
        TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(System.out);
        renderer.append(pending);
        renderer.finish();
        pendingReasoning.setLength(0);
        streamedOutput = true;
    }
}
