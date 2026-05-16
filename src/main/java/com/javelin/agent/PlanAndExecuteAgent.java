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
 * 三阶段流程：
 *   1. PLAN   —— Planner（ReAct 子循环 + create_plan）生成 ExecutionPlan
 *   2. EXECUTE—— DagExecutor 按 DAG 拓扑序并行执行
 *   3. REVIEW —— LLM 审阅执行结果，失败时触发重规划（最多 3 轮）
 */
public class PlanAndExecuteAgent {

    private static final int MAX_REPLAN_ROUNDS = 3;

    private final LlmProvider llm;
    private final ToolRegistry tools;
    private final String systemPrompt;
    private final Agent.Listener listener;
    private final Planner planner;
    private final DagExecutor dagExecutor;

    /**
     * @param llm          LLM Provider
     * @param tools        工具注册表（不含 create_plan）
     * @param systemPrompt 系统提示词，可为 null
     * @param listener     观察者，为 null 时静默运行
     */
    public PlanAndExecuteAgent(LlmProvider llm, ToolRegistry tools,
                               String systemPrompt, Agent.Listener listener) {
        this.llm = llm;
        this.tools = tools;
        this.systemPrompt = systemPrompt;
        this.listener = listener != null ? listener : Agent.Listener.NOOP;
        this.planner = new Planner(llm, tools, systemPrompt, this.listener);
        this.dagExecutor = new DagExecutor(this.listener);
    }

    /**
     * 处理一次用户输入，运行完整的 Plan → Execute → Review 流程。
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
            listener.onPhase("开始执行计划 " + currentPlan.id());
            dagExecutor.execute(currentPlan, tools);

            // ── 阶段 3: REVIEW ──
            review(userInput, currentPlan);

            boolean hasFailures = currentPlan.tasks().stream()
                    .anyMatch(t -> t.status() == Task.Status.FAILED
                                || t.status() == Task.Status.SKIPPED);

            if (!hasFailures || round == MAX_REPLAN_ROUNDS) {
                return ""; // review 已通过 listener 展示，不再重复
            }

            listener.onPhase("尝试修正失败步骤… 第 " + round + "/" + MAX_REPLAN_ROUNDS + " 轮");
            ExecutionPlan revised = replan(userInput, currentPlan);
            if (revised == null || revised.isEmpty()) {
                return "";
            }
            currentPlan = revised;
        }

        review(userInput, currentPlan);
        return "";
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
     * 审阅阶段：汇总 Task 执行结果，发给 LLM 生成总结。
     */
    private String review(String userInput, ExecutionPlan plan) {
        if (plan.isEmpty()) return "计划为空，无执行结果。";

        listener.onPhase("审阅执行结果…");

        StringBuilder sb = new StringBuilder();
        sb.append("目标: ").append(plan.goal()).append('\n');
        sb.append("执行顺序: ").append(String.join(" → ", plan.executionOrder())).append('\n');
        sb.append("耗时: ").append(plan.elapsedMs()).append("ms\n");

        for (Task task : plan.tasks()) {
            String icon = switch (task.status()) {
                case COMPLETED -> "OK";
                case FAILED -> "FAIL";
                case SKIPPED -> "SKIP";
                default -> "??";
            };
            sb.append("  [").append(icon).append("] ")
                    .append(task.type()).append(" ")
                    .append(task.stepId()).append(": ")
                    .append(task.toolName()).append('\n');
            if (!task.result().isEmpty()) {
                String r = task.result();
                if (r.length() > 200) r = r.substring(0, 200) + "...";
                sb.append("       → ").append(r.replace("\n", "\n       ")).append('\n');
            }
        }

        boolean hasFailures = plan.tasks().stream()
                .anyMatch(t -> t.status() == Task.Status.FAILED
                            || t.status() == Task.Status.SKIPPED);

        String prompt = """
                用户需求: %s

                执行计划结果汇总:
                %s

                请总结执行情况。%s
                """.formatted(
                    userInput,
                    sb.toString(),
                    hasFailures ? "有失败或跳过的步骤，如果认为可以修正请说明。" : "简要说明完成的工作。"
                );

        List<LlmMessage> history = new ArrayList<>();
        history.add(LlmMessage.user(prompt));

        LlmResponse resp = llm.chat(history, List.of(), systemPrompt);
        String text = resp.text();
        listener.onAssistantText(text);
        return text;
    }

    /**
     * 重规划：将失败结果发给 LLM，单次调用生成修正计划。
     */
    private ExecutionPlan replan(String userInput, ExecutionPlan failedPlan) {
        listener.onPhase("要求 LLM 修正失败步骤…");

        StringBuilder sb = new StringBuilder();
        sb.append("原始目标: ").append(failedPlan.goal()).append('\n');
        for (Task task : failedPlan.tasks()) {
            String icon = switch (task.status()) {
                case COMPLETED -> "OK";
                case FAILED -> "FAIL";
                case SKIPPED -> "SKIP";
                default -> "??";
            };
            sb.append("  [").append(icon).append("] ")
                    .append(task.stepId()).append(": ")
                    .append(task.toolName()).append('\n');
            if (!task.result().isEmpty()) {
                String r = task.result();
                if (r.length() > 200) r = r.substring(0, 200) + "...";
                sb.append("       → ").append(r.replace("\n", "\n       ")).append('\n');
            }
        }

        String prompt = """
                以下是执行失败的计划，请针对失败/跳过的步骤制定修正方案，
                调用 create_plan 提交新的执行计划（只需包含需要重试和修正的步骤）。

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
        history.add(LlmMessage.assistant(resp.text(), resp.toolCalls(), resp.reasoningContent()));

        return planner.parseToolCalls(resp.toolCalls());
    }
}
