package com.paicode.llm.service.stream.impl;

import com.paicode.llm.service.stream.StreamListener;
import com.paicode.utils.AnsiStyle;
import com.paicode.utils.TerminalMarkdownRenderer;

import java.io.PrintStream;

/**
 * @Author beaker
 * @Date 2026/9/19 20:27
 * @Description plan 流式输出渲染器
 */
public class PlanningStreamRenderer implements StreamListener {

    private final PrintStream out;
    private TerminalMarkdownRenderer reasoningRender;
    private boolean reasoningStarted;
    private boolean streamed;

    public PlanningStreamRenderer(PrintStream out) {
        this.out = out == null ? System.out : out;
    }

    @Override
    public void onReasoningDelta(String delta) {
        if (delta == null || delta.isBlank()) {
            return;
        }

        if (!reasoningStarted) {
            out.println(AnsiStyle.heading("🧠 规划思考"));
            reasoningRender = new TerminalMarkdownRenderer(out);
            reasoningStarted = true;
            streamed = true;
        }

        reasoningRender.append(delta);
        out.flush();
    }

    public void finish() {
        if (streamed) {
            if (reasoningRender != null) {
                reasoningRender.finish();
            }
            out.println("\n");
        }
    }
}
