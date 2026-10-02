package com.paicode.tui.service.config;

import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogBuilder;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.paicode.config.PaiCodeConfig;

/**
 * @Author beaker
 * @Date 2026/10/1 18:29
 * @Description TUI 参数面板, 用户可在此配置温度, 模型等
 *
 * 当前版本只提供 provider 快速切换
 */
public class TuiConfigPanel {

    private final PaiCodeConfig config;
    private final WindowBasedTextGUI gui;

    public TuiConfigPanel(PaiCodeConfig config, WindowBasedTextGUI gui) {
        this.config = config;
        this.gui = gui;
    }

    public void showConfigDialog() {
        String info = formatConfigInfo();
        MessageDialogBuilder dialog = new MessageDialogBuilder()
                .setTitle("⚙️  配置")
                .setText(info)
                .addButton(MessageDialogButton.OK);
        dialog.build().showDialog(gui);
    }

    /**
     * 格式化配置信息为可读文本。
     */
    private String formatConfigInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("当前配置:\n\n");

        config.getProviders().forEach((name, provider) -> {
            sb.append("Provider: ").append(name).append("\n");
            sb.append("  Model: ").append(provider.getModel() != null ? provider.getModel() : "未设置").append("\n");
            sb.append("  Base URL: ").append(provider.getBaseUrl() != null ? provider.getBaseUrl() : "默认").append("\n");
            sb.append("  Temperature: ").append(provider.getTemperature()).append("\n");
            sb.append("  Max Tokens: ").append(provider.getMaxTokens()).append("\n");
            sb.append("\n");
        });

        return sb.toString();
    }

    public void switchModel(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            return;
        }

        config.setDefaultProvider(providerName.trim());
        config.save();
    }
}
