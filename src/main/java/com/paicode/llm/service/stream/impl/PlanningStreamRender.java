package com.paicode.llm.service.stream.impl;

import com.paicode.llm.service.stream.StreamListener;
import com.paicode.utils.AnsiStyle;
import com.paicode.utils.TerminalMarkdownRenderer;

/**
 * @Author beaker
 * @Date 2026/9/19 20:27
 * @Description plan 流式输出渲染器
 */
public class PlanningStreamRender implements StreamListener {

    private TerminalMarkdownRenderer reasoningRender;
    private boolean reasoningStarted;
    private boolean streamed;

    @Override
    public void onReasoningDelta(String delta) {
        if (delta == null || delta.isBlank()) {
            return;
        }

        if (!reasoningStarted) {
            System.out.println(AnsiStyle.heading("规划思考"));
            reasoningRender = new TerminalMarkdownRenderer(System.out);
            reasoningStarted = true;
            streamed = true;
        }

        reasoningRender.append(delta);
        System.out.flush();
    }

    public void finish() {
        if (streamed) {
            if (reasoningRender != null) {
                reasoningRender.finish();
            }
            System.out.println("\n");
        }
    }
}
