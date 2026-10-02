package com.paicode.renderer.service.inline;

import lombok.Getter;

import java.io.PrintStream;
import java.util.List;

/**
 * @Author beaker
 * @Date 2026/10/2 01:33
 * @Description 行内可折叠块
 *
 * 典型用途是折叠 toolCall 的展示, 通过 ctrl + o 展开
 * 通过保存渲染后的行数, 上移光标并清屏实现
 */
@Getter
public class FoldableBlock {

    private final PrintStream out;
    private final String collapsedHeader;
    private final List<String> expandedLines;
    private final String collapseFooter;

    private boolean expanded;
    private int renderedLineCount; /** 当前渲染内容的行数 */
    private volatile boolean frozen;

    public FoldableBlock(PrintStream out,
                         String collapsedHeader,
                         List<String> expandedLines) {
        this(out, collapsedHeader, expandedLines, "⏷ collapse (ctrl+o)");
    }

    public FoldableBlock(PrintStream out,
                         String collapsedHeader,
                         List<String> expandedLines,
                         String collapseFooter) {
        this.out = out;
        this.collapsedHeader = collapsedHeader;
        this.expandedLines = List.copyOf(expandedLines);
        this.collapseFooter = collapseFooter;
    }

    /**
     *  首次渲染（折叠态），由调用方在 BlockRegistry.register 之前/之后调用。
     **/
    public void renderInitial() {
        synchronized (out) {
            out.println(collapsedHeader);
            renderedLineCount = 1;
            out.flush();
        }
    }

    public void freeze() {
        frozen = true;
    }

    /**
     * 展开/收起切换。frozen 后不再生效。
     */
    public boolean toggle() {
        if (frozen) {
            return false;
        }

        synchronized (out) {
            // 上移 N 行覆盖原渲染
            out.print(AnsiSeq.moveUp(renderedLineCount));
            out.print("\r");
            out.print(AnsiSeq.CLEAR_TO_EOS);

            if (expanded) {
                out.println(collapsedHeader);
                renderedLineCount = 1;
            } else {
                for (String line : expandedLines) {
                    out.println(line);
                }
                if (collapseFooter != null && !collapseFooter.isEmpty()) {
                    out.println(collapseFooter);
                    renderedLineCount = expandedLines.size() + 1;
                } else {
                    renderedLineCount = expandedLines.size();
                }
            }

            expanded = !expanded;
            out.flush();
        }
        return true;
    }


}
