package com.paicode.tui.service.manage;

import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.paicode.config.PaiCodeConfig;
import com.paicode.llm.service.model.LlmClient;
import com.paicode.tui.service.pane.RootPane;
import lombok.Getter;

import java.io.IOException;
import java.util.Objects;

/**
 * @Author beaker
 * @Date 2026/10/1 23:01
 * @Description Lanterna 窗口管理器
 */
@Getter
public class LanternaWindow {

    private final Screen screen;
    private final WindowBasedTextGUI gui;
    private final LlmClient llmClient;
    private final PaiCodeConfig config;
    private final BasicWindow mainWindow;
    private final RootPane rootPane;
    private Runnable closeHook = () -> {
    };

    /**
     * 是否处于 TUI 模式（静态标志）。
     */
    private static volatile boolean tuiMode = false;

    /**
     * 创建 Lanterna Window
     */
    public LanternaWindow(PaiCodeConfig config, LlmClient llmClient) throws IOException {
        this.config = config;
        this.llmClient = llmClient;

        // 创建 Terminal + Screen
        DefaultTerminalFactory terminalFactory = new DefaultTerminalFactory();
        this.screen = new TerminalScreen(terminalFactory.createTerminal());
        this.screen.startScreen();

        // 创建 GUI 层（使用默认主题）
        this.gui = new MultiWindowTextGUI(screen);

        // 创建根面板（三栏布局）
        this.rootPane = new RootPane(config, llmClient);
        this.mainWindow = new BasicWindow("PaiCode v16.0.0");
        mainWindow.setComponent(rootPane);
        gui.addWindow(mainWindow);

        tuiMode = true;
    }

    /**
     * 启动 TUI 主循环（阻塞直到窗口关闭）。
     */
    public void start() {
        try {
            gui.waitForWindowToClose(mainWindow);
        } catch (Exception e) {
            System.err.println("❌ TUI 主循环异常: " + e.getMessage());
        } finally {
            closeHook.run();
            tuiMode = false;
            closeScreen();
        }
    }

    /**
     * 关闭窗口。
     */
    public void close() {
        mainWindow.close();
        closeScreen();
        tuiMode = false;
    }

    private void closeScreen() {
        try {
            if (screen != null) {
                screen.stopScreen();
            }
        } catch (IOException e) {
            System.err.println("⚠️ 关闭屏幕失败: " + e.getMessage());
        }
    }

    /**
     * 在 GUI 线程执行任务
     */
    public void runOnGuiThread(Runnable task) {
        Objects.requireNonNull(task);
        if (gui != null) {
            gui.getGUIThread().invokeLater(task);
        }
    }

    public void setCloseHook(Runnable closeHook) {
        this.closeHook = closeHook == null ? () -> {} : closeHook;
    }
}
