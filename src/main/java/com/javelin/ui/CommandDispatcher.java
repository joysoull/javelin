package com.javelin.ui;

import com.javelin.tool.Tool;
import com.javelin.tool.ToolRegistry;

import java.io.PrintStream;
import java.util.function.Consumer;

/**
 * 处理 REPL 中的斜杠命令（/help、/tools、/mode、/clear、/exit）。
 *
 * 将命令解析与执行从主循环中抽离，保持 Main 的简洁。
 */
public final class CommandDispatcher {

    private final PrintStream out;
    private final ToolRegistry tools;
    private final Consumer<String> modeSetter;
    private final Runnable onExit;

    public CommandDispatcher(PrintStream out, ToolRegistry tools,
                             Consumer<String> modeSetter, Runnable onExit) {
        this.out = out;
        this.tools = tools;
        this.modeSetter = modeSetter;
        this.onExit = onExit;
    }

    /**
     * 解析并执行命令。
     *
     * @param line 用户输入的完整命令行（以 / 开头）
     * @return true 表示接收到退出命令
     */
    public boolean dispatch(String line) {
        switch (line) {
            case "/exit", "/quit" -> {
                out.println(Ansi.gray("👋 再见"));
                onExit.run();
                return true;
            }
            case "/clear" -> {
                out.print("[H[2J");
                out.flush();
            }
            case "/tools" -> printTools();
            case "/help" -> printHelp();
            case "/mode" -> out.println(Ansi.gray("ℹ️  当前模式未知。使用 /mode react 或 /mode plan 切换。"));
            default -> {
                if (line.startsWith("/mode ")) {
                    handleMode(line.substring(6).trim().toLowerCase());
                } else {
                    out.println(Ansi.red("❓ 未知命令: " + line + "  （试试 /help）"));
                }
            }
        }
        return false;
    }

    private void printTools() {
        StringBuilder sb = new StringBuilder();
        for (Tool t : tools.all()) {
            sb.append(Ansi.bold(t.name())).append('\n');
            sb.append(Ansi.gray("  " + t.description())).append('\n');
        }
        out.println(Box.render(Ansi.CYAN, "🛠️ 工具列表", null, sb.toString().stripTrailing()));
    }

    private void printHelp() {
        out.println(Box.render(Ansi.CYAN, "❓ 帮助", null, """
                /help    帮助
                /tools   列出工具
                /mode    切换模式 (react / plan)
                /clear   清屏
                /exit    退出
                快捷键：↑↓ 翻历史  Ctrl+A/E 行首/行尾  Ctrl+D 退出"""));
    }

    private void handleMode(String target) {
        if ("react".equals(target) || "plan".equals(target)) {
            modeSetter.accept(target);
            out.println(Ansi.green("✅ 已切换到 " + target + " 模式"));
        } else {
            out.println(Ansi.red("❌ 未知模式: " + target + "，可用: react / plan"));
        }
    }
}
