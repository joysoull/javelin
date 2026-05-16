package com.javelin.ui;

import com.javelin.agent.AgentListener;

import java.io.PrintStream;

/**
 * 将 Agent 事件渲染为 ANSI 着色的终端输出。
 *
 * 实现 {@link AgentListener} 的 5 个回调，把每一步 ReAct / Plan-and-Execute 流程
 * 以 Claude Code 风格的圆角框输出到终端，让用户肉眼看到思考、工具调用和结果。
 */
public final class ConsoleListener implements AgentListener {

    private final PrintStream out;

    public ConsoleListener(PrintStream out) {
        this.out = out;
    }

    @Override
    public void onPhase(String label) {
        out.println();
        out.println(Ansi.gray("── " + label + " ──"));
    }

    @Override
    public void onReasoning(String content) {
        out.println(Box.render(Ansi.BRIGHT_BLACK, "思考过程", Ansi.DIM, content));
    }

    @Override
    public void onAssistantText(String text) {
        if (text.isEmpty()) return;
        out.println(Box.render(Ansi.WHITE, "助手", null, MdAnsi.render(text)));
    }

    @Override
    public void onToolUse(String name, String useId, String argumentsJson) {
        out.println(Box.render(Ansi.CYAN, "调用工具 · " + name, Ansi.DIM, argumentsJson));
    }

    @Override
    public void onToolResult(String name, String useId, String output, boolean isError) {
        String style = isError ? Ansi.RED : Ansi.GREEN;
        String tag = (isError ? "工具错误" : "工具结果") + " · " + name;
        out.println(Box.render(style, tag, null, output));
    }
}
