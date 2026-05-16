package com.javelin.agent;

import com.javelin.agent.plan.*;
import com.javelin.llm.*;
import com.javelin.tool.ToolRegistry;
import com.javelin.tool.builtin.CreatePlanTool;

import java.util.ArrayList;
import java.util.List;

/**
 * Plan-and-Execute Agent。
 *
 * 流程：
 *   1. PLAN   —— Planner 生成 ExecutionPlan
 *   2. EXECUTE—— DagExecutor 按 DAG 拓扑序并行执行
 *   3. REPLAN —— 如有失败，LLM 同时给出修正说明 + 新计划（最多 3 轮）
 *
 * 全部成功时不再调 LLM 做纯文本总结，直接返回模板汇总；
 * 失败时由 replan 一次 LLM 调用同时完成"说明原因"和"生成新计划"。
 */
public class PlanAndExecuteAgent {

    private static final int MAX_REPLAN_ROUNDS = 3;

    private final LlmProvider llm;
    private final ToolRegistry tools;
    private final String systemPrompt;
    private final AgentListener listener;
    private final Planner planner;
    private final DagExecutor dagExecutor;

    /**
     * @param llm          LLM Provider
     * @param tools        工具注册表（不含 create_plan）
     * @param systemPrompt 系统提示词，可为 null
     * @param listener     观察者，为 null 时静默运行
     */
    public PlanAndExecuteAgent(LlmProvider llm, ToolRegistry tools,
                               String systemPrompt, AgentListener listener) {
        this.llm = llm;
        this.tools = tools;
        this.systemPrompt = systemPrompt;
        this.listener = listener != null ? listener : AgentListener.NOOP;
        this.planner = new Planner(llm, tools, systemPrompt, this.listener);
        this.dagExecutor = new DagExecutor(this.listener);
    }

    /**
     * 处理一次用户输入，运行完整的 Plan → Execute → （失败则 Replan）流程。
     *
     * 全部成功时直接返回模板总结，不再调 LLM 做纯文本总结；
     * 有失败时进入 replan，由 LLM 同时给出修正说明和新计划。
     */
    public String chat(String userInput) {
        // ── 阶段 1: PLAN ──
        ExecutionPlan currentPlan = planner.plan(userInput);
        if (currentPlan == null || currentPlan.isEmpty()) {
            return "无法生成执行计划，请尝试更具体地描述需求。";
        }

        // 展示计划预览
        printPlanPreview(currentPlan);

        for (int round = 1; round <= MAX_REPLAN_ROUNDS; round++) {
            // ── 阶段 2: EXECUTE ──
            listener.onPhase("🚀 开始执行计划 " + currentPlan.id());
            dagExecutor.execute(currentPlan, tools);

            boolean hasFailures = currentPlan.tasks().stream()
                    .anyMatch(t -> t.status() == Task.Status.FAILED
                                || t.status() == Task.Status.SKIPPED);

            if (!hasFailures) {
                // 全部成功：模板总结，不再调 LLM
                return formatSummary(currentPlan, false);
            }

            if (round == MAX_REPLAN_ROUNDS) {
                // 最后一轮仍有失败，返回失败汇总
                return formatSummary(currentPlan, true);
            }

            // ── 阶段 3: REPLAN ──
            listener.onPhase("🔁 尝试修正失败步骤… 第 " + round + "/" + MAX_REPLAN_ROUNDS + " 轮");
            ExecutionPlan revised = replan(userInput, currentPlan);
            if (revised == null || revised.isEmpty()) {
                return formatSummary(currentPlan, true);
            }
            currentPlan = revised;
        }

        return formatSummary(currentPlan, true);
    }

    /** 将计划的步骤依赖关系输出到 UI，供用户在执行前预览 */
    private void printPlanPreview(ExecutionPlan plan) {
        StringBuilder sb = new StringBuilder();
        for (Task t : plan.tasks()) {
            sb.append(t.stepId()).append(" (").append(t.type()).append(") ")
              .append(t.toolName());
            if (!t.dependsOn().isEmpty()) {
                sb.append("  ← ").append(String.join(", ", t.dependsOn()));
            }
            sb.append('\n');
        }
        listener.onPhase(sb.toString().stripTrailing());
    }

    /**
     * 生成执行结果模板汇总，不调用 LLM。
     */
    private String formatSummary(ExecutionPlan plan, boolean hasFailures) {
        StringBuilder sb = new StringBuilder();
        if (hasFailures) {
            sb.append("计划执行完成，但部分步骤失败或跳过。\n\n");
        } else {
            sb.append("计划执行完成。\n\n");
        }
        for (Task task : plan.tasks()) {
            String icon = switch (task.status()) {
                case COMPLETED -> "✅";
                case FAILED -> "❌";
                case SKIPPED -> "⏭️";
                default -> "❓";
            };
            sb.append(icon).append(' ').append(task.stepId()).append(": ").append(task.toolName());
            if (!task.result().isEmpty()) {
                String r = task.result();
                if (r.length() > 80) r = r.substring(0, 80) + "...";
                sb.append(" → ").append(r.replace("\n", " "));
            }
            sb.append('\n');
        }
        sb.append("\n⏱️ 耗时: ").append(plan.elapsedMs()).append("ms");
        return sb.toString();
    }

    /**
     * 重规划：将失败结果发给 LLM，单次调用同时给出修正说明和生成修正计划。
     */
    private ExecutionPlan replan(String userInput, ExecutionPlan failedPlan) {
        listener.onPhase("🔧 要求 LLM 修正失败步骤…");

        StringBuilder sb = new StringBuilder();
        sb.append("原始目标: ").append(failedPlan.goal()).append('\n');
        for (Task task : failedPlan.tasks()) {
            String icon = switch (task.status()) {
                case COMPLETED -> "✅";
                case FAILED -> "❌";
                case SKIPPED -> "⏭️";
                default -> "❓";
            };
            sb.append("  ").append(icon).append(" ")
                    .append(task.stepId()).append(": ")
                    .append(task.toolName()).append('\n');
            if (!task.result().isEmpty()) {
                String r = task.result();
                if (r.length() > 200) r = r.substring(0, 200) + "...";
                sb.append("       → ").append(r.replace("\n", "\n       ")).append('\n');
            }
        }

        String prompt = """
                以下是执行失败的计划，请针对失败/跳过的步骤制定修正方案。
                先用简短文字说明你的修正思路，然后调用 create_plan 提交新的执行计划
                （只需包含需要重试和修正的步骤）。

                用户原始需求: %s

                %s
                """.formatted(userInput, sb.toString());

        List<LlmMessage> history = new ArrayList<>();
        history.add(LlmMessage.user(prompt));

        List<ToolDef> planOnlyTools = List.of(new ToolDef(
                "create_plan",
                new CreatePlanTool().description(),
                new CreatePlanTool().inputSchema().toString()));

        LlmResponse resp = llm.chat(history, planOnlyTools, systemPrompt);

        // 展示 LLM 给出的修正说明（工具调用前的解释文本）
        if (!resp.text().isEmpty()) {
            listener.onAssistantText(resp.text());
        }

        return planner.parseToolCalls(resp.toolCalls());
    }
}
