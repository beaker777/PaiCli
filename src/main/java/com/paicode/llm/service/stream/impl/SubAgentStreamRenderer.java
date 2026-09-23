package com.paicode.llm.service.stream.impl;

import com.paicode.agent.MultiAgent.constant.AgentRole;
import com.paicode.llm.service.stream.StreamListener;
import com.paicode.utils.AnsiStyle;
import com.paicode.utils.TerminalMarkdownRenderer;

import java.io.PrintStream;

/**
 * @Author beaker
 * @Date 2026/9/21 17:56
 * @Description subAgent 流式渲染器
 */
public class SubAgentStreamRenderer implements StreamListener {

    private final String agentName;
    private final AgentRole role;
    private final PrintStream out;
    private final StringBuilder pendingReasoning = new StringBuilder();
    private final StringBuilder lateReasoning = new StringBuilder();
    private TerminalMarkdownRenderer reasoningRenderer;
    private TerminalMarkdownRenderer contentRenderer;
    private boolean reasoningStarted;
    private boolean contentStarted;
    private boolean streamedOutput;

    public SubAgentStreamRenderer(String agentName, AgentRole role, PrintStream out) {
        this.agentName = agentName;
        this.role = role;
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
            out.println(AnsiStyle.heading(reasoningLabel() + "[" + agentName + "]"));
            reasoningRenderer = new TerminalMarkdownRenderer(out);
            reasoningRenderer.append(pendingReasoning.toString());
            pendingReasoning.setLength(0);
            reasoningStarted = true;
            streamedOutput = true;
        } else {
            reasoningRenderer.append(delta);
        }
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
                // 实质 reasoning 尚未流出就被 content 打断, 先补齐思考过程再切到结果
                out.println(AnsiStyle.heading("🧠 " + reasoningLabel() + " [" + agentName + "]"));
                TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(out);
                r.append(pendingReasoning.toString());
                r.finish();
                out.println();
                pendingReasoning.setLength(0);
                reasoningStarted = true;
            }
            out.println(AnsiStyle.section("🤖 " + contentLabel() + " [" + agentName + "]"));
            contentRenderer = new TerminalMarkdownRenderer(out);
            contentStarted = true;
            streamedOutput = true;
        }
        contentRenderer.append(delta);
        out.flush();
    }

    private String reasoningLabel() {
        return switch (role) {
            case PLANNER -> "规划思考";
            case WORKER -> "执行思考";
            case REVIEWER -> "审查思考";
        };
    }

    private String contentLabel() {
        return switch (role) {
            case PLANNER -> "规划结果";
            case WORKER -> "执行输出";
            case REVIEWER -> "审查结果";
        };
    }

    /**
     * 在两次迭代之间调用, 避免出现上一轮的内容和下一轮错位的问题
     */
    public void resetBetweenTwoIterations() {
        if (reasoningRenderer != null) {
            reasoningRenderer.finish();
            reasoningRenderer = null;
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
        }
        if (contentRenderer != null) {
            contentRenderer.finish();
        }

        String late = lateReasoning.toString().trim();
        if (!late.isEmpty()) {
            out.println();
            out.println(AnsiStyle.heading("补充思考 [" + agentName + "]"));
            TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(out);
            r.append(late);
            r.finish();
            lateReasoning.setLength(0);
            streamedOutput = true;
        }
        if (streamedOutput) {
            out.println("\n");
        }
    }
}
