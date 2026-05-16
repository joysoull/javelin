package com.javelin.agent.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 状态转换测试。
 *
 * 覆盖：
 *   1. 构造默认值
 *   2. 正常执行流：markRunning → markCompleted
 *   3. 失败流：markRunning → markFailed
 *   4. 跳过流：markSkipped
 *   5. 构造时 type 为 null 回退 EXPLORE、dependsOn 为 null 回退空列表
 */
class TaskTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void defaultValueIsPending() {
        Task t = sampleTask("a");
        assertEquals(Task.Status.PENDING, t.status());
        assertEquals("", t.result());
        assertFalse(t.isError());
    }

    @Test
    void markRunningTransitionsToRunning() {
        Task t = sampleTask("a");
        t.markRunning();
        assertEquals(Task.Status.RUNNING, t.status());
    }

    @Test
    void markCompletedSetsStatusAndResult() {
        Task t = sampleTask("a");
        t.markRunning();
        t.markCompleted("done");

        assertEquals(Task.Status.COMPLETED, t.status());
        assertEquals("done", t.result());
        assertFalse(t.isError());
    }

    @Test
    void markFailedSetsStatusResultAndErrorFlag() {
        Task t = sampleTask("a");
        t.markRunning();
        t.markFailed("something broke");

        assertEquals(Task.Status.FAILED, t.status());
        assertEquals("something broke", t.result());
        assertTrue(t.isError());
    }

    @Test
    void markSkippedFromPending() {
        Task t = sampleTask("a");
        t.markSkipped("dependency failed");

        assertEquals(Task.Status.SKIPPED, t.status());
        assertEquals("dependency failed", t.result());
        assertFalse(t.isError());
    }

    @Test
    void nullTypeDefaultsToExplore() {
        ObjectNode args = MAPPER.createObjectNode();
        Task t = new Task(null, "a", "echo", args, List.of());
        assertEquals(Task.Type.PLANNING, t.type());
    }

    @Test
    void nullDependsOnDefaultsToEmptyList() {
        ObjectNode args = MAPPER.createObjectNode();
        Task t = new Task(Task.Type.FILE_WRITE, "a", "echo", args, null);
        assertTrue(t.dependsOn().isEmpty());
    }

    @Test
    void identityFieldsPreserved() {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("file", "test.txt");
        Task t = new Task(Task.Type.FILE_WRITE, "step_2", "write_file", args, List.of("step_1"));

        assertEquals(Task.Type.FILE_WRITE, t.type());
        assertEquals("step_2", t.stepId());
        assertEquals("write_file", t.toolName());
        assertEquals("{\"file\":\"test.txt\"}", t.toolArguments().toString());
        assertEquals(List.of("step_1"), t.dependsOn());
    }

    private static Task sampleTask(String id) {
        return new Task(Task.Type.PLANNING, id, "echo", MAPPER.createObjectNode(), List.of());
    }
}
