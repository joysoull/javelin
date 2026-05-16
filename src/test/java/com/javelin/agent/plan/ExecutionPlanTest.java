package com.javelin.agent.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExecutionPlan 生命周期测试。
 *
 * 覆盖：
 *   1. 构造默认状态 CREATED + 时间戳
 *   2. addTask 维护插入顺序
 *   3. 状态转换：markStarted → markCompleted / markFailed / markCancelled
 *   4. executionOrder 设置和替换
 *   5. isEmpty / taskCount / task 查找
 *   6. elapsedMs 耗时计算
 */
class ExecutionPlanTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void newPlanIsCreatedWithGeneratedId() {
        ExecutionPlan plan = new ExecutionPlan("test goal");
        assertEquals(ExecutionPlan.Status.CREATED, plan.status());
        assertEquals("test goal", plan.goal());
        assertNotNull(plan.id());
        assertEquals(8, plan.id().length());
        assertTrue(plan.createdAt() > 0);
    }

    @Test
    void newPlanIsEmpty() {
        ExecutionPlan plan = new ExecutionPlan("test");
        assertTrue(plan.isEmpty());
        assertEquals(0, plan.taskCount());
    }

    @Test
    void addTaskMaintainsInsertionOrder() {
        ExecutionPlan plan = new ExecutionPlan("test");
        plan.addTask(sampleTask("a"));
        plan.addTask(sampleTask("b"));
        plan.addTask(sampleTask("c"));

        assertEquals(3, plan.taskCount());
        assertFalse(plan.isEmpty());

        List<Task> tasks = plan.tasks();
        assertEquals("a", tasks.get(0).stepId());
        assertEquals("b", tasks.get(1).stepId());
        assertEquals("c", tasks.get(2).stepId());
    }

    @Test
    void taskLookupById() {
        ExecutionPlan plan = new ExecutionPlan("test");
        Task a = sampleTask("step_1");
        plan.addTask(a);

        assertSame(a, plan.task("step_1"));
        assertNull(plan.task("nonexistent"));
    }

    @Test
    void markStartedSetsRunningAndStartTime() {
        ExecutionPlan plan = new ExecutionPlan("test");
        plan.markStarted();

        assertEquals(ExecutionPlan.Status.RUNNING, plan.status());
        assertTrue(plan.startTime() > 0);
        assertEquals(0, plan.endTime());
    }

    @Test
    void markCompletedSetsStatusAndEndTime() {
        ExecutionPlan plan = new ExecutionPlan("test");
        plan.markStarted();
        plan.markCompleted();

        assertEquals(ExecutionPlan.Status.COMPLETED, plan.status());
        assertTrue(plan.endTime() > 0);
    }

    @Test
    void markFailedSetsStatusAndEndTime() {
        ExecutionPlan plan = new ExecutionPlan("test");
        plan.markStarted();
        plan.markFailed();

        assertEquals(ExecutionPlan.Status.FAILED, plan.status());
        assertTrue(plan.endTime() > 0);
    }

    @Test
    void markCancelledSetsStatusAndEndTime() {
        ExecutionPlan plan = new ExecutionPlan("test");
        plan.markCancelled();

        assertEquals(ExecutionPlan.Status.CANCELLED, plan.status());
        assertTrue(plan.endTime() > 0);
    }

    @Test
    void elapsedMsCalculatesDuration() throws InterruptedException {
        ExecutionPlan plan = new ExecutionPlan("test");
        plan.markStarted();

        Thread.sleep(5);
        plan.markCompleted();

        assertTrue(plan.elapsedMs() >= 5);
    }

    @Test
    void elapsedMsBeforeEndUsesCurrentTime() {
        ExecutionPlan plan = new ExecutionPlan("test");
        plan.markStarted();

        long elapsed = plan.elapsedMs();
        assertTrue(elapsed >= 0);
    }

    @Test
    void setExecutionOrderPersistsList() {
        ExecutionPlan plan = new ExecutionPlan("test");
        plan.setExecutionOrder(List.of("a", "b", "c"));
        assertEquals(List.of("a", "b", "c"), plan.executionOrder());
    }

    @Test
    void setExecutionOrderReplacesPrevious() {
        ExecutionPlan plan = new ExecutionPlan("test");
        plan.setExecutionOrder(List.of("x", "y"));
        plan.setExecutionOrder(List.of("a", "b"));
        assertEquals(List.of("a", "b"), plan.executionOrder());
    }

    @Test
    void tasksReturnsDefensiveCopy() {
        ExecutionPlan plan = new ExecutionPlan("test");
        plan.addTask(sampleTask("a"));

        List<Task> copy = plan.tasks();
        copy.clear();

        assertEquals(1, plan.taskCount());
    }

    private static Task sampleTask(String id) {
        return new Task(Task.Type.PLANNING, id, "echo", MAPPER.createObjectNode(), List.of());
    }
}
