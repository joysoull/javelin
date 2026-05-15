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
