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
