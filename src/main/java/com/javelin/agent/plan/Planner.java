package com.javelin.agent.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javelin.agent.Agent;
import com.javelin.llm.*;
import com.javelin.tool.ToolRegistry;
import com.javelin.tool.builtin.CreatePlanTool;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 规划器——把用户输入的复杂任务分解成 ExecutionPlan。
 *
 * 纯 Plan and Execute 模式：单次 LLM 调用，LLM 根据用户输入和
 * 可用工具清单直接调用 create_plan 提交结构化执行计划。
 *
 * 可用工具清单以文本形式嵌入 prompt，LLM 不能探索——只能规划。
 * 计划偏差由后续 replan 修正。
 */
public class Planner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 规划阶段的系统指令，追加到原有 systemPrompt 前面 */
    private static final String PLAN_INSTRUCTION = """
            你是计划制定者。收到用户需求后，你必须调用 create_plan 工具提交一个完整的执行计划。
            不要直接回答用户的问题，不要执行任何操作——只生成计划。
            计划中的每个步骤使用下面列出的可用工具，步骤之间用 depends_on 声明依赖关系。
            如果需求简单，一个步骤也可以，但仍需提交计划。
            """;

    private final LlmProvider llm;
    private final List<ToolDef> toolDefs;
    private final String systemPrompt;
    private final String toolSummary;
    private final Agent.Listener listener;

    /**
     * @param llm            LLM Provider
     * @param executionTools 执行阶段工具注册表（提取名称和描述告知 LLM）
     * @param systemPrompt   系统提示词，可为 null
     * @param listener       观察者，为 null 时静默运行
     */
    public Planner(LlmProvider llm, ToolRegistry executionTools, String systemPrompt, Agent.Listener listener) {
        this.llm = llm;
        this.systemPrompt = systemPrompt;
        this.listener = listener != null ? listener : Agent.Listener.NOOP;

        // 只注册 create_plan 为可调用工具
        CreatePlanTool createPlan = new CreatePlanTool();
        this.toolDefs = List.of(new ToolDef(
                createPlan.name(), createPlan.description(), createPlan.inputSchema().toString()));

        // 从执行工具注册表提取清单（含参数名），嵌入 prompt 告知 LLM
        this.toolSummary = executionTools.all().stream()
                .map(t -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("  ").append(t.name()).append(" — ").append(t.description());
                    // 提取 required 参数名
                    JsonNode schema = t.inputSchema();
                    if (schema != null && schema.has("required")) {
                        JsonNode required = schema.get("required");
                        if (required.isArray() && required.size() > 0) {
                            sb.append("  参数: ");
                            for (JsonNode r : required) {
                                sb.append(r.asText()).append(", ");
                            }
                            sb.setLength(sb.length() - 2); // 去掉末尾逗号空格
                        }
                    }
                    return sb.toString();
                })
                .collect(Collectors.joining("\n"));
    }

    /**
     * 单次 LLM 调用生成 ExecutionPlan。
     *
     * 用户消息中会附上可用工具清单，LLM 看到后调用 create_plan 提交计划。
     *
     * @param userInput 用户原始输入
     * @return 生成的计划，失败返回 null
     */
    public ExecutionPlan plan(String userInput) {
        listener.onPhase("制定计划…");

        // 把工具清单附在用户消息中
        String prompt = "可用工具:\n" + toolSummary + "\n\n用户需求: " + userInput;

        List<LlmMessage> history = new ArrayList<>();
        history.add(LlmMessage.user(prompt));

        // 规划指令 + 原有系统提示词
        String planningPrompt = (systemPrompt != null && !systemPrompt.isBlank())
                ? PLAN_INSTRUCTION + "\n" + systemPrompt
                : PLAN_INSTRUCTION;

        LlmResponse resp = llm.chat(history, toolDefs, planningPrompt);

        if (!resp.reasoningContent().isEmpty()) {
            listener.onReasoning(resp.reasoningContent());
        }

        history.add(LlmMessage.assistant(resp.text(), resp.toolCalls(), resp.reasoningContent()));

        if (resp.needsToolExecution()) {
            if (!resp.text().isEmpty()) listener.onAssistantText(resp.text());

            ExecutionPlan plan = parseToolCalls(resp.toolCalls());
            if (plan != null) {
                listener.onPhase("计划已生成 · 共 " + plan.taskCount() + " 步");
            }
            return plan;
        }

        if (!resp.text().isEmpty()) listener.onAssistantText(resp.text());
        return null;
    }

    /**
     * 从 LLM 返回的工具调用中查找 create_plan，解析为 ExecutionPlan。
     * 如果在 toolCalls 中找不到 create_plan，返回 null。
     */
    public ExecutionPlan parseToolCalls(List<ToolCall> toolCalls) {
        for (ToolCall tc : toolCalls) {
            if (!"create_plan".equals(tc.name())) continue;

            listener.onToolUse(tc.name(), tc.id(), tc.argumentsJson());
            try {
                JsonNode root = MAPPER.readTree(tc.argumentsJson());
                String goal = root.get("goal").asText("");

                ExecutionPlan plan = new ExecutionPlan(goal);

                JsonNode stepsNode = root.get("steps");
                if (stepsNode != null && stepsNode.isArray()) {
                    for (JsonNode s : stepsNode) {
                        JsonNode typeNode = s.get("type");
                        Task.Type type = parseType(typeNode != null ? typeNode.asText("") : null);
                        String id = s.get("id").asText("");
                        String toolName = s.get("tool").asText("");
                        JsonNode args = s.get("arguments");
                        if (args == null) args = MAPPER.createObjectNode();

                        List<String> deps = new ArrayList<>();
                        JsonNode depsNode = s.get("depends_on");
                        if (depsNode != null && depsNode.isArray()) {
                            for (JsonNode d : depsNode) {
                                deps.add(d.asText());
                            }
                        }

                        plan.addTask(new Task(type, id, toolName, args, deps));
                    }
                }
                return plan;
            } catch (JsonProcessingException e) {
                String errorMsg = "create_plan 参数解析失败: " + e.getMessage();
                listener.onToolResult(tc.name(), tc.id(), errorMsg, true);
                return null;
            }
        }
        return null;
    }

    private static Task.Type parseType(String s) {
        if (s == null) return Task.Type.PLANNING;
        try {
            return Task.Type.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Task.Type.PLANNING;
        }
    }
}
