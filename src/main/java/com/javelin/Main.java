package com.javelin;

import com.anthropic.errors.AnthropicServiceException;
import com.javelin.agent.Agent;
import com.javelin.agent.PlanAndExecuteAgent;
import com.javelin.config.DotEnv;
import com.javelin.config.ProviderFactory;
import com.javelin.llm.LlmProvider;
import com.javelin.tool.ToolRegistry;
import com.javelin.tool.builtin.CalculatorTool;
import com.javelin.tool.builtin.EditFileTool;
import com.javelin.tool.builtin.GlobTool;
import com.javelin.tool.builtin.GrepTool;
import com.javelin.tool.builtin.ListDirTool;
import com.javelin.tool.builtin.ReadFileTool;
import com.javelin.tool.builtin.WriteFileTool;
import com.javelin.ui.Ansi;
import com.javelin.ui.Box;
import com.javelin.ui.CommandDispatcher;
import com.javelin.ui.ConsoleListener;
import com.javelin.ui.MdAnsi;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 命令行 REPL 入口。
 *
 * 职责：读取配置 → 组装依赖 → 启动交互循环。
 * 具体配置解析、Provider 创建、命令分发已拆到对应包中。
 */
public class Main {

    private static final String SYSTEM_PROMPT = """
            你是 javelin —— 一个辅助编程的命令行 agent。
            你可以使用文件读写、搜索、目录浏览等工具来理解和修改代码。
            涉及数值计算时使用 calculator 工具，不要心算。
            修改文件前先 read_file 确认当前内容，写入后简要说明改动。
            回答简洁、直接，使用中文。
            """;

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);

        // ── 配置加载 ──
        Path envPath = Paths.get(".env").toAbsolutePath();
        DotEnv dotenv = DotEnv.loadOrEmpty(envPath);

        // ── 创建 Provider ──
        LlmProvider llm;
        try {
            llm = ProviderFactory.create(dotenv, out);
        } catch (RuntimeException e) {
            System.exit(1);
            return;
        }

        // ── 工具与 Agent ──
        ToolRegistry tools = new ToolRegistry()
                .register(new CalculatorTool())
                .register(new ReadFileTool())
                .register(new WriteFileTool())
                .register(new EditFileTool())
                .register(new ListDirTool())
                .register(new GrepTool())
                .register(new GlobTool());
        ConsoleListener console = new ConsoleListener(out);
        Agent reactAgent = new Agent(llm, tools, SYSTEM_PROMPT, console);
        PlanAndExecuteAgent planAgent = new PlanAndExecuteAgent(llm, tools, SYSTEM_PROMPT, console);

        String[] mode = { "react" };
        AtomicBoolean running = new AtomicBoolean(true);

        CommandDispatcher dispatcher = new CommandDispatcher(
                out, tools,
                target -> mode[0] = target,
                () -> running.set(false));

        // ── JLine 终端 + 行读取器 ──
        Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .encoding(StandardCharsets.UTF_8)
                .build();
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new StringsCompleter("/help", "/tools", "/mode", "/clear", "/exit"))
                .build();

        printBanner(out, llm.getClass().getSimpleName(),
                dotenv.getOrEnv("LLM_MODEL").orElse(null),
                dotenv.getOrEnv("LLM_BASE_URL").orElse(null),
                tools);

        String prompt = Ansi.bold(Ansi.cyan("你")) + Ansi.gray(" › ");
        while (running.get()) {
            String line;
            try {
                line = reader.readLine(prompt);
            } catch (UserInterruptException e) {
                continue;
            } catch (EndOfFileException e) {
                out.println(Ansi.gray("bye."));
                return;
            }
            if (line == null) return;
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("/")) {
                if (dispatcher.dispatch(line)) return;
                continue;
            }

            try {
                String reply = "plan".equals(mode[0])
                        ? planAgent.chat(line)
                        : reactAgent.chat(line);
                if (!reply.isEmpty()) {
                    out.println();
                    out.println(Box.render(Ansi.GREEN, "回答", null, MdAnsi.render(reply)));
                }
            } catch (AnthropicServiceException e) {
                StringBuilder detail = new StringBuilder();
                detail.append("HTTP ").append(e.statusCode());
                e.errorType().ifPresent(t -> detail.append("  type=").append(t));
                detail.append('\n').append("body: ").append(e.body());
                out.println(Box.render(Ansi.RED, "⚠️ API 错误", Ansi.RED, detail.toString()));
            } catch (RuntimeException e) {
                out.println(Box.render(Ansi.RED, "⚠️ 错误", Ansi.RED, e.getMessage()));
            }
        }
    }

    private static void printBanner(PrintStream out, String providerName,
                                    String modelId, String baseUrl, ToolRegistry tools) {
        String title = " javelin v0.2 ";
        out.println();
        out.println(Ansi.brightCyan("╭─" + title + "─".repeat(40)));
        out.println(Ansi.brightCyan("│ ") + Ansi.dim("一个学习用的 Claude-Code-like agent"));
        out.println(Ansi.brightCyan("│ ") + Ansi.gray("⚡ 提供商: ") + providerName);
        out.println(Ansi.brightCyan("│ ") + Ansi.gray("🤖 模型:   ") + (modelId != null ? modelId : "auto"));
        if (baseUrl != null) out.println(Ansi.brightCyan("│ ") + Ansi.gray("🔗 端点:  ") + baseUrl);
        out.println(Ansi.brightCyan("│ ") + Ansi.gray("🛠️ 工具:   ") + countTools(tools));
        out.println(Ansi.brightCyan("│ ") + Ansi.gray("/help 查看命令  ·  /exit 退出"));
        out.println(Ansi.brightCyan("╰─" + "─".repeat(title.length() + 41)));
        out.println();
    }

    private static String countTools(ToolRegistry tools) {
        StringBuilder names = new StringBuilder();
        for (com.javelin.tool.Tool t : tools.all()) {
            if (names.length() > 0) names.append(", ");
            names.append(t.name());
        }
        return names.toString();
    }
}
