package com.paicode.tui.service.pane.component;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.paicode.config.PaiCodeConfig;
import com.paicode.llm.service.model.LlmClient;

import java.util.function.Consumer;

/**
 * @Author beaker
 * @Date 2026/10/1 21:08
 * @Description 底部输入栏
 */
public class InputBar extends Panel {

    private final LlmClient llmClient;
    private final Consumer<String> onMessage;
    private final TextBox inputBox;

    /**
     * 创建输入栏
     */
    public InputBar(PaiCodeConfig config, LlmClient llmClient, Consumer<String> onMessage) {
        super();
        this.llmClient = llmClient;
        this.onMessage = onMessage;

        setLayoutManager(new LinearLayout(Direction.VERTICAL));

        // 输入框, 自定义 TextBox
        this.inputBox = new TextBox() {
            @Override
            public synchronized Result handleKeyStroke(KeyStroke keyStroke) {
                if (keyStroke.getKeyType() == KeyType.Enter) {
                    submit();
                    return Result.HANDLED;
                } else if (keyStroke.getKeyType() == KeyType.Escape) {
                    clear();
                    return Result.HANDLED;
                }
                return super.handleKeyStroke(keyStroke);
            }
        };
        inputBox.setPreferredSize(new TerminalSize(80, 3));

        addComponent(inputBox.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)));
    }

    /**
     * 提交消息。
     */
    private void submit() {
        String text = inputBox.getText().trim();

        if (!text.isEmpty()) {
            onMessage.accept(text);
            inputBox.setText("");
        }
    }

    /**
     * 清空输入框。
     */
    public void clear() {
        inputBox.setText("");
    }
}
