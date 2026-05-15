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
