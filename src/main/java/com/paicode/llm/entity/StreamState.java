package com.paicode.llm.entity;

/**
 * @Author beaker
 * @Date 2026/9/20 18:46
 * @Description 流状态
 */
public class StreamState {

    private volatile boolean streamedOutput;

    public void markStreamed() {
        streamedOutput = true;
    }

    public boolean hasStreamedOutput() {
        return streamedOutput;
    }
}
