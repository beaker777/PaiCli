package com.paicode.tui.service.pane;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.paicode.config.PaiCodeConfig;
import com.paicode.llm.service.model.LlmClient;
import com.paicode.tui.service.pane.component.CenterPane;
import com.paicode.tui.service.pane.component.FileTreePane;
import com.paicode.tui.service.pane.component.InputBar;
import com.paicode.tui.service.pane.component.StatusPane;
import lombok.Getter;
import lombok.Setter;

import javax.naming.LinkLoopException;
import java.util.function.Consumer;

/**
 * @Author beaker
 * @Date 2026/10/1 21:16
 * @Description 根面板容器, 实现布局, 进行组装
 */
@Getter
@Setter
public class RootPane extends Panel {

    private final FileTreePane fileTreePane;
    private final CenterPane centerPane;
    private final StatusPane statusPane;
    private final InputBar inputBar;

    private final LlmClient llmClient;
    private final PaiCodeConfig config;
    private Consumer<String> messageHandler;

    // 宽度比例（百分比）
    private static final double FILE_TREE_RATIO = 0.25;
    private static final double STATUS_RATIO = 0.10;

    // 文件树可见性状态
    private boolean fileTreeVisible = true;

    /**
     * 创建根面板
     */
    public RootPane(PaiCodeConfig config, LlmClient llmClient) {
        super();
        this.config = config;
        this.llmClient = llmClient;

        // 创建子面板
        this.fileTreePane = new FileTreePane(config);
        this.centerPane = new CenterPane(config, llmClient);
        this.statusPane = new StatusPane(config, llmClient);
        this.inputBar = new InputBar(config, llmClient, this::onUserMessage);
        this.messageHandler = centerPane::onUserMessage;

        // 设置主布局, 垂直布局
        setLayoutManager(new LinearLayout(Direction.VERTICAL));

        // 顶部面板容器, 水平布局
        Panel topPanel = new Panel();
        topPanel.setLayoutManager(new LinearLayout(Direction.HORIZONTAL));
        topPanel.addComponent(fileTreePane.withBorder(Borders.singleLine("项目结构")));
        topPanel.addComponent(centerPane
                .withBorder(Borders.singleLine("对话"))
                .setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)));
        topPanel.addComponent(statusPane
                .withBorder(Borders.singleLine("状态"))
                .setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)));

        // 添加顶部面板
        addComponent(topPanel.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)));
        // 输入栏
        addComponent(inputBar
                .withBorder(Borders.singleLine("输入"))
                .setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)));
    }

    public void onUserMessage(String message) {
        if (message != null && !message.trim().isBlank()) {
            messageHandler.accept(message);
        }
    }

    /**
     * 调整窗口大小
     */
    public void onResize(TerminalSize newSize) {
        if (newSize == null) {
            return;
        }

        int cols = newSize.getColumns();
        int rows = newSize.getRows();

        // 响应式调整：
        // - cols < 90: 文件树缩到 15 列
        // - cols < 80: 文件树隐藏（按 Ctrl+\ 可恢复）
        int fileTreeWidth;
        if (cols < 80) {
            fileTreeWidth = 0;  // 隐藏
        } else if (cols < 120) {
            fileTreeWidth = 15;
        } else {
            fileTreeWidth = (int) (cols * FILE_TREE_RATIO);
        }

        fileTreePane.setPreferredSize(new TerminalSize(fileTreeWidth, rows - 5));

        // 状态栏固定 20 列
        int statusWidth = Math.min(20, (int) (cols * STATUS_RATIO));
        statusPane.setPreferredSize(new TerminalSize(statusWidth, rows - 5));

        // 对话流填充剩余
        int centerWidth = cols - fileTreeWidth - statusWidth - 2;  // -2 for borders
        centerPane.setPreferredSize(new TerminalSize(Math.max(20, centerWidth), rows - 5));

        // 刷新布局
        invalidate();
    }

    /**
     * 切换文件树可见性。
     */
    public void toggleFileTree() {
        fileTreeVisible = !fileTreeVisible;
        fileTreePane.setVisible(fileTreeVisible);

        invalidate();
        centerPane.appendSystemMessage("文件树已" + (fileTreeVisible ? "显示" : "隐藏"));
    }

    /**
     * 处理快捷键
     */
    @Override
    public boolean handleInput(KeyStroke keyStroke) {
        // Ctrl+O: 折叠/展开代码块
        if (keyStroke.getKeyType() == KeyType.Character && keyStroke.isCtrlDown() && keyStroke.getCharacter() == 'O') {
            centerPane.appendSystemMessage("代码块折叠快捷键已收到；当前版本保留完整代码输出。");
            return true;
        }

        // Ctrl+P: 查看历史对话
        if (keyStroke.getKeyType() == KeyType.Character && keyStroke.isCtrlDown() && keyStroke.getCharacter() == 'P') {
            centerPane.appendSystemMessage("对话历史已持续保存到 ~/.paicode/history/。");
            return true;
        }

        // Ctrl+\: 显示/隐藏文件树
        if (keyStroke.getKeyType() == KeyType.Character && keyStroke.isCtrlDown() && keyStroke.getCharacter() == '\\') {
            toggleFileTree();
            return true;
        }

        return super.handleInput(keyStroke);
    }
}
