package com.javelin.agent.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * 一个完整的执行计划——将多个 Task 按 DAG 组织在一起。
 *
 * 生命周期：
 *   1. PlanAndExecuteAgent 从 LLM 输出解析出 goal，直接构建 Task 存入 tasks Map
 *   2. DagExecutor 计算 executionOrder、执行所有 Task、更新 status
 *   3. PlanAndExecuteAgent 审阅执行结果
 *
 * 状态转换：
 *   CREATED ──markStarted()──→ RUNNING ──markCompleted()──→ COMPLETED
 *   CREATED ──markCancelled()─→ CANCELLED            markFailed()
 */
public class ExecutionPlan {

    /** 计划生命周期状态 */
    public enum Status {
        CREATED, RUNNING, COMPLETED, FAILED, CANCELLED
    }

    private final String id;
    private final String goal;
    private final LinkedHashMap<String, Task> tasks;
    private final List<String> executionOrder;

    private volatile Status status;
    private final long createdAt;
    private volatile long startTime;
    private volatile long endTime;

    public ExecutionPlan(String goal) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.goal = goal;
        this.tasks = new LinkedHashMap<>();
        this.executionOrder = new ArrayList<>();
        this.status = Status.CREATED;
        this.createdAt = System.currentTimeMillis();
    }

    /** 添加一个 Task，保持插入顺序 */
    public void addTask(Task task) {
        tasks.put(task.stepId(), task);
    }

    // ── 计划身份 ──
    public String id()                        { return id; }
    public String goal()                      { return goal; }
    public Status status()                    { return status; }

    // ── 时间 ──
    public long createdAt()                   { return createdAt; }
    public long startTime()                   { return startTime; }
    public long endTime()                     { return endTime; }

    /** 从 startTime 到 endTime（或当前时间）的耗时毫秒 */
    public long elapsedMs() {
        long end = endTime > 0 ? endTime : System.currentTimeMillis();
        long start = startTime > 0 ? startTime : createdAt;
        return end - start;
    }

    // ── Task 访问 ──
    /** 按插入顺序返回所有 Task */
    public List<Task> tasks() {
        return new ArrayList<>(tasks.values());
    }

    /** 按 stepId 查找 Task */
    public Task task(String stepId) {
        return tasks.get(stepId);
    }

    /** Task 总数 */
    public int taskCount() {
        return tasks.size();
    }

    /** 是否有任何 Task */
    public boolean isEmpty() {
        return tasks.isEmpty();
    }

    // ── 执行顺序 ──
    public List<String> executionOrder()      { return executionOrder; }

    /** 由 DagExecutor 在计算完拓扑排序后设置 */
    public void setExecutionOrder(List<String> order) {
        executionOrder.clear();
        executionOrder.addAll(order);
    }

    // ── 状态变更 ──

    /** CREATED → RUNNING，记录开始时间 */
    public void markStarted() {
        this.status = Status.RUNNING;
        this.startTime = System.currentTimeMillis();
    }

    /** RUNNING → COMPLETED，记录结束时间 */
    public void markCompleted() {
        this.status = Status.COMPLETED;
        this.endTime = System.currentTimeMillis();
    }

    /** RUNNING → FAILED，记录结束时间 */
    public void markFailed() {
        this.status = Status.FAILED;
        this.endTime = System.currentTimeMillis();
    }

    /** → CANCELLED，记录结束时间 */
    public void markCancelled() {
        this.status = Status.CANCELLED;
        this.endTime = System.currentTimeMillis();
    }
}
