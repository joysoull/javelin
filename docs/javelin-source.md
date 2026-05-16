# javelin Source Code

This file contains the complete source code of the javelin project, organized by layer.

---

## 1. Entry

### `src/main/java/com/javelin/Main.java`

REPL 入口，负责配置加载、Provider 选择、工具注册、JLine 终端和主循环。

```java
package com.javelin;

import com.anthropic.errors.AnthropicServiceException;
import com.javelin.agent.Agent;
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
        Agent agent = new Agent(llm, tools, SYSTEM_PROMPT, new ConsoleListener(out));

        // ── JLine 终端 + 行读取器 ──
        Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .encoding(StandardCharsets.UTF_8)
                .build();
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new StringsCompleter("/help", "/tools", "/clear", "/exit"))
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
                if (handleSlash(out, line, tools)) return;
                continue;
            }

            try {
                String reply = agent.chat(line);
                out.println();
                out.println(Box.render(Ansi.GREEN, "回答", null, MdAnsi.render(reply)));
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

    private static boolean handleSlash(PrintStream out, String line, ToolRegistry tools) {
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
                    /clear   清屏
                    /exit    退出
                    快捷键：↑↓ 翻历史  Ctrl+A/E 行首/行尾  Ctrl+D 退出"""));
            default -> out.println(Ansi.red("未知命令: " + line + "  （试试 /help）"));
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

    // 注意：ConsoleListener 已提取到 ui/ConsoleListener.java，实现 AgentListener 接口
    static class ConsoleListener implements AgentListener {
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
```

---

## 2. Agent

### `src/main/java/com/javelin/agent/Agent.java`

ReAct 主循环，每轮用户输入后反复调用 LLM 直到获得最终答复，支持工具调用回灌。

```java
package com.javelin.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javelin.llm.*;
import com.javelin.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 主循环，实现 ReAct（Reasoning + Acting）模式。
 *
 * 每轮用户输入后，Agent 重复以下步骤直到 LLM 给出最终答复：
 *   1. 将消息历史 + 工具列表发送给 LLM
 *   2. 接收 LLM 响应（可能含文本、工具调用，或两者都有）
 *   3. 如果 stopReason 是 tool_use / tool_calls：
 *      a. 本地执行所有工具调用
 *      b. 将 assistant 消息和 tool_result 消息追加到历史
 *      c. 回到步骤 1
 *   4. 如果 stopReason 是 end_turn / stop，返回文本给调用方
 *
 * Agent 只依赖 LlmProvider 接口，不耦合任何具体 SDK。
 * 具体走 Anthropic 还是 OpenAI 协议，由外部注入的 Provider 实现决定。
 *
 * 单次 chat() 最多 N 轮 LLM 调用，超过则抛异常，防止死循环。
 */
public class Agent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单次 chat() 调用中最多允许的 LLM 往返次数 */
    private static final int MAX_ITERATIONS = 10;

    private final LlmProvider llm;
    private final ToolRegistry tools;
    private final String systemPrompt;
    private final Listener listener;

    /**
     * @param llm          LLM Provider，负责与具体 API 通信
     * @param tools        当前可用的工具注册表
     * @param systemPrompt 系统提示词，可为 null
     * @param listener     观察者，用于将 ReAct 每一步输出到 UI；为 null 时静默运行
     */
    public Agent(LlmProvider llm, ToolRegistry tools, String systemPrompt, Listener listener) {
        this.llm = llm;
        this.tools = tools;
        this.systemPrompt = systemPrompt;
        this.listener = listener != null ? listener : Listener.NOOP;
    }

    /**
     * 处理一次用户输入，运行完整 ReAct 循环直到 LLM 返回终结响应。
     *
     * 以一次工具调用为例，消息历史的累积过程：
     *   [0] user:       "请计算 23 * 47"
     *   [1] assistant:  text="我来算", toolCalls=[calculator(23*47)]    // LLM 第 1 次响应
     *   [2] user:       toolResults=[calculator -> "1081"]              // 本地执行结果
     *   [3] assistant:  text="23 * 47 = 1081"                          // LLM 第 2 次响应, stopReason=end_turn
     *
     * @param userInput 用户在 REPL 输入的原始文本
     * @return LLM 最终回复的文本内容
     * @throws RuntimeException 超过 MAX_ITERATIONS 时抛出
     */
    public String chat(String userInput) {
        // 消息历史：累积整个对话中所有的 user / assistant / tool_result 消息
        List<LlmMessage> history = new ArrayList<>();
        history.add(LlmMessage.user(userInput));

        // 将 ToolRegistry 中的工具转为 ToolDef 列表。
        // 工具集在一次对话中是固定的，所以提前构建，每次迭代直接复用。
        List<ToolDef> toolDefs = new ArrayList<>();
        for (com.javelin.tool.Tool t : tools.all()) {
            toolDefs.add(new ToolDef(t.name(), t.description(), t.inputSchema().toString()));
        }

        for (int iter = 1; iter <= MAX_ITERATIONS; iter++) {
            // 阶段标签：第一轮是首次思考，后续是 LLM 看到工具结果后的再思考
            String phase = iter == 1 ? "思考中…" : "整合工具结果…";
            listener.onPhase(phase);

            // 1. 调用 LLM，传入完整消息历史、工具定义、系统提示词
            LlmResponse resp = llm.chat(history, toolDefs, systemPrompt);

            // 2. 展示推理模型的思考过程
            if (!resp.reasoningContent().isEmpty()) {
                listener.onReasoning(resp.reasoningContent());
            }

            // 3. 将本轮 assistant 消息（含文本、工具调用、推理内容）追加到历史
            String assistantText = resp.text();
            history.add(LlmMessage.assistant(assistantText, resp.toolCalls(), resp.reasoningContent()));

            // 4. 需要执行工具 → 展示文本和工具调用，执行后继续循环
            if (resp.needsToolExecution()) {
                if (!assistantText.isEmpty()) listener.onAssistantText(assistantText);
                for (ToolCall tc : resp.toolCalls()) {
                    listener.onToolUse(tc.name(), tc.id(), tc.argumentsJson());
                }
                List<LlmMessage.ToolResultBlock> results = new ArrayList<>();
                for (ToolCall tc : resp.toolCalls()) {
                    results.add(runTool(tc));
                }
                history.add(LlmMessage.toolResults(results));
                continue;
            }

            // 5. 无工具调用 → LLM 已给出最终答复，不重复展示（由调用方渲染最终结果）
            return assistantText;
        }

        throw new RuntimeException("Agent exceeded MAX_ITERATIONS=" + MAX_ITERATIONS
                + "，怀疑陷入死循环。检查工具实现或提示词。");
    }

    /**
     * 执行单个工具调用。
     *
     * 工具查找失败或执行抛异常时不会向上传播，而是以 ToolResultBlock(isError=true) 返回。
     * LLM 看到错误信息后可能自行修正重试——这个机制称为"错误回灌"。
     *
     * @param tc LLM 返回的工具调用，含 id、工具名、arguments JSON
     * @return 工具执行结果，toolCallId 与入参一致
     */
    private LlmMessage.ToolResultBlock runTool(ToolCall tc) {
        com.javelin.tool.Tool tool = tools.get(tc.name());
        if (tool == null) {
            String msg = "error: unknown tool '" + tc.name() + "'";
            listener.onToolResult(tc.name(), tc.id(), msg, true);
            return new LlmMessage.ToolResultBlock(tc.id(), msg, true);
        }
        try {
            JsonNode input = MAPPER.readTree(tc.argumentsJson());
            String output = tool.execute(input);
            listener.onToolResult(tc.name(), tc.id(), output, false);
            return new LlmMessage.ToolResultBlock(tc.id(), output, false);
        } catch (Exception e) {
            String msg = "error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            listener.onToolResult(tc.name(), tc.id(), msg, true);
            return new LlmMessage.ToolResultBlock(tc.id(), msg, true);
        }
    }

    /**
     * ReAct 循环的观察者，让 REPL 层能把每一步渲染到终端。
     * 所有方法都是 default 空实现，调用方按需 override。
     */
    public interface Listener {
        Listener NOOP = new Listener() {};

        /** LLM 调用开始时的阶段描述：首轮为"思考中…"，后续为"整合工具结果…" */
        default void onPhase(String label) {}

        /** 推理模型返回的思考过程（DeepSeek reasoning_content 等） */
        default void onReasoning(String content) {}

        /** LLM 在发起工具调用的同时返回了文本（非最终回复） */
        default void onAssistantText(String text) {}

        /** LLM 发起了一个工具调用 */
        default void onToolUse(String name, String useId, String argumentsJson) {}

        /** 工具执行完毕 */
        default void onToolResult(String name, String useId, String output, boolean isError) {}
    }
}
```

---

## 3. LLM Layer

### `src/main/java/com/javelin/llm/LlmProvider.java`

LLM Provider 抽象接口，Agent 依赖此接口与任何 LLM API 解耦。

```java
package com.javelin.llm;

import java.util.List;

/**
 * LLM Provider 抽象接口。
 *
 * 这个接口让 Agent 不再依赖任何一个具体 SDK（Anthropic / OpenAI / ...）。
 * 添加新 provider 只需实现这个接口，Agent 代码一行不用动。
 *
 * 注意：只暴露同步、非流式接口。流式留到后续期次。
 */
public interface LlmProvider {

    /**
     * 发送一条对话请求，可能返回文本、工具调用，或两者都有。
     *
     * @param messages  完整的历史消息（包含当日轮次的 user 输入、往轮 assistant/tool 等）
     * @param tools     当前 Agent 拥有的工具定义。空列表 = 不给 LLM 任何工具
     * @param systemPrompt  系统提示词，可为 null
     * @return  LLM 回复，包含 text 和/或 toolCalls
     */
    LlmResponse chat(List<LlmMessage> messages, List<ToolDef> tools, String systemPrompt);
}
```

### `src/main/java/com/javelin/llm/LlmMessage.java`

对话消息的中性表示，支持 text、tool_use、tool_result、reasoning_content 等 content block。

```java
package com.javelin.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条对话消息，Provider 无关的中性表示。
 *
 * 参考 Anthropic Messages API 的多 content_block 设计：
 * 一条 assistant 消息可同时包含 text、tool_use、reasoning_content。
 * tool_result 消息是 user 角色，含 tool_use_id 和内容。
 */
public class LlmMessage {

    public enum Role {
        USER("user"),
        ASSISTANT("assistant");

        private final String label;
        Role(String label) { this.label = label; }
        public String label() { return label; }
    }

    private final Role role;
    private final String text;
    private final String reasoningContent;
    private final List<ToolCall> toolCalls;
    private final List<ToolResultBlock> toolResults;

    private LlmMessage(Role role, String text, String reasoningContent,
                       List<ToolCall> toolCalls, List<ToolResultBlock> toolResults) {
        this.role = role;
        this.text = text;
        this.reasoningContent = reasoningContent != null ? reasoningContent : "";
        this.toolCalls = toolCalls == null ? List.of() : toolCalls;
        this.toolResults = toolResults == null ? List.of() : toolResults;
    }

    public Role role()                       { return role; }
    public String text()                     { return text; }
    public String reasoningContent()         { return reasoningContent; }
    public List<ToolCall> toolCalls()        { return toolCalls; }
    public List<ToolResultBlock> toolResults() { return toolResults; }

    public boolean hasToolCalls()   { return !toolCalls.isEmpty(); }
    public boolean hasToolResults() { return !toolResults.isEmpty(); }

    // ── factories ──

    public static LlmMessage user(String text) {
        return new LlmMessage(Role.USER, text, null, null, null);
    }

    public static LlmMessage toolResults(List<ToolResultBlock> results) {
        return new LlmMessage(Role.USER, null, null, null, results);
    }

    /**
     * 构造 assistant 消息。
     *
     * @param text             LLM 输出的文本
     * @param toolCalls        LLM 发起的工具调用列表
     * @param reasoningContent DeepSeek 等推理模型的思考过程，不需要时传 "" 或 null
     */
    public static LlmMessage assistant(String text, List<ToolCall> toolCalls, String reasoningContent) {
        return new LlmMessage(Role.ASSISTANT, text, reasoningContent, toolCalls, null);
    }

    public record ToolResultBlock(String toolCallId, String content, boolean isError) {}
}
```

### `src/main/java/com/javelin/llm/LlmResponse.java`

LLM 的一次完整回复，包含文本、工具调用、推理内容和停止原因。

```java
package com.javelin.llm;

import java.util.List;

/**
 * LLM 的一次完整回复。
 *
 * 可以同时包含文本、工具调用、推理内容（thinking/reasoning）。
 * Agent 根据 stopReason 判断是继续循环还是结束。
 */
public class LlmResponse {

    private final String text;
    private final List<ToolCall> toolCalls;
    private final String stopReason;
    /** DeepSeek 等推理模型的思考过程。无推理能力或关闭思考时为空字符串 */
    private final String reasoningContent;

    public LlmResponse(String text, List<ToolCall> toolCalls, String stopReason, String reasoningContent) {
        this.text = text;
        this.toolCalls = toolCalls;
        this.stopReason = stopReason;
        this.reasoningContent = reasoningContent != null ? reasoningContent : "";
    }

    public String text()              { return text; }
    public List<ToolCall> toolCalls() { return toolCalls; }
    public String stopReason()        { return stopReason; }
    public String reasoningContent()  { return reasoningContent; }

    /** stopReason 为 tool_use（Anthropic）或 tool_calls（OpenAI）时需要执行工具并继续循环 */
    public boolean needsToolExecution() {
        return "tool_use".equals(stopReason) || "tool_calls".equals(stopReason);
    }
}
```

### `src/main/java/com/javelin/llm/ToolCall.java`

LLM 返回的工具调用中性表示，统一 OpenAI/anthropic 的 arguments 格式。

```java
package com.javelin.llm;

import java.util.List;

/**
 * LLM 返回的工具调用 —— 中性表示。
 *
 * 注意：OpenAI 的 arguments 是 JSON 字符串，Anthropic 的 input 是 JSON 对象。
 * 这里统一存为 JSON 字符串（从 Anthropic 的 JsonValue 做一次 toString）。
 */
public class ToolCall {

    private final String id;
    private final String name;
    /** JSON 格式的参数字符串，可直接传给 JSON 解析器 */
    private final String argumentsJson;

    public ToolCall(String id, String name, String argumentsJson) {
        this.id = id;
        this.name = name;
        this.argumentsJson = argumentsJson;
    }

    public String id()            { return id; }
    public String name()          { return name; }
    public String argumentsJson() { return argumentsJson; }
}
```

### `src/main/java/com/javelin/llm/ToolDef.java`

中性工具定义，将工具名称、描述和 JSON Schema 参数打包为 LLM 可理解的格式。

```java
package com.javelin.llm;

import java.util.List;

/**
 * 中性工具定义 —— 不依赖任何 SDK 的结构。
 *
 * Anthropic 叫 input_schema，OpenAI 叫 parameters，
 * 格式都是 JSON Schema (type: object, properties, required)，所以用 Map 直接存就够了。
 */
public class ToolDef {

    private final String name;
    private final String description;
    /** JSON Schema of the parameters, as a raw JSON string. */
    private final String parametersJson;

    public ToolDef(String name, String description, String parametersJson) {
        this.name = name;
        this.description = description;
        this.parametersJson = parametersJson;
    }

    public String name()        { return name; }
    public String description() { return description; }
    public String parametersJson() { return parametersJson; }
}
```

---

## 4. LLM Implementations

### `src/main/java/com/javelin/llm/impl/AnthropicProvider.java`

Anthropic SDK 的 LlmProvider 实现，适配 Messages API 和工具调用协议。

```java
package com.javelin.llm.impl;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javelin.llm.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 把已有的 anthropic-java SDK 包装成 {@link LlmProvider} 接口。
 *
 * 这个类封装了所有 Anthropic 协议特有逻辑：
 * - input_schema → Tool.InputSchema
 * - messages 数组拼接（assistant → toParam → addMessage；tool_result 是 user 角色）
 * - 响应解析（content[] 里的 text / tool_use 块）
 * - stop_reason → "end_turn" / "tool_use"
 */
public class AnthropicProvider implements LlmProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AnthropicClient client;
    private final String modelId;

    public AnthropicProvider(String apiKey, String baseUrl, String modelId) {
        AnthropicOkHttpClient.Builder cb = AnthropicOkHttpClient.builder().apiKey(apiKey);
        if (baseUrl != null && !baseUrl.isBlank()) cb.baseUrl(baseUrl);
        this.client = cb.build();
        this.modelId = modelId != null && !modelId.isBlank() ? modelId : "claude-sonnet-4-5-20250929";
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages, List<ToolDef> tools, String systemPrompt) {
        // Neutral messages → SDK MessageParam list
        List<MessageParam> params = new ArrayList<>();
        for (LlmMessage m : messages) {
            if (m.role() == LlmMessage.Role.USER) {
                if (m.hasToolResults()) {
                    // 打包 tool_result 块进一条 user 消息
                    List<ContentBlockParam> blocks = new ArrayList<>();
                    for (LlmMessage.ToolResultBlock tr : m.toolResults()) {
                        blocks.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                .toolUseId(tr.toolCallId())
                                .content(tr.content())
                                .isError(tr.isError())
                                .build()));
                    }
                    params.add(MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .contentOfBlockParams(blocks)
                            .build());
                } else {
                    params.add(MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .content(m.text())
                            .build());
                }
            } else {
                // assistant 消息：带 tool_use 块
                List<ContentBlockParam> blocks = new ArrayList<>();
                if (m.text() != null && !m.text().isEmpty()) {
                    blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(m.text()).build()));
                }
                for (ToolCall tc : m.toolCalls()) {
                    try {
                        JsonNode inputNode = MAPPER.readTree(tc.argumentsJson());
                        blocks.add(ContentBlockParam.ofToolUse(
                            ToolUseBlockParam.builder()
                                .id(tc.id())
                                .name(tc.name())
                                .input(JsonValue.fromJsonNode(inputNode))
                                .build()));
                    } catch (Exception e) {
                        throw new RuntimeException("无法解析 tool call arguments: " + tc.argumentsJson(), e);
                    }
                }
                params.add(MessageParam.builder()
                        .role(MessageParam.Role.ASSISTANT)
                        .contentOfBlockParams(blocks)
                        .build());
            }
        }

        // SDK Tool 列表
        List<ToolUnion> sdkTools = new ArrayList<>();
        for (ToolDef td : tools) sdkTools.add(toSdkTool(td));

        // 调 API
        MessageCreateParams.Builder req = MessageCreateParams.builder()
                .maxTokens(2048L)
                .messages(params)
                .tools(sdkTools);
        if (systemPrompt != null && !systemPrompt.isEmpty()) req.system(systemPrompt);
        req.model(modelId);

        Message resp = client.messages().create(req.build());

        // 解析响应
        String text = "";
        List<ToolCall> toolCalls = new ArrayList<>();
        for (ContentBlock block : resp.content()) {
            if (block.isText()) {
                String t = block.text().map(TextBlock::text).orElse("");
                if (!text.isEmpty()) text += "\n";
                text += t;
            } else if (block.isToolUse()) {
                ToolUseBlock tu = block.toolUse().orElseThrow();
                toolCalls.add(new ToolCall(tu.id(), tu.name(), tu._input().toString()));
            }
        }
        String stop = resp.stopReason().map(sr -> sr.asString()).orElse("end_turn");
        return new LlmResponse(text, toolCalls, stop, "");
    }

    /** ToolDef → SDK Tool */
    private static ToolUnion toSdkTool(ToolDef td) {
        try {
            JsonNode schema = MAPPER.readTree(td.parametersJson());
            Tool.InputSchema.Properties.Builder pb = Tool.InputSchema.Properties.builder();
            JsonNode props = schema.get("properties");
            if (props != null && props.isObject()) {
                Iterator<String> it = props.fieldNames();
                while (it.hasNext()) {
                    String k = it.next();
                    pb.putAdditionalProperty(k, JsonValue.fromJsonNode(props.get(k)));
                }
            }
            Tool.InputSchema.Builder sb = Tool.InputSchema.builder().properties(pb.build());
            JsonNode req = schema.get("required");
            if (req != null && req.isArray()) {
                List<String> r = new ArrayList<>();
                req.forEach(n -> r.add(n.asText()));
                sb.required(r);
            }
            return ToolUnion.ofTool(
                Tool.builder().name(td.name()).description(td.description()).inputSchema(sb.build()).build());
        } catch (Exception e) {
            throw new RuntimeException("无法解析 tool schema: " + td.name(), e);
        }
    }
}
```

### `src/main/java/com/javelin/llm/impl/OpenAICompatProvider.java`

OpenAI 协议兼容 Provider，覆盖 DeepSeek、GLM、Kimi 等兼容服务，支持推理模型。

```java
package com.javelin.llm.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javelin.llm.*;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * OpenAI 协议兼容 Provider —— 同时覆盖 DeepSeek、GLM、Kimi、通义千问等。
 *
 * 与 Anthropic 协议的核心差异（对照学习用）：
 * 1. 工具定义：OpenAI 用 function.parameters，Anthropic 用 input_schema（结构相同）
 * 2. 工具调用：OpenAI 是 message.tool_calls[] 字段（独立），Anthropic 是 content[] 里的 tool_use 块
 * 3. 工具结果：OpenAI 是 role:"tool" 消息（tool_call_id），Anthropic 是 user 消息里的 tool_result 块
 * 4. 参数格式：OpenAI arguments 是 JSON 字符串，Anthropic input 是 JSON 对象
 * 5. 停止原因：OpenAI finish_reason:"tool_calls"，Anthropic stop_reason:"tool_use"
 */
public class OpenAICompatProvider implements LlmProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final OpenAIClient client;
    private final String modelId;
    private final boolean thinkingDisabled;

    public OpenAICompatProvider(String apiKey, String baseUrl, String modelId, boolean thinkingDisabled) {
        OpenAIOkHttpClient.Builder cb = OpenAIOkHttpClient.builder().apiKey(apiKey);
        if (baseUrl != null && !baseUrl.isBlank()) cb.baseUrl(baseUrl);
        this.client = cb.build();
        this.modelId = modelId != null && !modelId.isBlank() ? modelId : "deepseek-chat";
        this.thinkingDisabled = thinkingDisabled;
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages, List<ToolDef> tools, String systemPrompt) {
        // ── 1) 构建 SDK 消息列表 ──
        List<ChatCompletionMessageParam> params = new ArrayList<>();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            params.add(ChatCompletionMessageParam.ofSystem(
                ChatCompletionSystemMessageParam.builder().content(systemPrompt).build()));
        }

        for (LlmMessage m : messages) {
            if (m.role() == LlmMessage.Role.USER) {
                if (m.hasToolResults()) {
                    // OpenAI：每个 tool_result 是独立的一条 role:"tool" 消息
                    for (LlmMessage.ToolResultBlock tr : m.toolResults()) {
                        params.add(ChatCompletionMessageParam.ofTool(
                            ChatCompletionToolMessageParam.builder()
                                .toolCallId(tr.toolCallId())
                                .content(tr.content())
                                .build()));
                    }
                } else {
                    params.add(ChatCompletionMessageParam.ofUser(
                        ChatCompletionUserMessageParam.builder().content(m.text()).build()));
                }
            } else {
                // assistant 消息
                List<ChatCompletionMessageToolCall> tcList = new ArrayList<>();
                for (ToolCall tc : m.toolCalls()) {
                    tcList.add(ChatCompletionMessageToolCall.ofFunction(
                        ChatCompletionMessageFunctionToolCall.builder()
                            .id(tc.id())
                            .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                .name(tc.name())
                                .arguments(tc.argumentsJson()) // OpenAI arguments 就是 JSON 字符串
                                .build())
                            .build()));
                }
                ChatCompletionAssistantMessageParam.Builder ab = ChatCompletionAssistantMessageParam.builder();
                if (m.text() != null && !m.text().isEmpty()) ab.content(m.text());
                if (!tcList.isEmpty()) ab.toolCalls(tcList);
                // DeepSeek 推理模型：reasoning_content 必须在后续请求中原样回传
                if (m.reasoningContent() != null && !m.reasoningContent().isEmpty()) {
                    ab.putAdditionalProperty("reasoning_content",
                        JsonValue.from(m.reasoningContent()));
                }
                params.add(ChatCompletionMessageParam.ofAssistant(ab.build()));
            }
        }

        // ── 2) 构建工具列表 ──
        List<ChatCompletionTool> sdkTools = new ArrayList<>();
        for (ToolDef td : tools) sdkTools.add(toSdkTool(td));

        // ── 3) 调 API ──
        ChatCompletionCreateParams.Builder req = ChatCompletionCreateParams.builder()
                .model(modelId)
                .messages(params)
                .maxTokens(2048L);
        if (!sdkTools.isEmpty()) req.tools(sdkTools);

        // DeepSeek 推理模型默认开启 thinking，通过 extra body 参数关掉
        if (thinkingDisabled) {
            req.putAdditionalBodyProperty("thinking",
                JsonValue.from(java.util.Map.of("type", "disabled")));
        }

        ChatCompletion resp = client.chat().completions().create(req.build());

        // ── 4) 解析响应 ──
        ChatCompletion.Choice choice = resp.choices().get(0);
        ChatCompletionMessage msg = choice.message();

        String text = msg.content().orElse("");
        List<ToolCall> toolCalls = new ArrayList<>();
        if (msg.toolCalls().isPresent()) {
            for (ChatCompletionMessageToolCall tc : msg.toolCalls().get()) {
                if (tc.isFunction()) {
                    ChatCompletionMessageFunctionToolCall fn = tc.asFunction();
                    // OpenAI：arguments() 已经是 JSON 字符串，直接拿
                    String args = fn.function().arguments();
                    toolCalls.add(new ToolCall(fn.id(), fn.function().name(), args));
                }
            }
        }

        // DeepSeek 推理模型的思考过程，存储在 _additionalProperties 中
        String reasoning = "";
        if (msg._additionalProperties().containsKey("reasoning_content")) {
            JsonValue rv = msg._additionalProperties().get("reasoning_content");
            reasoning = rv.convert(String.class);
            if (reasoning == null) reasoning = "";
        }

        String finish = choice.finishReason().asString();
        return new LlmResponse(text, toolCalls, finish, reasoning);
    }

    /** ToolDef → ChatCompletionTool */
    private static ChatCompletionTool toSdkTool(ToolDef td) {
        try {
            JsonNode schema = MAPPER.readTree(td.parametersJson());

            // 用 Jackson 构建完整的 parameters JSON（含 type/properties/required），
            // 然后逐键放入 FunctionParameters（它是一个扁平的 additionalProperties map）
            com.fasterxml.jackson.databind.node.ObjectNode paramsNode = MAPPER.createObjectNode();
            paramsNode.put("type", "object");
            paramsNode.set("properties", schema.get("properties"));
            if (schema.has("required")) {
                paramsNode.set("required", schema.get("required"));
            }

            FunctionParameters.Builder pb = FunctionParameters.builder();
            Iterator<String> fields = paramsNode.fieldNames();
            while (fields.hasNext()) {
                String key = fields.next();
                pb.putAdditionalProperty(key, JsonValue.fromJsonNode(paramsNode.get(key)));
            }

            FunctionDefinition.Builder fb = FunctionDefinition.builder()
                    .name(td.name())
                    .description(td.description())
                    .parameters(pb.build());
            return ChatCompletionTool.ofFunction(
                ChatCompletionFunctionTool.builder().function(fb.build()).build());
        } catch (Exception e) {
            throw new RuntimeException("无法解析 tool schema: " + td.name(), e);
        }
    }
}
```

---

## 5. Tools

### `src/main/java/com/javelin/tool/Tool.java`

工具接口，定义 agent 可调用的外部能力，包含名称、描述、输入 schema 和执行方法。

```java
package com.javelin.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一个 Tool 描述了 agent 能调用的一种"外部能力"。
 *
 * 学习要点：
 * 1. name() / description() / inputSchema() 会被打包成 JSON 发给 LLM，
 *    告诉它"你有哪些工具、参数长什么样"。LLM 据此决定要不要调用、怎么调。
 * 2. execute() 是真正的本地执行逻辑。LLM 永远不会"自己跑代码"，
 *    它只会要求我们跑工具，再把结果给它看。
 * 3. inputSchema 必须是合法的 JSON Schema (object 类型)，
 *    SDK 内部会校验，写错了 API 会拒绝。
 */
public interface Tool {

    /** 工具名（agent 与 LLM 双方用来识别这个工具）。必须唯一、英文小写下划线。 */
    String name();

    /** 给 LLM 看的工具说明。写得越清楚，模型越知道何时该用、怎么传参。 */
    String description();

    /**
     * JSON Schema，描述 input 的结构。最小形态如：
     * <pre>
     * {
     *   "type": "object",
     *   "properties": { "expression": { "type": "string" } },
     *   "required": ["expression"]
     * }
     * </pre>
     */
    JsonNode inputSchema();

    /**
     * 真正执行工具。
     *
     * @param input  LLM 给的入参（已解析为 JsonNode）
     * @return       返回给 LLM 的字符串结果。出错时建议返回包含 "error:" 的字符串，
     *               这样模型能感知失败并尝试别的方式 —— 这就是"错误回灌"机制。
     */
    String execute(JsonNode input) throws Exception;
}
```

### `src/main/java/com/javelin/tool/ToolRegistry.java`

工具注册表，用 LinkedHashMap 管理 name → Tool 的映射，保留注册顺序。

```java
package com.javelin.tool;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 简单的工具注册表。Agent 在每轮 LLM 调用前会把所有工具的 schema 一并发送，
 * 在收到 tool_use 时通过 name 查到对应实现执行。
 *
 * 用 LinkedHashMap 保留注册顺序，方便调试时看到工具列表是稳定的。
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry register(Tool tool) {
        if (tools.containsKey(tool.name())) {
            throw new IllegalStateException("Duplicated tool: " + tool.name());
        }
        tools.put(tool.name(), tool);
        return this;
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public Collection<Tool> all() {
        return tools.values();
    }
}
```

### `src/main/java/com/javelin/tool/builtin/CalculatorTool.java`

四则运算计算器，用递归下降解析器实现，避免 JDK 17 缺少 Nashorn 引擎的问题。

```java
package com.javelin.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javelin.tool.Tool;

/**
 * 计算器工具：用 JavaScript 引擎跑一段算术表达式。
 *
 * 选它作为"第一个工具"的原因：
 * - 几乎没有副作用，验证 ReAct 循环最干净
 * - 输入输出都是纯文本，方便打印观察
 * - 让 LLM 通过工具做数学，比让它"硬算 23*47"靠谱得多 —— 这正是 agent 的价值之一
 *
 * 注意：javax.script + Nashorn 在 JDK 17 上不再内置 JS 引擎，
 * 这里用一个手写的"安全四则运算解析器"代替，避免引入额外依赖。
 */
public class CalculatorTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public String description() {
        return "Evaluate a basic arithmetic expression with + - * / and parentheses. "
                + "Use this whenever you need to compute a number — do not do mental math.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode expr = props.putObject("expression");
        expr.put("type", "string");
        expr.put("description", "Arithmetic expression, e.g. \"(23 + 7) * 4\"");
        schema.putArray("required").add("expression");
        return schema;
    }

    @Override
    public String execute(JsonNode input) {
        JsonNode exprNode = input.get("expression");
        if (exprNode == null || !exprNode.isTextual()) {
            return "error: missing string field 'expression'";
        }
        try {
            double result = new Parser(exprNode.asText()).parse();
            // 整数结果就不显示小数点，让输出更自然
            if (result == Math.floor(result) && !Double.isInfinite(result)) {
                return Long.toString((long) result);
            }
            return Double.toString(result);
        } catch (RuntimeException e) {
            return "error: " + e.getMessage();
        }
    }

    /** 极小的递归下降解析器：expr = term (('+'|'-') term)* ; term = factor (('*'|'/') factor)* ; factor = number | '(' expr ')' | '-' factor. */
    private static class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        double parse() {
            double v = expr();
            skipSpace();
            if (pos != src.length()) throw new RuntimeException("unexpected char at " + pos + ": '" + src.charAt(pos) + "'");
            return v;
        }

        private double expr() {
            double v = term();
            while (true) {
                skipSpace();
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                    char op = src.charAt(pos++);
                    double r = term();
                    v = (op == '+') ? v + r : v - r;
                } else return v;
            }
        }

        private double term() {
            double v = factor();
            while (true) {
                skipSpace();
                if (pos < src.length() && (src.charAt(pos) == '*' || src.charAt(pos) == '/')) {
                    char op = src.charAt(pos++);
                    double r = factor();
                    v = (op == '*') ? v * r : v / r;
                } else return v;
            }
        }

        private double factor() {
            skipSpace();
            if (pos >= src.length()) throw new RuntimeException("unexpected end of input");
            char c = src.charAt(pos);
            if (c == '(') {
                pos++;
                double v = expr();
                skipSpace();
                if (pos >= src.length() || src.charAt(pos) != ')') throw new RuntimeException("missing ')'");
                pos++;
                return v;
            }
            if (c == '-') { pos++; return -factor(); }
            if (c == '+') { pos++; return factor(); }
            int start = pos;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) pos++;
            if (start == pos) throw new RuntimeException("expected number at " + pos);
            return Double.parseDouble(src.substring(start, pos));
        }

        private void skipSpace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }
    }
}
```

### `src/main/java/com/javelin/tool/builtin/ReadFileTool.java`

读取文件内容，支持 offset/limit 片段读取，防止大文件撑爆上下文。

```java
package com.javelin.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javelin.tool.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 读取文件内容，返回全文。
 * 支持通过 offset/limit 读取文件片段，避免大文件撑爆上下文。
 */
public class ReadFileTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() { return "read_file"; }

    @Override
    public String description() {
        return "读取指定文件的内容。可选 offset（起始行，从 1 开始）和 limit（读取行数）。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode filePath = props.putObject("file_path");
        filePath.put("type", "string");
        filePath.put("description", "文件路径（绝对路径或相对于当前工作目录）");
        ObjectNode offset = props.putObject("offset");
        offset.put("type", "integer");
        offset.put("description", "起始行号，从 1 开始。不填则从头读取");
        ObjectNode limit = props.putObject("limit");
        limit.put("type", "integer");
        limit.put("description", "最大读取行数，不填则读取全部");
        schema.putArray("required").add("file_path");
        return schema;
    }

    @Override
    public String execute(JsonNode input) {
        String rawPath = input.get("file_path").asText();
        try {
            Path path = Path.of(rawPath);
            if (!Files.isRegularFile(path)) {
                return "error: 文件不存在: " + rawPath;
            }
            String content = Files.readString(path);
            String[] lines = content.split("\n", -1);

            int start = 0;
            if (input.has("offset")) {
                start = input.get("offset").asInt() - 1;
                if (start < 0) start = 0;
            }
            int end = lines.length;
            if (input.has("limit")) {
                end = Math.min(end, start + input.get("limit").asInt());
            }

            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(String.format("%6d\t%s%n", i + 1, lines[i]));
            }
            return sb.toString();
        } catch (IOException e) {
            return "error: 读取失败: " + e.getMessage();
        }
    }
}
```

### `src/main/java/com/javelin/tool/builtin/WriteFileTool.java`

创建或覆盖写入文件，父目录自动创建。

```java
package com.javelin.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javelin.tool.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 创建或覆盖写入文件。父目录不存在时会自动创建。
 */
public class WriteFileTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() { return "write_file"; }

    @Override
    public String description() {
        return "将内容写入指定文件。如果文件已存在则覆盖，父目录不存在时自动创建。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode filePath = props.putObject("file_path");
        filePath.put("type", "string");
        filePath.put("description", "目标文件路径（绝对路径或相对于当前工作目录）");
        ObjectNode content = props.putObject("content");
        content.put("type", "string");
        content.put("description", "要写入的内容");
        schema.putArray("required").add("file_path").add("content");
        return schema;
    }

    @Override
    public String execute(JsonNode input) {
        String rawPath = input.get("file_path").asText();
        String content = input.get("content").asText();
        try {
            Path path = Path.of(rawPath);
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
            return "已写入: " + path.toAbsolutePath() + " (" + content.length() + " 字符)";
        } catch (IOException e) {
            return "error: 写入失败: " + e.getMessage();
        }
    }
}
```

### `src/main/java/com/javelin/tool/builtin/ListDirTool.java`

列出目录内容，支持递归深度控制。

```java
package com.javelin.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javelin.tool.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 列出目录中的文件和子目录。
 */
public class ListDirTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() { return "list_dir"; }

    @Override
    public String description() {
        return "列出指定目录中的文件和子目录。支持递归（depth 控制深度，默认 1）。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode dirPath = props.putObject("path");
        dirPath.put("type", "string");
        dirPath.put("description", "目录路径，不填则为当前工作目录");
        ObjectNode depth = props.putObject("depth");
        depth.put("type", "integer");
        depth.put("description", "递归深度，默认 1。设为 2 显示一层子目录内容，以此类推");
        schema.putArray("required");
        return schema;
    }

    @Override
    public String execute(JsonNode input) {
        String rawPath = input.has("path") ? input.get("path").asText() : ".";
        int maxDepth = input.has("depth") ? input.get("depth").asInt() : 1;
        if (maxDepth < 1) maxDepth = 1;
        if (maxDepth > 5) maxDepth = 5;

        try {
            Path root = Path.of(rawPath).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                return "error: 不是目录: " + rawPath;
            }
            StringBuilder sb = new StringBuilder();
            listRecursive(root, root, 1, maxDepth, sb);
            return sb.toString();
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    private void listRecursive(Path root, Path dir, int currentDepth, int maxDepth,
                                StringBuilder sb) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            entries.sorted().forEach(p -> {
                String prefix = "  ".repeat(currentDepth - 1);
                String name = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    sb.append(prefix).append(name).append("/\n");
                    if (currentDepth < maxDepth) {
                        try {
                            listRecursive(root, p, currentDepth + 1, maxDepth, sb);
                        } catch (IOException ignored) {}
                    }
                } else {
                    sb.append(prefix).append(name).append("\n");
                }
            });
        }
    }
}
```

### `src/main/java/com/javelin/tool/builtin/GrepTool.java`

在文件内容中搜索正则表达式，自动跳过二进制文件和常见忽略目录。

```java
package com.javelin.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javelin.tool.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 在文件内容中搜索正则表达式，类似 ripgrep。
 *
 * 自动跳过二进制文件和常见忽略目录（target、.git、node_modules 等）。
 */
public class GrepTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RESULTS = 200;
    private static final long MAX_FILE_SIZE = 1024 * 1024; // 1 MB

    @Override
    public String name() { return "grep"; }

    @Override
    public String description() {
        return "在目录中递归搜索文件内容，支持正则表达式。自动跳过二进制文件和 target/.git/node_modules 目录。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode pattern = props.putObject("pattern");
        pattern.put("type", "string");
        pattern.put("description", "正则表达式搜索模式");
        ObjectNode dirPath = props.putObject("path");
        dirPath.put("type", "string");
        dirPath.put("description", "搜索目录路径，不填则为当前工作目录");
        ObjectNode glob = props.putObject("glob");
        glob.put("type", "string");
        glob.put("description", "文件名过滤，如 *.java。不填则搜索所有文本文件");
        schema.putArray("required").add("pattern");
        return schema;
    }

    @Override
    public String execute(JsonNode input) {
        String patternStr = input.get("pattern").asText();
        String searchPath = input.has("path") ? input.get("path").asText() : ".";
        String globFilter = input.has("glob") ? input.get("glob").asText() : null;

        Pattern regex;
        try {
            regex = Pattern.compile(patternStr);
        } catch (PatternSyntaxException e) {
            return "error: 正则表达式语法错误: " + e.getMessage();
        }

        try {
            List<String> results = new ArrayList<>();
            Path root = Path.of(searchPath);
            if (!Files.isDirectory(root)) {
                return "error: 目录不存在: " + searchPath;
            }

            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    if (name.equals(".git") || name.equals("target") || name.equals("node_modules")
                            || name.equals(".idea") || name.equals(".vscode") || name.equals("__pycache__")
                            || name.equals("maven-repository")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (results.size() >= MAX_RESULTS) return FileVisitResult.TERMINATE;
                    if (attrs.size() > MAX_FILE_SIZE) return FileVisitResult.CONTINUE;
                    if (globFilter != null && !file.getFileName().toString().matches(globToRegex(globFilter))) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        String content = Files.readString(file);
                        String[] lines = content.split("\n", -1);
                        for (int i = 0; i < lines.length; i++) {
                            if (results.size() >= MAX_RESULTS) break;
                            if (regex.matcher(lines[i]).find()) {
                                String relPath = root.relativize(file).toString();
                                results.add(String.format("%s:%d: %s", relPath, i + 1, lines[i].strip()));
                            }
                        }
                    } catch (IOException ignored) {
                        // 二进制文件或编码问题，跳过
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            if (results.isEmpty()) return "未找到匹配项";
            return String.join("\n", results);
        } catch (IOException e) {
            return "error: 搜索失败: " + e.getMessage();
        }
    }

    /** 将简单 glob 转为正则：* → [^/]*，? → [^/] */
    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*': sb.append("[^/]*"); break;
                case '?': sb.append("[^/]"); break;
                case '.': sb.append("\\."); break;
                default:  sb.append(c);
            }
        }
        sb.append("$");
        return sb.toString();
    }
}
```

### `src/main/java/com/javelin/tool/builtin/GlobTool.java`

按通配符模式搜索文件名，支持 ** 递归匹配，自动跳过常见忽略目录。

```java
package com.javelin.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javelin.tool.Tool;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * 按通配符模式搜索文件名，类似 glob 命令。
 *
 * 支持 ** 递归匹配，自动跳过常见忽略目录。
 */
public class GlobTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RESULTS = 500;

    @Override
    public String name() { return "glob"; }

    @Override
    public String description() {
        return "按通配符模式查找文件，如 **/*.java 匹配所有 Java 文件。"
                + "支持 **（递归）、*（单层文件名）、?（单字符）。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode pattern = props.putObject("pattern");
        pattern.put("type", "string");
        pattern.put("description", "文件匹配模式，如 **/*.java 或 src/**/*Test*.java");
        ObjectNode dirPath = props.putObject("path");
        dirPath.put("type", "string");
        dirPath.put("description", "搜索根目录，不填则为当前工作目录");
        schema.putArray("required").add("pattern");
        return schema;
    }

    @Override
    public String execute(JsonNode input) {
        String patternStr = input.get("pattern").asText();
        String searchPath = input.has("path") ? input.get("path").asText() : ".";

        try {
            Path root = Path.of(searchPath).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                return "error: 目录不存在: " + searchPath;
            }

            // 将 glob 转为 PathMatcher 可用的 "glob:**/*.java" 格式
            String matcherPattern = "glob:" + patternStr;
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher(matcherPattern);

            List<String> results = new ArrayList<>();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (name.equals(".git") || name.equals("target") || name.equals("node_modules")
                            || name.equals(".idea") || name.equals("__pycache__")
                            || name.equals("maven-repository")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (results.size() >= MAX_RESULTS) return FileVisitResult.TERMINATE;
                    Path rel = root.relativize(file);
                    // PathMatcher 的 matches 对 glob:**/*.java 格式只匹配文件名，需要用完整相对路径
                    // 所以手动检查：先看文件名是否匹配，再处理 ** 的情况
                    if (matchesGlob(rel, patternStr)) {
                        results.add(rel.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            if (results.isEmpty()) return "未找到匹配项";
            results.sort(String::compareTo);
            return String.join("\n", results);
        } catch (IOException e) {
            return "error: 搜索失败: " + e.getMessage();
        }
    }

    /** 简单 glob 匹配：将 ** 处理为任意路径段，* 为任意文件名字符 */
    private static boolean matchesGlob(Path rel, String glob) {
        return matchSegments(rel.toString().replace('\\', '/'), glob.replace('\\', '/'));
    }

    private static boolean matchSegments(String path, String pattern) {
        // 将 pattern 转为正则
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*':
                    if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                        i++;
                        if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '/') {
                            i++; // **/
                            regex.append("(.*/)?");
                        } else {
                            regex.append(".*"); // ** at end
                        }
                    } else {
                        regex.append("[^/]*"); // * = not slash
                    }
                    break;
                case '?': regex.append("[^/]"); break;
                case '.': regex.append("\\."); break;
                default: regex.append(c);
            }
        }
        regex.append("$");
        return path.matches(regex.toString());
    }
}
```

---

## 6. Config

### `src/main/java/com/javelin/config/DotEnv.java`

极简 .env 文件加载器，支持 KEY=value、引号包裹、行尾注释，无需第三方库。

```java
package com.javelin.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 极简 .env 加载器。不引第三方库，覆盖最常见的语法即可。
 *
 * 支持：
 *   KEY=value             # 行尾注释
 *   KEY="quoted value"    支持双引号包裹（用于值里含 # 或空格）
 *   KEY='single quoted'   同上
 *   # 整行注释
 *   空行
 *
 * 不支持（保持简单）：
 *   变量插值 ${OTHER}
 *   多行字符串
 *   导出语法 `export KEY=...`（会被忽略掉 export 前缀）
 *
 * 设计说明：
 * - System.getenv() 在 Java 中是不可变的，所以我们不能"把 .env 注入环境变量"，
 *   只能把它当成普通 Map 暴露给应用层（{@link #get}）。
 * - 加载失败（文件不存在）返回空 DotEnv 实例，让调用方决定如何回退。
 */
public final class DotEnv {

    private final Map<String, String> values;

    private DotEnv(Map<String, String> values) {
        this.values = values;
    }

    public static DotEnv loadOrEmpty(Path path) {
        if (!Files.isRegularFile(path)) {
            return new DotEnv(Map.of());
        }
        try {
            List<String> lines = Files.readAllLines(path);
            Map<String, String> map = new HashMap<>();
            for (int i = 0; i < lines.size(); i++) {
                parseLine(lines.get(i), i + 1, map);
            }
            return new DotEnv(map);
        } catch (IOException e) {
            throw new RuntimeException("读取 " + path + " 失败: " + e.getMessage(), e);
        }
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    /** .env 优先，找不到回退到 System.getenv()。值为空串视同未设置。 */
    public Optional<String> getOrEnv(String key) {
        String v = values.get(key);
        if (v == null || v.isEmpty()) v = System.getenv(key);
        return (v == null || v.isEmpty()) ? Optional.empty() : Optional.of(v);
    }

    private static void parseLine(String raw, int lineNo, Map<String, String> out) {
        String line = raw.strip();
        if (line.isEmpty() || line.startsWith("#")) return;

        if (line.startsWith("export ")) line = line.substring("export ".length()).strip();

        int eq = line.indexOf('=');
        if (eq <= 0) {
            throw new IllegalArgumentException(".env 第 " + lineNo + " 行缺少 '=': " + raw);
        }
        String key = line.substring(0, eq).strip();
        String value = line.substring(eq + 1).strip();

        // 引号包裹：取引号内的所有内容，不再处理 #
        if (value.length() >= 2
                && ((value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"')
                 || (value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\''))) {
            value = value.substring(1, value.length() - 1);
        } else {
            // 非引号场景：# 之前是值，之后是注释
            int hash = value.indexOf('#');
            if (hash >= 0) value = value.substring(0, hash).strip();
        }

        out.put(key, value);
    }
}
```

---

## 7. UI

### `src/main/java/com/javelin/ui/Ansi.java`

ANSI 终端颜色/样式工具，静态方法封装，支持全局禁用。

```java
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
```

### `src/main/java/com/javelin/ui/Box.java`

圆角框渲染器，用于工具调用/结果的视觉呈现，模仿 Claude Code 风格。

```java
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
```

### `src/main/java/com/javelin/ui/MdAnsi.java`

极简 Markdown 到 ANSI 转义序列渲染器，支持代码围栏、标题、粗体、斜体和行内 code。

```java
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
```

---

## 8. Build

### `pom.xml`

Maven 构建配置，依赖 anthropic-java、openai-java、JLine，配置编译和执行插件。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.javelin</groupId>
    <artifactId>javelin</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <name>javelin</name>
    <description>A learning-oriented Claude-Code-like agent in Java.</description>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>com.anthropic</groupId>
            <artifactId>anthropic-java</artifactId>
            <version>2.32.0</version>
        </dependency>

        <!-- OpenAI 官方 Java SDK：用于 DeepSeek / GLM / Kimi 等 OpenAI 协议兼容的 provider -->
        <dependency>
            <groupId>com.openai</groupId>
            <artifactId>openai-java</artifactId>
            <version>4.35.0</version>
        </dependency>

        <!-- JLine 3/4：行编辑、历史、补全、终端能力。uber 包 jline 把 terminal/reader/builtins 全包含 -->
        <dependency>
            <groupId>org.jline</groupId>
            <artifactId>jline</artifactId>
            <version>4.0.14</version>
            <classifier>jdk11</classifier>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
                <configuration>
                    <release>17</release>
                </configuration>
            </plugin>

            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.3.0</version>
                <configuration>
                    <mainClass>com.javelin.Main</mainClass>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```
