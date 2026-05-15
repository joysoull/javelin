package com.javelin.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 圆角框渲染器 —— 用于工具调用 / 工具结果块的视觉呈现，模仿 Claude Code 风格。
 *
 * <pre>
 *   ╭─ tool_use · calculator ─────────────
 *   │ { "expression": "23 * 47" }
 *   ╰─
 * </pre>
 *
 * 说明：
 * - 左侧用竖线 │ 作为视觉边界，比完整四边框更紧凑，长内容也不需要计算右边界
 * - 顶/底用 ╭ ╰ 圆角，加一段横线 + 标题
 * - 标题部分可上颜色，内容部分留空白由调用方决定颜色
 * - 内容里的换行会被拆开，每行加前导 "│ "
 */
public final class Box {

    public static final char TL = '╭';
    public static final char BL = '╰';
    public static final char H  = '─';
    public static final char V  = '│';

    private Box() {}

    /**
     * 渲染一个带标题的左侧边框块。
     *
     * @param titleStyle  ANSI 颜色（如 {@link Ansi#CYAN}）。null 表示不上色
     * @param title       标题文本，会被显示在顶部
     * @param contentStyle 内容颜色，null 表示不上色
     * @param content     主体内容，可包含换行
     */
    public static String render(String titleStyle, String title, String contentStyle, String content) {
        StringBuilder sb = new StringBuilder();

        // 顶部：╭─ title ─────
        String head = TL + "" + H + " " + title + " ";
        sb.append(applyStyle(titleStyle, head + repeat(H, 4))).append('\n');

        // 内容：每行前面加 "│ "
        for (String line : splitLines(content)) {
            sb.append(applyStyle(titleStyle, V + " "));
            sb.append(applyStyle(contentStyle, line));
            sb.append('\n');
        }

        // 底部：╰─
        sb.append(applyStyle(titleStyle, BL + "" + H));
        return sb.toString();
    }

    private static String applyStyle(String style, String text) {
        return style == null ? text : Ansi.wrap(style, text);
    }

    private static List<String> splitLines(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) { out.add(""); return out; }
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        if (start < s.length()) out.add(s.substring(start));
        return out;
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }
}
