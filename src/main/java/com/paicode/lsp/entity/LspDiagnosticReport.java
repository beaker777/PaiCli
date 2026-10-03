package com.paicode.lsp.entity;

/**
 * @Author beaker
 * @Date 2026/10/3 17:57
 * @Description LSP 诊断报告
 */
public record LspDiagnosticReport(String promptText, String displayText) {

    public static final LspDiagnosticReport EMPTY = new LspDiagnosticReport("", "");

    public boolean isEmpty() {
        return promptText == null || promptText.isBlank();
    }
}
