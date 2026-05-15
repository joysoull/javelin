package com.javelin.ui;

/**
 * ANSI 终端颜色 / 样式工具。
 *
 * 设计原则：
 * - 不依赖 Jansi —— 现代 Windows 10+ 终端、所有 *nix 终端原生支持 ANSI escape
 * - 全部用静态方法 + 常量字符串，调用方写起来短，"绿色文本" → `Ansi.green("...")`
 * - 想完全禁用颜色时，把 {@link #enabled} 设为 false，所有 helper 退化为原样返回
 *
 * 颜色风格故意贴近 Claude Code：
 * - 用户提示词：粗体白
 * - assistant 文本：白
 * - 工具调用：青/蓝灰，弱化存在感
 * - 工具结果：绿（成功）/ 红（错误）
 * - 系统提示 / 状态信息：灰（dim）
 */
public final class Ansi {

    public static boolean enabled = true;

    private static final String ESC = "[";
    public static final String RESET = ESC + "0m";

    public static final String BOLD = ESC + "1m";
    public static final String DIM = ESC + "2m";
    public static final String ITALIC = ESC + "3m";
    public static final String UNDERLINE = ESC + "4m";

    public static final String BLACK = ESC + "30m";
    public static final String RED = ESC + "31m";
    public static final String GREEN = ESC + "32m";
    public static final String YELLOW = ESC + "33m";
    public static final String BLUE = ESC + "34m";
    public static final String MAGENTA = ESC + "35m";
    public static final String CYAN = ESC + "36m";
    public static final String WHITE = ESC + "37m";

    public static final String BRIGHT_BLACK = ESC + "90m"; // = gray
    public static final String BRIGHT_CYAN = ESC + "96m";

    private Ansi() {}

    public static String wrap(String style, String text) {
        return enabled ? style + text + RESET : text;
    }

    public static String bold(String s)    { return wrap(BOLD, s); }
    public static String dim(String s)     { return wrap(DIM, s); }
    public static String red(String s)     { return wrap(RED, s); }
    public static String green(String s)   { return wrap(GREEN, s); }
    public static String yellow(String s)  { return wrap(YELLOW, s); }
    public static String cyan(String s)    { return wrap(CYAN, s); }
    public static String gray(String s)    { return wrap(BRIGHT_BLACK, s); }
    public static String brightCyan(String s) { return wrap(BRIGHT_CYAN, s); }
}
