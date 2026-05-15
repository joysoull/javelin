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
