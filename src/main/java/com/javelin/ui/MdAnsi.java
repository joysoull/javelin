package com.javelin.ui;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简 Markdown → ANSI 转义序列渲染器。
 *
 * 覆盖终端中最常用的 5 种语法，不引第三方库。
 * 管线：先按行处理代码围栏和标题，再按片处理行内语法。
 */
public final class MdAnsi {

    private MdAnsi() {}

    /** 将 Markdown 字符串转为带 ANSI 样式的字符串 */
    public static String render(String md) {
        if (md == null || md.isEmpty()) return "";

        StringBuilder out = new StringBuilder();
        String[] lines = md.split("\n", -1);
        boolean inFence = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // 代码围栏 ```...```
            if (line.strip().startsWith("```")) {
                inFence = !inFence;
                if (line.strip().length() > 3) {
                    // 带语言标记的首行：显示语言标签
                    out.append(Ansi.dim(line.strip().substring(3).trim())).append('\n');
                }
                continue;
            }
            if (inFence) {
                out.append(Ansi.dim(line)).append('\n');
                continue;
            }

            // 标题 # ## ### ...
            Matcher hm = Pattern.compile("^(#{1,6})\\s+(.+)").matcher(line);
            if (hm.matches()) {
                out.append(Ansi.bold(Ansi.wrap(Ansi.UNDERLINE, line))).append('\n');
                continue;
            }

            // 行内渲染：粗体、斜体、code
            out.append(renderInline(line)).append('\n');
        }
        return out.toString();
    }

    /** 渲染行内语法：**粗体**、*斜体*、`code` */
    private static String renderInline(String line) {
        // **粗体**
        line = replaceAll(line, "\\*\\*(.+?)\\*\\*", m -> Ansi.bold(m.group(1)));
        // *斜体*
        line = replaceAll(line, "\\*(?![\\s*])(.+?)\\*", m -> Ansi.dim(m.group(1)));
        // `code`
        line = replaceAll(line, "`([^`]+)`", m -> Ansi.gray(m.group(1)));
        return line;
    }

    @FunctionalInterface
    private interface Replacer {
        String apply(Matcher m);
    }

    private static String replaceAll(String input, String regex, Replacer fn) {
        Matcher m = Pattern.compile(regex).matcher(input);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(input, last, m.start());
            sb.append(fn.apply(m));
            last = m.end();
        }
        sb.append(input, last, input.length());
        return sb.toString();
    }
}
