package com.javelin;

import com.anthropic.errors.AnthropicServiceException;
import com.javelin.agent.Agent;
import com.javelin.agent.PlanAndExecuteAgent;
import com.javelin.config.DotEnv;
import com.javelin.llm.LlmProvider;
import com.javelin.llm.impl.AnthropicProvider;
import com.javelin.llm.impl.OpenAICompatProvider;
import com.javelin.tool.ToolRegistry;
import com.javelin.tool.builtin.CalculatorTool;
import com.javelin.tool.builtin.GlobTool;
import com.javelin.tool.builtin.GrepTool;
import com.javelin.tool.builtin.ListDirTool;
import com.javelin.tool.builtin.ReadFileTool;
import com.javelin.tool.builtin.WriteFileTool;
import com.javelin.ui.Ansi;
import com.javelin.ui.Box;
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
import java.util.Optional;

/**
 * 命令行 REPL。
 *
 * Provider 选择逻辑：
 *   LLM_PROVIDER=openai    → OpenAI 协议（DeepSeek / GLM / Kimi / 智谱 …）
 *   LLM_PROVIDER=anthropic → Anthropic 协议
 *   未设置                 → 自动检测：有 ANTHROPIC_BASE_URL 用 anthropic，否则用 openai
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

        Optional<String> apiKey = dotenv.getOrEnv("LLM_API_KEY");
        if (apiKey.isEmpty()) {
            out.println(Ansi.red("[error] 未找到 LLM_API_KEY。请任选一种："));
            out.println("  1) 在 " + envPath + " 写入 LLM_API_KEY=你的key");
            out.println("  2) 设置环境变量 LLM_API_KEY");
            System.exit(1);
        }
        String baseUrl = dotenv.getOrEnv("LLM_BASE_URL").orElse(null);
        String modelId = dotenv.getOrEnv("LLM_MODEL").orElse(null);
        String providerChoice = dotenv.getOrEnv("LLM_PROVIDER").orElse(null);
        boolean thinkingDisabled = "disabled".equalsIgnoreCase(dotenv.getOrEnv("LLM_THINKING").orElse(""));

        // ── 创建 Provider ──
        LlmProvider llm;
        if ("anthropic".equalsIgnoreCase(providerChoice)) {
            llm = new AnthropicProvider(apiKey.get(), baseUrl, modelId);
        } else if ("openai".equalsIgnoreCase(providerChoice)) {
            llm = new OpenAICompatProvider(apiKey.get(), baseUrl, modelId, thinkingDisabled);
        } else if (providerChoice != null && !providerChoice.isBlank()) {
            out.println(Ansi.red("[error] 未知 LLM_PROVIDER=" + providerChoice + "，可选：anthropic / openai"));
            System.exit(1);
            return;
        } else {
            llm = new OpenAICompatProvider(apiKey.get(), baseUrl, modelId, thinkingDisabled);
        }

        // ── 工具与 Agent ──
        ToolRegistry tools = new ToolRegistry()
                .register(new CalculatorTool())
                .register(new ReadFileTool())
                .register(new WriteFileTool())
                .register(new ListDirTool())
                .register(new GrepTool())
                .register(new GlobTool());
        ConsoleListener console = new ConsoleListener(out);
        Agent reactAgent = new Agent(llm, tools, SYSTEM_PROMPT, console);
        PlanAndExecuteAgent planAgent = new PlanAndExecuteAgent(llm, tools, SYSTEM_PROMPT, console);

        // 当前运行模式：react 或 plan，默认 react
        String[] mode = { "react" };

        // ── JLine 终端 + 行读取器 ──
        Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .encoding(StandardCharsets.UTF_8)
                .build();
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new StringsCompleter("/help", "/tools", "/mode", "/clear", "/exit"))
                .build();

        printBanner(out, llm.getClass().getSimpleName(), modelId, baseUrl, tools);

        String prompt = Ansi.bold(Ansi.cyan("you")) + Ansi.gray(" › ");
        while (true) {
            String line;
            try {
                line = reader.readLine(prompt);
            } catch (UserInterruptException e) { continue;
            } catch (EndOfFileException e) {
                out.println(Ansi.gray("bye."));
                return;
            }
            if (line == null) return;
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("/")) {
                if (handleSlash(out, line, tools, mode)) return;
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
                out.println(Box.render(Ansi.RED, "api error", Ansi.RED, detail.toString()));
            } catch (RuntimeException e) {
                out.println(Box.render(Ansi.RED, "error", Ansi.RED, e.getMessage()));
            }
        }
    }

    private static boolean handleSlash(PrintStream out, String line, ToolRegistry tools, String[] mode) {
        switch (line) {
            case "/exit", "/quit" -> { out.println(Ansi.gray("bye.")); return true; }
            case "/clear" -> {
                out.print("[H[2J"); out.flush();
            }
            case "/tools" -> {
                StringBuilder sb = new StringBuilder();
                for (com.javelin.tool.Tool t : tools.all()) {
                    sb.append(Ansi.bold(t.name())).append('\n');
                    sb.append(Ansi.gray("  " + t.description())).append('\n');
                }
                out.println(Box.render(Ansi.CYAN, "tools", null, sb.toString().stripTrailing()));
            }
            case "/help" -> out.println(Box.render(Ansi.CYAN, "help", null, """
                    /help    帮助
                    /tools   列出工具
                    /mode    切换模式 (react / plan)
                    /clear   清屏
                    /exit    退出
                    快捷键：↑↓ 翻历史  Ctrl+A/E 行首/行尾  Ctrl+D 退出"""));
            case "/mode" -> {
                out.println(Ansi.gray("当前模式: " + mode[0] + "。使用 /mode react 或 /mode plan 切换。"));
            }
            default -> {
                if (line.startsWith("/mode ")) {
                    String target = line.substring(6).trim().toLowerCase();
                    if ("react".equals(target) || "plan".equals(target)) {
                        mode[0] = target;
                        out.println(Ansi.green("已切换到 " + target + " 模式"));
                    } else {
                        out.println(Ansi.red("未知模式: " + target + "，可用: react / plan"));
                    }
                } else {
                    out.println(Ansi.red("未知命令: " + line + "  （试试 /help）"));
                }
            }
        }
        return false;
    }

    private static void printBanner(PrintStream out, String providerName, String modelId, String baseUrl, ToolRegistry tools) {
        String title = " javelin v0.2 ";
        out.println();
        out.println(Ansi.brightCyan("╭─" + title + "─".repeat(40)));
        out.println(Ansi.brightCyan("│ ") + Ansi.dim("一个学习用的 Claude-Code-like agent"));
        out.println(Ansi.brightCyan("│ ") + Ansi.gray("provider: ") + providerName);
        out.println(Ansi.brightCyan("│ ") + Ansi.gray("model:   ") + (modelId != null ? modelId : "auto"));
        if (baseUrl != null) out.println(Ansi.brightCyan("│ ") + Ansi.gray("endpoint:") + baseUrl);
        out.println(Ansi.brightCyan("│ ") + Ansi.gray("tools:   ") + countTools(tools));
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

    static class ConsoleListener implements Agent.Listener {
        private final PrintStream out;
        ConsoleListener(PrintStream out) { this.out = out; }

        @Override public void onPhase(String label) {
            out.println();
            out.println(Ansi.gray("── " + label + " ──"));
        }
        @Override public void onReasoning(String content) {
            out.println(Box.render(Ansi.BRIGHT_BLACK, "思考过程", Ansi.DIM, content));
        }
        @Override public void onAssistantText(String text) {
            if (text.isEmpty()) return;
            out.println(Box.render(Ansi.WHITE, "助手", null, MdAnsi.render(text)));
        }
        @Override public void onToolUse(String name, String useId, String argumentsJson) {
            out.println(Box.render(Ansi.CYAN, "调用工具 · " + name, Ansi.DIM, argumentsJson));
        }
        @Override public void onToolResult(String name, String useId, String output, boolean isError) {
            String style = isError ? Ansi.RED : Ansi.GREEN;
            String tag = (isError ? "工具错误" : "工具结果") + " · " + name;
            out.println(Box.render(style, tag, null, output));
        }
    }
}
