package com.paicode.llm.stream;

/**
 * @Author beaker
 * @Date 2026/9/19 20:25
 * @Description 流式输出监听器
 */
public interface StreamListener {

    StreamListener NO_OP = new StreamListener() {
    };

    default void onReasoningDelta(String delta) {}

    default void onContentDelta(String delta) {}
}
