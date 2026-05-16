package com.javelin.agent.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.javelin.agent.Agent;
import com.javelin.tool.Tool;
import com.javelin.tool.ToolRegistry;

import java.util.*;
import java.util.concurrent.*;

/**
 * DAG 执行引擎。
 *
 * 接收 ExecutionPlan（已填充 Task），按 dependsOn 构建有向无环图，
 * 用 Kahn 算法计算 executionOrder 并分层并行执行。
 * 执行结果回填到 Task，plan 状态随之更新。
 */
public class DagExecutor {

    private final ExecutorService executor;
    private final Agent.Listener listener;

    /**
     * @param listener 用于输出每个步骤的执行进度
     */
    public DagExecutor(Agent.Listener listener) {
        this.executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "dag-executor");
            t.setDaemon(true);
            return t;
        });
        this.listener = listener != null ? listener : Agent.Listener.NOOP;
    }

    /**
     * 按 DAG 拓扑序执行 ExecutionPlan 中的所有 Task。
     *
     * 步骤：
     *   1. 构建邻接表（dependsOn → dependents）和入度表
     *   2. Kahn 算法逐层执行，同步记录 executionOrder
     *   3. 失败 Task 的下游自动跳过
     *   4. 执行完毕后更新 plan 状态（COMPLETED 或 FAILED）
     *
     * @param plan  待执行的计划，执行结果回填到其中的 Task
     * @param tools 工具注册表
     */
    public void execute(ExecutionPlan plan, ToolRegistry tools) {
        if (plan.isEmpty()) return;
        plan.markStarted();

        List<Task> tasks = plan.tasks();
        List<String> executionOrder = new ArrayList<>();

        // ── 构建图结构 ──
        Map<String, Task> taskById = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        Map<String, Integer> indegree = new LinkedHashMap<>();

        for (Task task : tasks) {
            taskById.put(task.stepId(), task);
            dependents.put(task.stepId(), new ArrayList<>());
            indegree.put(task.stepId(), task.dependsOn().size());
        }

        // 验证 dependsOn 引用存在
        for (Task task : tasks) {
            for (String depId : task.dependsOn()) {
                if (!taskById.containsKey(depId)) {
                    task.markFailed("error: 依赖的步骤 '" + depId + "' 不存在");
                } else {
                    dependents.get(depId).add(task.stepId());
                }
            }
        }

        // ── 初始化队列 ──
        Queue<String> ready = new ArrayDeque<>();
        for (Task task : tasks) {
            if (indegree.get(task.stepId()) == 0 && task.status() != Task.Status.FAILED) {
                ready.add(task.stepId());
            }
        }

        // ── 分层并行执行 ──
        while (!ready.isEmpty()) {
            List<String> currentLevel = new ArrayList<>(ready);
            ready.clear();

            // 当前层的所有节点写入执行顺序
            executionOrder.addAll(currentLevel);

            listener.onPhase("执行 " + String.join(", ", currentLevel));

            List<Future<?>> futures = new ArrayList<>();
            for (String taskId : currentLevel) {
                Task task = taskById.get(taskId);
                futures.add(executor.submit(() -> runTask(task, tools)));
            }

            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    // runTask 内部已处理异常
                }
            }

            // 更新下游入度
            for (String taskId : currentLevel) {
                Task task = taskById.get(taskId);

                if (task.status() == Task.Status.FAILED) {
                    skipDownstream(taskId, dependents, taskById, indegree);
                } else {
                    for (String depId : dependents.get(taskId)) {
                        int newIndegree = indegree.merge(depId, -1, Integer::sum);
                        if (newIndegree == 0 && taskById.get(depId).status() == Task.Status.PENDING) {
                            ready.add(depId);
                        }
                    }
                }
            }
        }

        // 剩余未执行节点 → SKIPPED，也记入执行顺序
        for (Task task : tasks) {
            if (task.status() == Task.Status.PENDING) {
                task.markSkipped("跳过：依赖步骤失败或存在环");
                executionOrder.add(task.stepId());
            }
        }

        plan.setExecutionOrder(executionOrder);

        // 判断计划最终状态
        boolean anyFailed = tasks.stream().anyMatch(t -> t.status() == Task.Status.FAILED);
        if (anyFailed) {
            plan.markFailed();
        } else {
            plan.markCompleted();
        }
    }

    private void runTask(Task task, ToolRegistry tools) {
        task.markRunning();
        listener.onToolUse(task.toolName(), task.stepId(), task.toolArguments().toString());

        Tool tool = tools.get(task.toolName());
        if (tool == null) {
            task.markFailed("error: 未知工具 '" + task.toolName() + "'");
            listener.onToolResult(task.toolName(), task.stepId(), task.result(), true);
            return;
        }

        try {
            JsonNode resolved = resolvePlaceholders(task.toolArguments(), task.stepId());
            String output = tool.execute(resolved);
            task.markCompleted(output);
            listener.onToolResult(task.toolName(), task.stepId(), output, false);
        } catch (Exception e) {
            task.markFailed("error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            listener.onToolResult(task.toolName(), task.stepId(), task.result(), true);
        }
    }

    private void skipDownstream(String failedId, Map<String, List<String>> dependents,
                                Map<String, Task> taskById, Map<String, Integer> indegree) {
        for (String depId : dependents.get(failedId)) {
            Task dep = taskById.get(depId);
            if (dep.status() == Task.Status.PENDING) {
                dep.markSkipped("跳过：前置步骤 '" + failedId + "' 执行失败");
                indegree.put(depId, 0);
                skipDownstream(depId, dependents, taskById, indegree);
            }
        }
    }

    private JsonNode resolvePlaceholders(JsonNode node, String currentStepId) {
        return node;
    }
}
