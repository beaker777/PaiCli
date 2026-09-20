package com.paicode.llm.stream;

import com.paicode.utils.AnsiStyle;
import com.paicode.utils.TerminalMarkdownRenderer;

/**
 * @Author beaker
 * @Date 2026/9/19 21:33
 * @Description ReAct 流式输出监听器
 */
public class AgentStreamListener implements StreamListener {

    private TerminalMarkdownRenderer reasoningRenderer;
    private TerminalMarkdownRenderer contentRenderer;
    private boolean reasoningStarted;
    private boolean contentStarted;
    private boolean streamedOutput;

    @Override
    public void onReasoningDelta(String delta) {
        if (delta == null || delta.isBlank()) {
            return;
        }

        if (!reasoningStarted) {
            System.out.println(AnsiStyle.heading("思考过程"));
            reasoningRenderer = new TerminalMarkdownRenderer(System.out);
            reasoningStarted = true;
            streamedOutput = true;
        }

        reasoningRenderer.append(delta);
        System.out.flush();
    }

    @Override
    public void onContentDelta(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }

        if (!contentStarted) {
            if (!reasoningStarted) {
                System.out.println(AnsiStyle.section("🤖 最终结果"));
            } else {
                System.out.println();
                System.out.println(AnsiStyle.section("🤖 最终结果"));
            }
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

    private void finish() {
        if (streamedOutput) {
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
            }
            System.out.println();
        }
    }
}
