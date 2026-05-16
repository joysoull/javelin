# javelin 第二期：Plan and Execute 模式 + DAG 执行

> **学习目标**：理解 Plan and Execute 架构——LLM 一次性生成完整执行计划，DAG 拓扑排序决定执行顺序，同层无依赖步骤并行执行，失败传播和重规划。
> 不涉及流式输出、上下文压缩、子 Agent、权限沙箱等高级机制。

## 1. 什么是 Plan and Execute

ReAct 模式中，LLM 每一步都要思考→行动→观察→再思考。这在复杂多步骤任务中效率低——每个步骤都要等上一轮的 LLM 调用返回。

Plan and Execute 的核心思想：**LLM 一次性想好全部步骤，系统按依赖关系自动调度执行**。

```
用户输入
  ↓
┌─ PLAN   ── 单次 LLM 调用，LLM 调用 create_plan 工具提交计划
├─ EXECUTE── DagExecutor 按 DAG 拓扑序并行执行所有步骤
└─ REVIEW ── LLM 审阅结果。如有失败，重规划（最多 3 轮）
  ↓
最终输出
```

## 2. 架构分层

```
┌──────────────────────────────────────────┐
│  Main.java         REPL 入口              │  ← /mode plan 切换到 Plan 模式
├──────────────────────────────────────────┤
│  PlanAndExecuteAgent.java   编排层        │  ← Plan → Execute → Review 三阶段
├──────────────────────────────────────────┤
│  Planner.java        规划器               │  ← 单次 LLM 调用 → create_plan → ExecutionPlan
├──────────────────────────────────────────┤
│  DagExecutor.java     DAG 执行引擎        │  ← Kahn 拓扑排序 + 分层并行执行
├──────────────────────────────────────────┤
│  ExecutionPlan.java   计划容器            │  ← goal + tasks + executionOrder + 生命周期
├──────────────────────────────────────────┤
│  Task.java            执行单元            │  ← Type + Status 枚举 + 状态转换方法
└──────────────────────────────────────────┘
```

**对比第一期**：ReAct Agent 约 170 行一个类搞定。Plan and Execute 拆成 5 个类——规划、执行、计划、任务各司其职。

## 3. 纯 Plan and Execute vs Explore-Plan-Execute

设计过程中经历了一次关键抉择。

**最初实现**：Planner 内部跑一个 ReAct 子循环，LLM 先探索环境（list_dir、read_file 等），再调 create_plan。这其实是 Explore-Plan-Execute。

**最终选择**：去掉探索循环，Planner 只注册 create_plan 一个工具，单次 LLM 调用生成计划。LLM 通过 system prompt 中的工具清单了解可用工具，不能提前读文件。

```
Planner.plan() 的完整逻辑：

    String prompt = "可用工具:\n" + toolSummary + "\n\n用户需求: " + userInput;
    
    List<LlmMessage> history = new ArrayList<>();
    history.add(LlmMessage.user(prompt));

    // 单次调用，只有 create_plan 一个工具
    LlmResponse resp = llm.chat(history, toolDefs, planningPrompt);

    if (resp.needsToolExecution()) {
        ExecutionPlan plan = parseToolCalls(resp.toolCalls());
        if (plan != null) return plan;
    }
    return null;
```

对应代码在 [Planner.java 的 plan() 方法](src/main/java/com/javelin/agent/plan/Planner.java)。

### 规划阶段的 system prompt

Planner 有专用的系统指令，覆盖 Main.java 的通用 prompt：

```
你是计划制定者。收到用户需求后，你必须调用 create_plan 工具提交一个完整的执行计划。
不要直接回答用户的问题，不要执行任何操作——只生成计划。
计划中的每个步骤使用下面列出的可用工具，步骤之间用 depends_on 声明依赖关系。
```

### LLM 如何知道有哪些工具

Planner 构造时接收执行工具注册表，提取每个工具的名称、描述、required 参数，拼成文本嵌入用户消息：

```
可用工具:
  write_file — 创建或覆盖文件  参数: file_path, content
  read_file — 读取文件，带行号  参数: file_path
  list_dir — 列出目录，支持递归
  grep — 正则搜索文件内容  参数: pattern
  glob — 通配符匹配文件名  参数: pattern
  calculator — 四则运算表达式求值  参数: expression
```

这样 LLM 知道工具名和参数名，就不会凭空猜出 `bash` 或 `filePath`。

## 4. Task：执行单元

### 4.1 类型系统

`Task.Type` 有 6 种，按行为语义划分：

| 类型 | 语义 | 典型工具 |
|---|---|---|
| `PLANNING` | 规划任务：分析和决策 | — |
| `FILE_READ` | 读取文件：获取信息 | read_file, grep, glob |
| `FILE_WRITE` | 写入文件：输出结果 | write_file |
| `COMMAND` | 执行命令：编译运行等 | 未来的 shell |
| `ANALYSIS` | 分析结果：中间决策 | — |
| `VERIFICATION` | 验证结果：检查正确性 | read_file（验证性使用） |

### 4.2 状态转换

`Task.Status` 有 5 种，通过 `mark*()` 方法统一入口：

```
PENDING ──markRunning()──→ RUNNING ──markCompleted()──→ COMPLETED
PENDING ──markFailed()───→ FAILED           markFailed()
PENDING ──markSkipped()──→ SKIPPED
```

```java
// 合法用法：一个原子调用搞定状态 + 结果
task.markRunning();
task.markCompleted("echo: hello");           // 状态→COMPLETED, 结果已设
task.markFailed("error: 未知工具 'bash'");    // 状态→FAILED, isError=true

// 不再允许裸 setter——外部无法写出"状态 COMPLETED 但 result 为空"这种不一致
```

对应代码在 [Task.java](src/main/java/com/javelin/agent/plan/Task.java)。

## 5. ExecutionPlan：计划容器

### 5.1 结构

```java
public class ExecutionPlan {
    String id;                            // UUID 前 8 位，追踪用
    String goal;                          // LLM 给出的总体目标
    LinkedHashMap<String, Task> tasks;    // stepId → Task，保持插入顺序
    List<String> executionOrder;          // DagExecutor 计算出的拓扑序
    Status status;                        // 生命周期状态
    long createdAt, startTime, endTime;   // 时间戳
}
```

### 5.2 生命周期

```
CREATED ──markStarted()──→ RUNNING ──markCompleted()──→ COMPLETED
                                      markFailed()    ──→ FAILED
CREATED ──markCancelled()─→ CANCELLED
```

状态转换 + 时间戳让每个计划有可追踪的执行记录。`elapsedMs()` 从 startTime 到 endTime（或当前时间）计算耗时。

对应代码在 [ExecutionPlan.java](src/main/java/com/javelin/agent/plan/ExecutionPlan.java)。

## 6. DAG 执行引擎

### 6.1 Kahn 算法

DagExecutor 接收 ExecutionPlan，从中读取所有 Task 的 `dependsOn`，构建入度表和邻接表，用 Kahn 算法分层并行执行。

**核心数据结构**：

```
indegree:   每个 Task 还剩多少个前置未完成
dependents: 前置步骤 → 依赖它的后续步骤列表
ready:      当前入度为 0 的步骤队列（可以立即执行）
```

**算法步骤**：

```
1. 初始化 indegree[taskId] = task.dependsOn.size()
2. 入度为 0 的加入 ready 队列
3. while ready 不空:
     a. 当前层 = ready 中所有节点 → 提交到线程池并行执行
     b. 等待当前层全部完成
     c. 每个完成节点：下游入度 -1；入度为 0 的加入 ready
4. 未执行的节点（环内或依赖失败）→ 标记 SKIPPED
5. 根据 Task 结果设置 plan 状态
```

对应代码在 [DagExecutor.java 的 execute() 方法](src/main/java/com/javelin/agent/plan/DagExecutor.java)。

### 6.2 依赖解析示例

```
步骤 A (无依赖) ──┐
                  ├──→ 步骤 C (依赖 A, B)
步骤 B (无依赖) ──┘
                        ↓
                  步骤 D (依赖 C)
```

执行过程：

```
初始化: indegree[A]=0, indegree[B]=0, indegree[C]=2, indegree[D]=1
        ready = [A, B]

第 1 层: 并行执行 [A, B]
         A 完成 → C 入度-1=1
         B 完成 → C 入度-1=0 → ready = [C]

第 2 层: 执行 [C]
         C 完成 → D 入度-1=0 → ready = [D]

第 3 层: 执行 [D] → ready 空，完成

executionOrder = [A, B, C, D]
```

### 6.3 失败传播

步骤 B 失败 → 其下游 C 的入度减 1（从 2 到 1）但永不归 0 → C 永不入队。C 和 D 最终被 `markSkipped`。

```
skipDownstream("B"):
    C.markSkipped("跳过：前置步骤 'B' 执行失败")
    skipDownstream("C"):
        D.markSkipped("跳过：前置步骤 'C' 执行失败")
```

对应代码在 [DagExecutor.java 的 skipDownstream()](src/main/java/com/javelin/agent/plan/DagExecutor.java#L168)。

### 6.4 并行执行

同层步骤提交到固定 4 线程的 daemon 线程池并行执行。线程池不随单次 execute() 关闭——复用支持重规划。

```java
ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
    Thread t = new Thread(r, "dag-executor");
    t.setDaemon(true);
    return t;
});
```

## 7. PlanAndExecuteAgent：编排层

三阶段流程的完整实现：

```java
public String chat(String userInput) {
    // 阶段 1: PLAN
    ExecutionPlan plan = planner.plan(userInput);
    printPlanPreview(plan);

    for (int round = 1; round <= 3; round++) {
        // 阶段 2: EXECUTE
        dagExecutor.execute(plan, tools);

        // 阶段 3: REVIEW
        review(userInput, plan);

        if (全部成功 || 最后一轮) return "";

        // 失败 → 重规划
        plan = replan(userInput, plan);
    }
}
```

对应代码在 [PlanAndExecuteAgent.java](src/main/java/com/javelin/agent/PlanAndExecuteAgent.java)。

### 重规划（replan）

与初次规划不同，重规划是单次 LLM 调用——不给探索机会，LLM 收到失败汇总后直接调 create_plan 提交修正方案。

## 8. create_plan 工具

`CreatePlanTool` 是 LLM 用来提交计划的特殊工具。它不会被真正"执行"——Planner 在 parseToolCalls 中检测到就拦截。

inputSchema 的完整结构：

```json
{
  "type": "object",
  "properties": {
    "goal": { "type": "string", "description": "总体目标" },
    "steps": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "type": { "type": "string", "enum": ["PLANNING","FILE_READ","FILE_WRITE","COMMAND","ANALYSIS","VERIFICATION"] },
          "id": { "type": "string" },
          "description": { "type": "string" },
          "tool": { "type": "string" },
          "arguments": { "type": "object" },
          "depends_on": { "type": "array", "items": { "type": "string" } }
        },
        "required": ["type", "id", "description", "tool", "arguments"]
      }
    }
  },
  "required": ["goal", "steps"]
}
```

对应代码在 [CreatePlanTool.java](src/main/java/com/javelin/tool/builtin/CreatePlanTool.java)。

## 9. UI 改进

第二期对终端输出做了三处优化：

| 改进 | 效果 |
|---|---|
| 计划预览 | 执行前展示所有步骤及依赖关系 |
| 步骤进度 | 阶段标签显示步骤 ID（`执行 step_1, step_2`）而非模糊的"并行执行 N 个" |
| 去重 | Plan 模式 review 已在 `<助手>` 展示，不再重复渲染 `<回答>` Box |

## 10. 测试

pom.xml 新增 JUnit 5 + maven-surefire-plugin，每次 `mvn test` / `mvn package` 自动运行。

```
src/test/java/com/javelin/agent/plan/
├── TaskTest.java            8 条：构造默认、状态转换、null 回退
├── ExecutionPlanTest.java  13 条：生命周期、插入顺序、时间戳、防御性拷贝
├── PlannerTest.java         8 条：parseToolCalls 解析、缺失字段、未知类型回退
└── DagExecutorTest.java     8 条：无依赖并行、串行链、菱形依赖、失败传播、级联跳过
```

37 条全部通过。测试不依赖 LLM——用假工具直接构造 ToolCall 或 ExecutionPlan。

## 11. 模式切换

Main.java 新增 `/mode` 命令：

```
you › /mode plan          # 切换到 Plan and Execute
已切换到 plan 模式
you › /mode react         # 切回 ReAct
已切换到 react 模式
```

两种模式共用同一套工具和 Provider，Agent 零修改。

## 12. 项目文件结构

```
D:\javelin\
├── pom.xml                                   新增 JUnit 5 + surefire
├── docs/
│   ├── javelin-tutorial.md                   第一期教程
│   └── javelin-tutorial-phase2.md            ← 第二期教程
└── src/
    ├── main/java/com/javelin/
    │   ├── Main.java                         新增 /mode 命令
    │   ├── agent/
    │   │   ├── Agent.java                    ReAct Agent（未修改）
    │   │   └── PlanAndExecuteAgent.java      ← Plan & Execute 编排层
    │   ├── llm/                              （第一期，未修改）
    │   ├── tool/
    │   │   ├── Tool.java / ToolRegistry.java  （第一期，未修改）
    │   │   └── builtin/
    │   │       ├── CalculatorTool.java ...    （第一期，未修改）
    │   │       └── CreatePlanTool.java        ← 计划提交工具
    │   ├── config/                           （第一期，未修改）
    │   └── ui/                               （第一期，未修改）
    └── test/java/com/javelin/agent/plan/     ← 测试
        ├── TaskTest.java
        ├── ExecutionPlanTest.java
        ├── PlannerTest.java
        └── DagExecutorTest.java
```

新增 10 个源文件，修改 2 个（pom.xml, Main.java），0 个删除。第一期 ReAct Agent 零侵入。
