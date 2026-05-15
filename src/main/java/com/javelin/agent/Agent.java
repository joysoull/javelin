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
