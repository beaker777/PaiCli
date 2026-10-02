package com.paicode.renderer.service.manage.factory;

import com.paicode.renderer.constant.Mode;
import com.paicode.renderer.service.inline.TerminalCapabilities;
import com.paicode.renderer.service.manage.Renderer;
import com.paicode.renderer.service.manage.impl.InlineRenderer;
import com.paicode.renderer.service.manage.impl.PlainRenderer;
import org.jline.terminal.Terminal;

/**
 * @Author beaker
 * @Date 2026/10/3 01:49
 * @Description 根据环境变量选择渲染器类型
 */
public class RendererFactory {

    public static Mode resolveMode() {
        String prop = System.getProperty("paicode.renderer");
        if (prop != null && !prop.isBlank()) {
            return parse(prop);
        }
        String env = System.getenv("PAICODE_RENDERER");
        if (env != null && !env.isBlank()) {
            return parse(env);
        }
        return Mode.INLINE;
    }

    private static Mode parse(String raw) {
        return switch (raw.trim().toLowerCase()) {
            case "lanterna", "tui" -> Mode.LANTERNA;
            case "plain" -> Mode.PLAIN;
            case "inline" -> Mode.INLINE;
            default -> {
                System.err.println("⚠️ 未识别的 PAICODE_RENDERER='" + raw + "'，回退到 inline");
                yield Mode.INLINE;
            }
        };
    }

    /**
     * 创建渲染器, inline 模式如果终端不支持自动降级到 plain 模式
     * Lanterna 应当在 TuiBootstrap 中完成注册, 走到这里说明注册失败, 使用 plain 兜底
     */
    public static Renderer create(Mode mode, Terminal terminal) {
        return switch (mode) {
            case PLAIN -> new PlainRenderer();
            case INLINE -> {
                if (TerminalCapabilities.supportsAnsi(terminal)) {
                    yield new InlineRenderer(terminal);
                }
                System.err.println("⚠️ 终端不支持 ANSI，inline 模式回退到 plain");
                yield new PlainRenderer();
            }
            case LANTERNA -> new PlainRenderer();
        };
    }
}
