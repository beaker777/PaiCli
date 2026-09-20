package com.paicode.llm.stream;

import com.paicode.llm.stream.entity.StreamState;
import com.paicode.utils.TerminalMarkdownRenderer;

/**
 * @Author beaker
 * @Date 2026/9/20 20:31
 * @Description 任务流式输出渲染器
 */
public class TaskStreamRender implements StreamListener {

    private final String taskId;
    private final StreamState streamState;
    private TerminalMarkdownRenderer reasoningRenderer;
    private TerminalMarkdownRenderer contentRenderer;
    private boolean reasoningStarted;
    private boolean contentStarted;
    private boolean streamedOutput;

    public TaskStreamRender(String taskId, StreamState streamState) {
        this.taskId = taskId;
        this.streamState = streamState;
    }

    @Override
    public void onReasoningDelta(String delta) {
        if (delta == null || delta.isBlank()) {
            return;
        }

        if (!reasoningStarted) {
            System.out.println("任务思考 [" + taskId + "]");
            reasoningRenderer = new TerminalMarkdownRenderer(System.out);
            reasoningStarted = true;
            streamedOutput = true;
            streamState.markStreamed();
        }
        reasoningRenderer.append(delta);

        System.out.flush();
    }

    @Override
    public void onContentDelta(String delta) {
        if (delta == null || delta.isBlank()) {
            return;
        }

        if (!contentStarted) {
            if (!reasoningStarted) {
                System.out.println("任务结果 [" + taskId + "]");
            } else {
                System.out.println();
                System.out.println("任务结果 [" + taskId + "]");
            }
            contentRenderer = new TerminalMarkdownRenderer(System.out);
            contentStarted = true;
            streamedOutput = true;
            streamState.markStreamed();
        }
        contentRenderer.append(delta);
        System.out.flush();
    }

    public synchronized void finish() {
        if (streamedOutput) {
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
            }
            System.out.println("\n");
        }
    }

    public synchronized boolean hasStreamedOutput() {
        return streamedOutput;
    }
}
