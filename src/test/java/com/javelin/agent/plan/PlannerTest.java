package com.javelin.agent.plan;

import com.javelin.agent.Agent;
import com.javelin.llm.ToolCall;
import com.javelin.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Planner 单元测试。
 *
 * 不依赖 LLM——直接构造 ToolCall 测试 parseToolCalls 解析逻辑。
 */
class PlannerTest {

    private final Planner planner = new Planner(null, new ToolRegistry(), null, Agent.Listener.NOOP);

    @Test
    void parseValidPlanReturnsExecutionPlan() {
        String json = """
                {
                  "goal": "创建测试文件并验证",
                  "steps": [
                    {
                      "type": "FILE_WRITE",
                      "id": "1",
                      "description": "写入文件",
                      "tool": "write_file",
                      "arguments": {"file_path": "test.txt", "content": "hello"}
                    },
                    {
                      "type": "VERIFICATION",
                      "id": "2",
                      "description": "验证文件",
                      "tool": "read_file",
                      "arguments": {"file_path": "test.txt"},
                      "depends_on": ["1"]
                    }
                  ]
                }""";

        ToolCall tc = new ToolCall("call_1", "create_plan", json);
        ExecutionPlan plan = planner.parseToolCalls(List.of(tc));

        assertNotNull(plan);
        assertEquals("创建测试文件并验证", plan.goal());
        assertEquals(ExecutionPlan.Status.CREATED, plan.status());
        assertEquals(2, plan.taskCount());

        Task task1 = plan.task("1");
        assertEquals(Task.Type.FILE_WRITE, task1.type());
        assertEquals("write_file", task1.toolName());
        assertTrue(task1.dependsOn().isEmpty());

        Task task2 = plan.task("2");
        assertEquals(Task.Type.VERIFICATION, task2.type());
        assertEquals(List.of("1"), task2.dependsOn());
    }

    @Test
    void parseWithoutCreatePlanReturnsNull() {
        ToolCall tc = new ToolCall("call_1", "read_file", "{\"file_path\": \"test.txt\"}");
        ExecutionPlan plan = planner.parseToolCalls(List.of(tc));
        assertNull(plan);
    }

    @Test
    void parseEmptyStepsReturnsEmptyPlan() {
        String json = """
                {"goal": "nothing", "steps": []}""";

        ToolCall tc = new ToolCall("call_1", "create_plan", json);
        ExecutionPlan plan = planner.parseToolCalls(List.of(tc));

        assertNotNull(plan);
        assertEquals("nothing", plan.goal());
        assertTrue(plan.isEmpty());
    }

    @Test
    void parseMissingTypeDefaultsToPlanning() {
        String json = """
                {
                  "goal": "test",
                  "steps": [
                    {"id": "1", "tool": "echo", "arguments": {}}
                  ]
                }""";

        ToolCall tc = new ToolCall("call_1", "create_plan", json);
        ExecutionPlan plan = planner.parseToolCalls(List.of(tc));

        assertEquals(Task.Type.PLANNING, plan.task("1").type());
    }

    @Test
    void parseInvalidJsonReturnsNull() {
        ToolCall tc = new ToolCall("call_1", "create_plan", "not json");
        ExecutionPlan plan = planner.parseToolCalls(List.of(tc));
        assertNull(plan);
    }

    @Test
    void parseUnknownTypeDefaultsToPlanning() {
        String json = """
                {
                  "goal": "test",
                  "steps": [
                    {"type": "UNKNOWN_TYPE", "id": "1", "tool": "echo", "arguments": {}}
                  ]
                }""";

        ToolCall tc = new ToolCall("call_1", "create_plan", json);
        ExecutionPlan plan = planner.parseToolCalls(List.of(tc));

        assertEquals(Task.Type.PLANNING, plan.task("1").type());
    }

    @Test
    void parseMultipleToolCallsFindsCreatePlan() {
        ToolCall other = new ToolCall("call_0", "read_file", "{\"file_path\": \"x\"}");
        String json = """
                {"goal": "test", "steps": [
                  {"type": "FILE_READ", "id": "1", "tool": "read_file", "arguments": {}}
                ]}""";
        ToolCall createPlan = new ToolCall("call_1", "create_plan", json);

        ExecutionPlan plan = planner.parseToolCalls(List.of(other, createPlan));
        assertNotNull(plan);
        assertEquals(1, plan.taskCount());
    }

    @Test
    void parseStepsWithArgumentsPreservesJson() {
        String json = """
                {
                  "goal": "test",
                  "steps": [
                    {
                      "type": "COMMAND",
                      "id": "1",
                      "tool": "echo",
                      "arguments": {"msg": "hello", "count": 42}
                    }
                  ]
                }""";

        ToolCall tc = new ToolCall("call_1", "create_plan", json);
        ExecutionPlan plan = planner.parseToolCalls(List.of(tc));

        Task task = plan.task("1");
        assertEquals("{\"msg\":\"hello\",\"count\":42}", task.toolArguments().toString());
    }
}
