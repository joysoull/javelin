package com.javelin.agent.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javelin.agent.Agent;
import com.javelin.tool.Tool;
import com.javelin.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DagExecutor 依赖解析、并行执行、失败传播测试。
 *
 * 使用不依赖 LLM 的假工具，构造有依赖关系的 ExecutionPlan 验证：
 *   1. 无依赖步骤全部完成
 *   2. 串行依赖链（A→B→C）
 *   3. 菱形依赖（A→B,A→C→D）
 *   4. 失败传播（B 失败 → 下游跳过）
 *   5. 未知工具 → 标记 FAILED
 *   6. 缺失依赖 → 标记 FAILED
 *   7. executionOrder 正确记录
 */
class DagExecutorTest {

    private ToolRegistry tools;
    private ObjectMapper mapper;
    private List<String> eventLog;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        eventLog = Collections.synchronizedList(new ArrayList<>());

        tools = new ToolRegistry();
        // echo: 返回参数原样
        tools.register(new Tool() {
            @Override public String name() { return "echo"; }
            @Override public String description() { return "echo"; }
            @Override public JsonNode inputSchema() { return mapper.createObjectNode(); }
            @Override public String execute(JsonNode input) {
                eventLog.add("echo:" + input.get("msg").asText());
                return "echo: " + input.get("msg").asText();
            }
        });
        // fail: 一定抛异常
        tools.register(new Tool() {
            @Override public String name() { return "fail"; }
            @Override public String description() { return "fail"; }
            @Override public JsonNode inputSchema() { return mapper.createObjectNode(); }
            @Override public String execute(JsonNode input) {
                throw new RuntimeException("模拟失败: " + input.get("msg").asText());
            }
        });
    }

    @Test
    void allStepsCompleteWithNoDependencies() {
        ExecutionPlan plan = new ExecutionPlan("no deps");
        plan.addTask(task("a", List.of()));
        plan.addTask(task("b", List.of()));

        execute(plan);

        assertEquals(ExecutionPlan.Status.COMPLETED, plan.status());
        assertStatus(plan, "a", Task.Status.COMPLETED);
        assertStatus(plan, "b", Task.Status.COMPLETED);
        // a, b 在同一个并行层，顺序可能是 a,b 或 b,a
        assertTrue(plan.executionOrder().containsAll(List.of("a", "b")));
    }

    @Test
    void linearDependencyChain() {
        // A → B → C
        ExecutionPlan plan = new ExecutionPlan("chain");
        plan.addTask(task("a", List.of()));
        plan.addTask(task("b", List.of("a")));
        plan.addTask(task("c", List.of("b")));

        execute(plan);

        assertEquals(ExecutionPlan.Status.COMPLETED, plan.status());
        assertStatus(plan, "a", Task.Status.COMPLETED);
        assertStatus(plan, "b", Task.Status.COMPLETED);
        assertStatus(plan, "c", Task.Status.COMPLETED);
        // 验证拓扑序：a 在 b 前，b 在 c 前
        assertBefore(plan.executionOrder(), "a", "b");
        assertBefore(plan.executionOrder(), "b", "c");
    }

    @Test
    void diamondDependency() {
        // A → B, A → C, B+C → D
        ExecutionPlan plan = new ExecutionPlan("diamond");
        plan.addTask(task("a", List.of()));
        plan.addTask(task("b", List.of("a")));
        plan.addTask(task("c", List.of("a")));
        plan.addTask(task("d", List.of("b", "c")));

        execute(plan);

        assertEquals(ExecutionPlan.Status.COMPLETED, plan.status());
        assertStatus(plan, "d", Task.Status.COMPLETED);
        // A 必须在 B,C 之前；B,C 必须在 D 之前
        assertBefore(plan.executionOrder(), "a", "b");
        assertBefore(plan.executionOrder(), "a", "c");
        assertBefore(plan.executionOrder(), "b", "d");
        assertBefore(plan.executionOrder(), "c", "d");
    }

    @Test
    void failurePropagatesToDownstream() {
        // A(echo) + B(fail) → C(depends on A,B)
        ExecutionPlan plan = new ExecutionPlan("failure");
        plan.addTask(task("a", "echo", List.of()));
        plan.addTask(task("b", "fail", List.of()));
        plan.addTask(task("c", "echo", List.of("a", "b")));

        execute(plan);

        assertEquals(ExecutionPlan.Status.FAILED, plan.status());
        assertStatus(plan, "a", Task.Status.COMPLETED);
        assertStatus(plan, "b", Task.Status.FAILED);
        assertStatus(plan, "c", Task.Status.SKIPPED);
    }

    @Test
    void failureCascadesMultipleLevels() {
        // A(echo) → B(fail) → C → D
        ExecutionPlan plan = new ExecutionPlan("cascade");
        plan.addTask(task("a", "echo", List.of()));
        plan.addTask(task("b", "fail", List.of("a")));
        plan.addTask(task("c", "echo", List.of("b")));
        plan.addTask(task("d", "echo", List.of("c")));

        execute(plan);

        assertEquals(ExecutionPlan.Status.FAILED, plan.status());
        assertStatus(plan, "a", Task.Status.COMPLETED);
        assertStatus(plan, "b", Task.Status.FAILED);
        assertStatus(plan, "c", Task.Status.SKIPPED);
        assertStatus(plan, "d", Task.Status.SKIPPED);
    }

    @Test
    void unknownToolMarksFailed() {
        ExecutionPlan plan = new ExecutionPlan("unknown tool");
        plan.addTask(new Task(Task.Type.PLANNING, "x", "no_such_tool", args("x"), List.of()));

        execute(plan);

        assertEquals(ExecutionPlan.Status.FAILED, plan.status());
        assertStatus(plan, "x", Task.Status.FAILED);
        assertTrue(plan.task("x").isError());
    }

    @Test
    void missingDependencyMarksFailed() {
        ExecutionPlan plan = new ExecutionPlan("missing dep");
        // 引用了不存在的步骤 "ghost"
        plan.addTask(task("a", "echo", List.of("ghost")));

        execute(plan);

        assertStatus(plan, "a", Task.Status.FAILED);
        assertTrue(plan.task("a").result().contains("ghost"));
    }

    @Test
    void emptyPlanDoesNothing() {
        ExecutionPlan plan = new ExecutionPlan("empty");
        execute(plan);

        assertEquals(ExecutionPlan.Status.CREATED, plan.status());
    }

    // ── helpers ──

    private void execute(ExecutionPlan plan) {
        new DagExecutor(Agent.Listener.NOOP).execute(plan, tools);
    }

    private Task task(String id, List<String> deps) {
        return task(id, "echo", deps);
    }

    private Task task(String id, String tool, List<String> deps) {
        return new Task(Task.Type.PLANNING, id, tool, args(id), deps);
    }

    private ObjectNode args(String msg) {
        ObjectNode node = mapper.createObjectNode();
        node.put("msg", msg);
        return node;
    }

    private static void assertStatus(ExecutionPlan plan, String stepId, Task.Status expected) {
        assertEquals(expected, plan.task(stepId).status(),
                "step " + stepId + " expected " + expected + " but was " + plan.task(stepId).status()
                        + " (" + plan.task(stepId).result() + ")");
    }

    private static void assertBefore(List<String> order, String earlier, String later) {
        int i1 = order.indexOf(earlier);
        int i2 = order.indexOf(later);
        assertTrue(i1 >= 0, earlier + " not in order");
        assertTrue(i2 >= 0, later + " not in order");
        assertTrue(i1 < i2, earlier + " must be before " + later + " but order is " + order);
    }
}
