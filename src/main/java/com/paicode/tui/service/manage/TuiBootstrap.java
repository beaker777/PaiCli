package com.paicode.tui.service.manage;

import com.paicode.agent.ReAct.Agent;
import com.paicode.config.PaiCodeConfig;
import com.paicode.hitl.service.handler.impl.SwitchableHitlHandler;
import com.paicode.hitl.service.handler.impl.RendererHitlHandler;
import com.paicode.llm.service.model.LlmClient;
import com.paicode.tui.service.config.TuiConfigPanel;
import com.paicode.utils.AnsiStyle;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.util.Objects;

/**
 * @Author beaker
 * @Date 2026/10/1 22:52
 * @Description tui 入口与降级检测
 *
 * TUI 使用条件 renderer = TUI/Lanterna, 兼容旧条件 tui = true
 */
public class TuiBootstrap {

    private static final String TUI_ENV = "PAICODE_TUI";
    private static final String TUI_PROPERTY = "paicode.tui";
    private static final String RENDERER_ENV = "PAICODE_RENDERER";
    private static final String RENDERER_PROPERTY = "paicode.renderer";
    private static final int MIN_COLS = 80;
    private static final int MIN_ROWS = 24;

    /**
     * 判断当前环境是否应该使用 tui
     */
    public static boolean shouldUseTui() {
        if (!isTuiRequested()) {
            return false;
        }

        try (Terminal terminal = TerminalBuilder.builder().system(true).dumb(true).build()) {
            return shouldUseTui(terminal);
        } catch (IOException e) {
            System.err.println("⚠️ 已显式启用 TUI，但终端检测失败，降级到 CLI 模式: " + e.getMessage());
            return false;
        }
    }

    public static boolean shouldUseTui(Terminal terminal) {
        // 默认保持传统 CLI 交互；全屏 TUI 必须显式开启。
        boolean requested = isTuiRequested();
        if (!requested) {
            return false;
        }

        // 环境变量强制降级
        if (Boolean.parseBoolean(Objects.requireNonNullElse(System.getenv("NO_TUI"), "false"))) {
            System.out.println(AnsiStyle.heading("💡 提示: NO_TUI=true，已切换为 CLI 模式。"
                    + "要启用 TUI 请清除 NO_TUI 环境变量，并保留 PAICODE_RENDERER=lanterna"));
            return false;
        }

        // 终端尺寸检测
        if (terminal == null) {
            System.out.println(AnsiStyle.heading("💡 提示: 当前运行环境没有可用系统终端，已切换为 CLI 模式。"));
            return false;
        }
        Size size = terminal.getSize();
        if (size == null || size.getColumns() <= 0 || size.getRows() <= 0) {
            System.out.println(AnsiStyle.heading("💡 提示: 当前运行环境无法读取真实终端尺寸，已切换为 CLI 模式。"
                    + "如需启用 TUI，请在 macOS Terminal / iTerm2 等真实终端中运行。"));
            return false;
        }
        if (size.getColumns() < MIN_COLS || size.getRows() < MIN_ROWS) {
            System.out.println(AnsiStyle.heading("💡 提示: 终端尺寸过小（当前 " +
                    size.getColumns() + "×" + size.getRows() +
                    "，最小需要 " + MIN_COLS + "×" + MIN_ROWS + "），已切换为 CLI 模式。" +
                    "如需启用 TUI，请调整窗口大小后重新运行。"));
            return false;
        }

        return true;
    }

    private static boolean isTuiRequested() {
        String rendererProperty = System.getProperty(RENDERER_PROPERTY);
        if (rendererProperty != null && !rendererProperty.isBlank()) {
            return isLanternaRenderer(rendererProperty);
        }
        String rendererEnv = System.getenv(RENDERER_ENV);
        if (rendererEnv != null && !rendererEnv.isBlank()) {
            return isLanternaRenderer(rendererEnv);
        }

        // 兼容旧式配置
        String property = System.getProperty(TUI_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Boolean.parseBoolean(property);
        }
        return Boolean.parseBoolean(Objects.requireNonNullElse(System.getenv(TUI_ENV), "false"));
    }

    private static boolean isLanternaRenderer(String value) {
        String normalized = value.trim().toLowerCase();
        return "lanterna".equals(normalized) || "tui".equals(normalized);
    }

    /**
     * 启动 TUI
     */
    public static void launch(PaiCodeConfig config, LlmClient llmClient, Agent reactAgent, SwitchableHitlHandler hitlHandler) throws IOException {
        Objects.requireNonNull(config);
        Objects.requireNonNull(llmClient);
        Objects.requireNonNull(reactAgent);
        Objects.requireNonNull(hitlHandler);

        System.out.println(AnsiStyle.section("🖥️  启动 TUI 界面..."));

        try {
            // 创建窗口
            LanternaWindow window = new LanternaWindow(config, llmClient);

            // 创建渲染器
            LanternaRenderer renderer = new LanternaRenderer(window);
            reactAgent.setRenderer(renderer);
            reactAgent.setHitlEnabledSupplier(hitlHandler::isEnabled);
            reactAgent.getToolRegistry().setWriteFileObserver(
                    (path, ba) -> renderer.appendDiff(path, ba[0], ba[1]));
            RendererHitlHandler rendererHitl = new RendererHitlHandler(renderer, hitlHandler.isEnabled());
            hitlHandler.setDelegate(rendererHitl);

            // 创建 Controller
            TuiSessionController controller = new TuiSessionController(
                    config,
                    llmClient,
                    reactAgent,
                    hitlHandler,
                    window.getRootPane().getCenterPane(),
                    window.getRootPane().getStatusPane(),
                    window::close,
                    () -> new TuiConfigPanel(config, window.getGui()).showConfigDialog(),
                    window::runOnGuiThread
            );
            window.getRootPane().setMessageHandler(controller::submit);
            window.setCloseHook(controller::close);

            // 阻塞直到用户退出
            window.start();

            System.out.println("\n👋 再见");
        } catch (Exception e) {
            System.err.println("❌ TUI 启动失败，降级到 CLI 模式: " + e.getMessage());
            e.printStackTrace();
            throw new IOException("TUI 启动失败", e);
        }
    }
}
