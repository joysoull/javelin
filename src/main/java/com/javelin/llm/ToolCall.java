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
