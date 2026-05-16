package com.javelin.agent.plan;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 执行单元——DagExecutor 操作的对象。
 *
 * 状态转换通过 mark*() 方法统一入口：
 *   PENDING ──markRunning()──→ RUNNING ──markCompleted()──→ COMPLETED
 *   PENDING ──markFailed()───→ FAILED           markFailed()
 *   PENDING ──markSkipped()──→ SKIPPED
 */
public class Task {

    /** 任务语义类型 */
    public enum Type {
        PLANNING,       // 规划任务：分析和决策
        FILE_READ,      // 读取文件：获取信息
        FILE_WRITE,     // 写入文件：输出结果
        COMMAND,        // 执行命令：编译运行等
        ANALYSIS,       // 分析结果：中间决策
        VERIFICATION    // 验证结果：检查正确性
    }

    /** 任务执行状态 */
    public enum Status {
        PENDING, RUNNING, COMPLETED, FAILED, SKIPPED
    }

    private final Type type;
    private final String stepId;
    private final String toolName;
    private final JsonNode toolArguments;
    private final List<String> dependsOn;

    private volatile Status status = Status.PENDING;
    private volatile String result = "";
    private volatile boolean isError = false;

    public Task(Type type, String stepId, String toolName, JsonNode toolArguments, List<String> dependsOn) {
        this.type = type != null ? type : Type.PLANNING;
        this.stepId = stepId;
        this.toolName = toolName;
        this.toolArguments = toolArguments;
        this.dependsOn = dependsOn != null ? dependsOn : List.of();
    }

    // ── 身份（不可变） ──
    public Type type()                  { return type; }
    public String stepId()              { return stepId; }
    public String toolName()            { return toolName; }
    public JsonNode toolArguments()     { return toolArguments; }
    public List<String> dependsOn()     { return dependsOn; }

    // ── 运行时状态（只读） ──
    public Status status()              { return status; }
    public String result()              { return result; }
    public boolean isError()            { return isError; }

    // ── 状态转换 ──

    /** PENDING → RUNNING */
    public void markRunning() {
        this.status = Status.RUNNING;
    }

    /** RUNNING → COMPLETED，同时记录成功输出 */
    public void markCompleted(String output) {
        this.status = Status.COMPLETED;
        this.result = output;
        this.isError = false;
    }

    /** → FAILED，同时记录错误信息 */
    public void markFailed(String error) {
        this.status = Status.FAILED;
        this.result = error;
        this.isError = true;
    }

    /** PENDING → SKIPPED，因前置步骤失败或存在环 */
    public void markSkipped(String reason) {
        this.status = Status.SKIPPED;
        this.result = reason;
    }
}
