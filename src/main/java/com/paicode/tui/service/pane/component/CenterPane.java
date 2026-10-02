package com.paicode.tui.service.pane.component;

import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.paicode.config.PaiCodeConfig;
import com.paicode.llm.service.model.LlmClient;
import com.paicode.tui.service.highlight.CodeHighlighter;
import com.paicode.utils.AnsiStyle;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Author beaker
 * @Date 2026/10/1 19:52
 * @Description 中心面板, 展示模型输出的对话内容
 */
public class CenterPane extends Panel {

    private final LlmClient llmClient;
    private final TextBox chatArea;
    private final StringBuilder assistantBuffer;

    /**
     * 创建面板
     */
    public CenterPane(PaiCodeConfig config, LlmClient llmClient) {
        super();
        this.llmClient = llmClient;
        this.assistantBuffer = new StringBuilder();

        // 设置布局
        setLayoutManager(new LinearLayout(Direction.VERTICAL));

        // 对话使用 textBox, 设置为只读
        this.chatArea = new TextBox("对话开始...\n\n💡 提示：\n  - 在底部输入框输入问题\n  - Ctrl+O 折叠/展开代码块\n  - Ctrl+P 查看历史对话\n  - Ctrl+\\ 显示/隐藏文件树");
        chatArea.setReadOnly(true);

        // 设置文本框的布局并假如面板
        addComponent(chatArea.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)));
    }

    public void onUserMessage(String message) {
        appendUserMessage(message);
    }

    /**
     * 追加系统信息
     */
    public void appendSystemMessage(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        chatArea.setText(chatArea.getText() + "\n💡 系统:\n" + message.trim() + "\n");
        scrollToBottom();
    }

    /**
     * 追加助手信息
     */
    public void appendAssistantOutput(String output) {
        if (output == null || output.isBlank()) {
            return;
        }
        chatArea.setText(chatArea.getText() + "\n🤖 PaiCode:\n" + output.trim() + "\n");
        scrollToBottom();
    }

    /**
     * 追加用户信息
     */
    private void appendUserMessage(String message) {
        String rendered = renderMarkdown(message);
        chatArea.setText(chatArea.getText() + "\n👤 你:\n" + rendered + "\n");
        scrollToBottom();
    }

    /**
     * 追加助手信息, 流式
     */
    public void appendAssistantChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        assistantBuffer.append(chunk);
        flushAssistantBuffer();
    }

    /**
     * 将缓存区中的消息刷新到 textBox
     */
    private synchronized void flushAssistantBuffer() {
        if (assistantBuffer.isEmpty()) {
            return;
        }

        String content = assistantBuffer.toString();
        assistantBuffer.setLength(0);

        // 渲染 Markdown + 代码高亮
        String rendered = renderMarkdown(content);
        chatArea.setText(chatArea.getText() + rendered);
        scrollToBottom();
    }

    /**
     * 追加工具调用消息
     */
    public void appendToolCall(String toolName, String args) {
        String toolBlock = "🔧 工具调用: " + (toolName != null ? toolName : "unknown") + "\n"
                + (args != null ? "  参数: " + args : "")
                + "\n";
        chatArea.setText(chatArea.getText() + "\n" + toolBlock);
        scrollToBottom();
    }

    /**
     * 追加工具调用结果
     */
    public void appendToolResult(String result) {
        String truncated = truncateResult(result, 500);
        String resultBlock = "📤 工具结果:\n" + truncated + "\n";
        chatArea.setText(chatArea.getText() + resultBlock);
        scrollToBottom();
    }

    /**
     * 渲染 markdown, 添加高亮
     */
    private String renderMarkdown(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        // 代码块高亮（```lang ... ```）
        text = highlightCodeBlocks(text);

        // 粗体 **text** → bold
        text = replaceAllRegex(text, "\\*\\*(.+?)\\*\\*", m -> AnsiStyle.emphasis(m.group(1)));

        // 行内代码 `code`
        text = replaceAllRegex(text, "`(.+?)`", m -> AnsiStyle.codeLabel(m.group(1)));

        return text;
    }

    private static String replaceAllRegex(String text, String regex, Function<Matcher, String> replacer) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, replacer.apply(matcher));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String highlightCodeBlocks(String text) {
        StringBuilder result = new StringBuilder();
        int i = 0;

        while (i < text.length()) {
            // 查找代码块开始 ```lang
            if (i < text.length() - 2 && text.charAt(i) == '`' && text.charAt(i + 1) == '`' && text.charAt(i + 2) == '`') {
                int start = i + 3;
                // 提取语言标识
                int langEnd = text.indexOf('\n', start);
                String lang = "text";
                int codeStart;
                if (langEnd > 0) {
                    lang = text.substring(start, langEnd).trim();
                    codeStart = langEnd + 1;
                } else {
                    codeStart = start;
                }

                // 查找代码块结束
                int codeEnd = text.indexOf("```", codeStart);
                if (codeEnd < 0) {
                    codeEnd = text.length();
                }

                String code = text.substring(codeStart, codeEnd);
                String highlighted = CodeHighlighter.highlight(code, lang);

                result.append("\n").append(highlighted);
                i = codeEnd + 3;
            } else {
                result.append(text.charAt(i));
                i++;
            }
        }

        return result.toString();
    }

    private static String truncateResult(String result, int maxLength) {
        if (result == null) {
            return "null";
        }
        if (result.length() <= maxLength) {
            return result;
        }
        return result.substring(0, maxLength) + "\n... (截断，共 " + result.length() + " 字符)";
    }

    private void scrollToBottom() {
        // TextBox 会自动滚动到最后。
    }

    public void clear() {
        chatArea.setText("");
        assistantBuffer.setLength(0);
    }
}
