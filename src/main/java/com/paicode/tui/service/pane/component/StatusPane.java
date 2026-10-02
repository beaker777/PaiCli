package com.paicode.tui.service.pane.component;

import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.paicode.config.PaiCodeConfig;
import com.paicode.llm.service.model.LlmClient;

import java.util.concurrent.atomic.AtomicLong;

/**
 * @Author beaker
 * @Date 2026/10/1 21:01
 * @Description 右侧的状态面板
 */
public class StatusPane extends Panel {

    private final LlmClient llmClient;
    private final Label modelLabel;
    private final Label tokenLabel;
    private final Label modeLabel;
    private final Label timeLabel;
    private final AtomicLong taskStartTime = new AtomicLong(0);

    /**
     * 创建状态栏面板
     */
    public StatusPane(PaiCodeConfig config, LlmClient llmClient) {
        self();
        this.llmClient = llmClient;

        setLayoutManager(new LinearLayout(Direction.VERTICAL));

        // 模型信息
        this.modelLabel = new Label("🤖 " + (llmClient != null ? llmClient.getModelName() : "?"));
        this.tokenLabel = new Label("💡 --");
        this.modeLabel = new Label("🔄 ReAct");
        this.timeLabel = new Label("⏱ --");

        addComponent(modelLabel);
        addComponent(timeLabel);
        addComponent(modeLabel);
        addComponent(timeLabel);
    }

    /**
     * 更新 token 用量
     */
    public void updateTokenUsage(long used, long budget, long cached) {
        tokenLabel.setText(String.format("💡 %d/%d", used, budget));
        if (cached > 0) {
            tokenLabel.setText(tokenLabel.getText() + String.format(" (cached: %d)", cached));
        }
    }

    /**
     * 更新 mode
     */
    public void updateMode(String mode) {
        modeLabel.setText("🔄 " + (mode != null ? mode : "ReAct"));
    }

    /**
     * 开始计时
     */
    public void startTimer() {
        taskStartTime.set(System.currentTimeMillis());
    }

    /**
     * 停止计时, 更新 label
     */
    public void stopTimer() {
        if (taskStartTime.get() > 0) {
            long elapsedMs = System.currentTimeMillis() - taskStartTime.get();
            timeLabel.setText(String.format("⏱ %.1fs", elapsedMs / 1000.0));
            taskStartTime.set(0);
        }
    }
}
