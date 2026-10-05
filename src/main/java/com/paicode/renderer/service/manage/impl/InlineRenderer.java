package com.paicode.renderer.service.manage.impl;

import com.paicode.hitl.entity.ApprovalRequest;
import com.paicode.hitl.entity.ApprovalResult;
import com.paicode.llm.entity.ToolCall;
import com.paicode.renderer.entity.StatusInfo;
import com.paicode.renderer.entity.entry.BlockEntry;
import com.paicode.renderer.entity.entry.TextEntry;
import com.paicode.renderer.entity.entry.TranscriptEntry;
import com.paicode.renderer.service.inline.*;
import com.paicode.renderer.service.manage.Renderer;
import com.paicode.utils.AnsiStyle;
import lombok.Getter;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * @Author beaker
 * @Date 2026/10/2 14:22
 * @Description Inline 流式渲染器
 */
@Getter
public class InlineRenderer implements Renderer {

    private final Terminal terminal;
    private final PlainRenderer fallback;
    private final BottomStatusBar statusBar;
    private final BlockRegistry blockRegistry;
    private final PrintStream stream;
    private final PrintStream out;
    private final Object transcriptLock = new Object();
    private final Object thinkingLock = new Object();
    private final InlineActivityDisplay activityDisplay;
    private final List<TranscriptEntry> transcript = new ArrayList<>();
    private volatile LineReader lineReader;
    private int renderedRows;
    private boolean redrawing;
    private volatile boolean started;
    private volatile boolean closed;

    // —— 代码块折叠状态机字段（仅供 createTranscriptStream 内部使用）——
    private final StringBuilder lineBuffer = new StringBuilder();
    private final List<String> codeBodyLines = new ArrayList<>();
    private boolean inCodeBlock;
    private String codeLanguage = "";
    private String codeHeaderLine;
    private int codeStartTranscriptIndex = -1;
    private boolean codeHeaderEmitted;

    public InlineRenderer(Terminal terminal) {
        this(terminal, System.out);
    }

    public InlineRenderer(Terminal terminal, PrintStream out) {
        this.terminal = terminal;
        this.fallback = new PlainRenderer();
        this.out = out;
        this.statusBar = TerminalCapabilities.supportsScrollRegion(terminal)
                ? new BottomStatusBar(terminal)
                : null;
        this.activityDisplay = statusBar == null ? null : new InlineActivityDisplay(terminal, out);
        this.blockRegistry = new BlockRegistry();
        this.stream = createTranscriptStream(out);
    }

    @Override
    public void start() {
        if (started || closed) {
            return;
        }
        if (statusBar != null) {
            statusBar.start();
        }
        started = true;
    }

    @Override
    public void beginTurn() {
        synchronized (transcriptLock) {
            transcript.clear();
            renderedRows = 0;
            lineBuffer.setLength(0);
            inCodeBlock = false;
            codeBodyLines.clear();
            codeLanguage = "";
            codeHeaderLine = null;
            codeStartTranscriptIndex = -1;
            codeHeaderEmitted = false;
        }
        blockRegistry.clear();
    }

    @Override
    public void beforeInput() {
        if (statusBar != null) {
            statusBar.prepareInputLine();
            statusBar.flushNow();
        }
    }

    @Override
    public void afterInput() {
        if (statusBar != null) {
            statusBar.finishInputLine();
        }
    }

    @Override
    public String inputPrompt() {
        return "* ";
    }

    @Override
    public String inputRightPrompt() {
        return "message / @path / @image";
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (activityDisplay != null) {
            activityDisplay.close();
        }
        if (statusBar != null) {
            statusBar.close();
        }
        fallback.close();
    }

    @Override
    public PrintStream stream() {
        return stream;
    }

    @Override
    public boolean supportsThinkingPanel() {
        return activityDisplay != null;
    }

    @Override
    public void beginThinking(String label) {
        if (activityDisplay != null && !closed) {
            activityDisplay.begin(label);
        }
    }

    @Override
    public void appendThinking(String delta) {
        if (activityDisplay != null && !closed) {
            activityDisplay.appendThinking(delta);
        }
    }

    @Override
    public void endThinking() {
        if (activityDisplay != null && !closed) {
            activityDisplay.end();
        }
    }

    /**
     * 绑定当前交互循环使用的 JLine LineReader。
     *
     * <p>绑定后，用户正在输入时的异步输出会优先通过
     * {@link LineReader#printAbove(String)} 显示在输入行上方；非读取态和
     * 测试/降级路径继续使用构造时注入的 {@link PrintStream}。
     */
    public void bindLineReader(LineReader lineReader) {
        this.lineReader = lineReader;
    }


    @Override
    public void appendToolCalls(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }

        // 注册 Block
        Map<String, List<ToolCall>> grouped = ToolCallRenderer.group(toolCalls);
        FoldableBlock block = new FoldableBlock(out,
                ToolCallRenderer.collapsedHeader(grouped),
                ToolCallRenderer.expandedLines(grouped));
        blockRegistry.register(block);


        TranscriptEntry entry = new BlockEntry(block);
        String rendered = entry.render();
        synchronized (transcriptLock) {
            transcript.add(entry);
            renderedRows += estimateRows(rendered);
            out.println(rendered);
            out.flush();
        }
    }

    @Override
    public void appendDiff(String filePath, String before, String after) {
        new InlineDiffRenderer(out).render(filePath, before, after);
    }

    @Override
    public void updateStatus(StatusInfo status) {
        if (statusBar != null) {
            statusBar.update(status);
        }
    }

    @Override
    public ApprovalResult promptApproval(ApprovalRequest request) {
        if (terminal == null) {
            return fallback.promptApproval(request);
        }
        return new InlineApprovalPrompter(out, terminal).prompt(request);
    }

    @Override
    public int openPalette(String title, List<String> items) {
        if (terminal == null) {
            return fallback.openPalette(title, items);
        }
        return new SlashPalette(out, terminal).open(title, items);
    }

    /**
     * 是否启动了状态栏
     */
    public boolean hasStatusBar() {
        return statusBar != null;
    }

    /** Main.java 使用：把 Ctrl+O 绑定到 toggleLast。 */
    public BlockRegistry blockRegistry() {
        return blockRegistry;
    }

    /** Main.java 使用：Ctrl+O 触发内存态切换，然后重绘本轮 transcript。 */
    public boolean toggleLastBlock() {
        boolean changed = blockRegistry.toggleLastForRedraw();
        if (changed) {
            redrawTranscript();
        }
        return changed;
    }

    /**
     * 自定义 stream
     */
    private PrintStream createTranscriptStream(PrintStream delegate) {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                if (len <= 0) {
                    return;
                }
                String text = new String(b, off, len, StandardCharsets.UTF_8);
                synchronized (transcriptLock) {
                    if (redrawing) {
                        // 重绘期间 BlockEntry.render() 已经决定折叠/展开形态，原样转发不再走折叠状态机
                        delegate.write(b, off, len);
                        return;
                    }
                    feedWithCodeBlockDetection(text);
                }
            }

            @Override
            public void flush() {
                delegate.flush();
            }
        }, true, StandardCharsets.UTF_8);
    }

    private void feedWithCodeBlockDetection(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            lineBuffer.append(ch);
            if (ch == '\n') {
                String line = lineBuffer.toString();
                lineBuffer.setLength(0);

                // 处理一行内容
                processStreamedLine(line);
            }
        }
    }

    private void processStreamedLine(String line) {
        String stripped = stripAnsi(line).trim();

        if (!inCodeBlock && stripped.startsWith("┌─ code")) {
            // 进入代码块：写出 header，记录 transcript 位置，body 之后会被吞掉
            inCodeBlock = true;
            int colon = stripped.indexOf(':');
            codeLanguage = colon >= 0 ? stripped.substring(colon + 1).trim() : "";
            codeHeaderLine = stripTrailingNewline(line);
            codeBodyLines.clear();
            codeStartTranscriptIndex = transcript.size();
            codeHeaderEmitted = activePrintAboveReader() == null;
            if (codeHeaderEmitted) {
                emit(line);
                transcript.add(new TextEntry(line));
                renderedRows += estimateRows(line);
            }
            return;
        }

        if (inCodeBlock) {
            if (stripped.startsWith("└─ end")) {
                int bodyLineCount = codeBodyLines.size();
                inCodeBlock = false;

                if (codeHeaderEmitted) {
                    // 用 ANSI move-up + clear-to-eos 覆盖原 header 行。这里必须直写
                    // 底层输出，因为 printAbove 是追加式输出，不适合承载光标回退。
                    out.print(AnsiSeq.moveUp(1));
                    out.print("\r");
                    out.print(AnsiSeq.CLEAR_TO_EOS);
                }

                String label = codeLanguage.isEmpty() ? "code" : "code: " + codeLanguage;
                String collapsedHeader = AnsiStyle.subtle(
                        "⏵ " + label + " (" + bodyLineCount + " 行, ctrl+o to expand)");

                List<String> expandedLines = new ArrayList<>();
                expandedLines.add(stripTrailingNewline(codeHeaderLine));
                for (String body : codeBodyLines) {
                    expandedLines.add(stripTrailingNewline(body));
                }
                expandedLines.add(stripTrailingNewline(line));

                FoldableBlock block = new FoldableBlock(out, collapsedHeader, expandedLines, "⏷ collapse (ctrl+o)");
                blockRegistry.register(block);

                if (codeStartTranscriptIndex >= 0 && codeStartTranscriptIndex < transcript.size()) {
                    transcript.set(codeStartTranscriptIndex, new BlockEntry(block));
                } else {
                    transcript.add(new BlockEntry(block));
                }

                if (codeHeaderEmitted) {
                    out.print(collapsedHeader);
                    out.print("\n");
                    out.flush();
                } else {
                    emit(collapsedHeader + "\n");
                }


                // renderedRows 调整：移除原 header 占位（约 1 行），加入折叠头占位
                renderedRows = Math.max(0, renderedRows - estimateRows(codeHeaderLine + "\n"));
                renderedRows += estimateRows(collapsedHeader + "\n");

                codeBodyLines.clear();
                codeHeaderLine = null;
                codeStartTranscriptIndex = -1;
                codeHeaderEmitted = false;
                return;
            }
            // body 行：缓冲，不写终端、不入 transcript
            codeBodyLines.add(line);
            return;
        }

        // 非代码块：常规流式
        emit(line);
        transcript.add(new TextEntry(line));
        renderedRows += estimateRows(line);
    }

    private LineReader activePrintAboveReader() {
        LineReader reader = lineReader;
        if (reader == null || redrawing || closed) {
            return null;
        }
        try {
            return reader.isReading() ? reader : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void emit(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        LineReader reader = activePrintAboveReader();
        if (reader != null) {
            reader.printAbove(text);
            return;
        }
        out.print(text);
        out.flush();
    }

    /**
     * 跳过 Ansi 的内容
     */
    private static String stripAnsi(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '' && i + 1 < s.length() && s.charAt(i + 1) == '[') {
                int j = i + 2;
                while (j < s.length()) {
                    char c = s.charAt(j);
                    if (c >= '@' && c <= '~') {
                        break;
                    }
                    j++;
                }
                i = j;
                continue;
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    private static String stripTrailingNewline(String s) {
        if (s == null || s.isEmpty()) {
            return s == null ? "" : s;
        }
        int end = s.length();
        if (s.charAt(end - 1) == '\n') {
            end--;
        }
        if (end > 0 && s.charAt(end - 1) == '\r') {
            end--;
        }
        return s.substring(0, end);
    }

    private void redrawTranscript() {
        synchronized (transcriptLock) {
            if (transcript.isEmpty()) {
                return;
            }
            LineReader reader = activePrintAboveReader();
            if (reader != null) {
                StringBuilder snapshot = new StringBuilder();
                int rowsAfter = 0;
                for (TranscriptEntry entry : transcript) {
                    String rendered = entry.render();
                    snapshot.append(rendered);
                    rowsAfter += estimateRows(rendered);
                }
                renderedRows = rowsAfter;
                reader.printAbove(snapshot.toString());
                return;
            }
            redrawing = true;
            try {
                int rows = TerminalCapabilities.safeSize(terminal).getRows();
                int maxMove = Math.max(1, rows - (statusBar == null ? 1 : 2));
                int move = Math.min(renderedRows, maxMove);
                if (move > 0) {
                    out.print(AnsiSeq.moveUp(move));
                }
                out.print("\r");
                out.print(AnsiSeq.CLEAR_TO_EOS);
                int rowsAfter = 0;
                for (TranscriptEntry entry : transcript) {
                    String rendered = entry.render();
                    out.print(rendered);
                    rowsAfter += estimateRows(rendered);
                }
                renderedRows = rowsAfter;
                out.flush();
            } finally {
                redrawing = false;
            }
        }
    }

    private int estimateRows(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cols = Math.max(20, TerminalCapabilities.safeSize(terminal).getColumns());
        int rows = 0;
        int col = 0;
        boolean sawVisible = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\u001B') {
                i = skipAnsi(text, i);
                continue;
            }
            if (ch == '\r') {
                col = 0;
                continue;
            }
            if (ch == '\n') {
                rows++;
                col = 0;
                sawVisible = false;
                continue;
            }
            int width = displayWidth(ch);
            if (width <= 0) {
                continue;
            }
            sawVisible = true;
            col += width;
            if (col >= cols) {
                rows++;
                col = 0;
                sawVisible = false;
            }
        }
        if (sawVisible) {
            rows++;
        }
        return rows;
    }

    private int skipAnsi(String text, int escIndex) {
        int i = escIndex + 1;
        if (i < text.length() && text.charAt(i) == '[') {
            i++;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c >= '@' && c <= '~') {
                    return i;
                }
                i++;
            }
        }
        return escIndex;
    }

    private int displayWidth(char ch) {
        if (Character.isISOControl(ch)) {
            return 0;
        }
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA) {
            return 2;
        }
        return 1;
    }
}
