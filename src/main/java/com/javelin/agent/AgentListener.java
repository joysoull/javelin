package com.javelin.agent;

/**
 * ReAct / Plan-and-Execute 循环的观察者，让 REPL 层能把每一步渲染到终端。
 *
 * 所有方法都是 default 空实现，调用方按需 override。
 */
public interface AgentListener {

    AgentListener NOOP = new AgentListener() {};

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
