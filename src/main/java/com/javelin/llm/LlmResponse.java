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
