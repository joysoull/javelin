# javelin

用 Java 17 模仿 Claude Code 的 Agent 架构，通过"边搭边学"理解其内部原理与机制。

> 不是追求生产可用，而是把 Agent 的核心机制拆开看——每写完一个模块，就理解一个概念。

## 当前能力

**ReAct 模式**：LLM 思考→工具调用→结果回填→再思考的完整闭环，双 LLM 协议支持。

**Plan and Execute 模式**：LLM 一次性生成完整执行计划，DAG 拓扑排序决定执行顺序，同层无依赖步骤并行执行，失败自动重规划。

```
/mode plan                              # 切换到 Plan 模式
在 plan/ 目录下创建 HelloWorld.java     # LLM 生成 3 步计划 → DAG 执行 → 审阅
```

## 架构

```
Main.java              REPL 入口（JLine + ANSI UI + /mode 切换）
Agent.java             ReAct 主循环
PlanAndExecuteAgent    Plan → Execute → Review 三阶段编排
├─ Planner             规划器（单次 LLM 调用 → create_plan → ExecutionPlan）
└─ DagExecutor         DAG 执行引擎（Kahn 拓扑排序 + 分层并行执行）
LlmProvider            接口（隔离具体 SDK）
├─ AnthropicProvider
└─ OpenAICompatProvider
Tool / ToolRegistry    工具接口 + 注册表（6 个内置工具 + create_plan）
```

## 快速开始

### 1. 配置

```bash
cp .env.example .env
# 编辑 .env，填入你的 API Key
```

```env
LLM_API_KEY=sk-你的key
LLM_PROVIDER=openai          # anthropic | openai
LLM_BASE_URL=https://api.deepseek.com/v1
LLM_MODEL=deepseek-chat
```

### 2. 运行

```bash
chcp 65001                   # 确保 UTF-8 终端（Windows）
mvn test                     # 运行 37 条测试
mvn exec:java                # 启动 REPL
```

### 3. 试试

```
you › 读一下 src/main/java/com/javelin/agent/Agent.java 的前 30 行
you › 搜索所有包含 "ReAct" 的文件
you › /mode plan
you › 在 plan/ 目录下创建 HelloWorld.java，打印 hello javelin，验证写入正确
```

### 斜杠命令

| 命令 | 作用 |
|---|---|
| `/help` | 显示帮助 |
| `/tools` | 列出所有工具 |
| `/mode react\|plan` | 切换 Agent 模式 |
| `/clear` | 清屏 |
| `/exit` | 退出 |

## 内置工具

| 工具 | 用途 |
|---|---|
| `calculator` | 四则运算 |
| `read_file` | 读取文件 |
| `write_file` | 写入文件 |
| `list_dir` | 列出目录 |
| `grep` | 正则搜索内容 |
| `glob` | 文件名匹配 |

## Plan and Execute 三阶段

| 阶段 | 负责 | 说明 |
|---|---|---|
| PLAN | Planner | 单次 LLM 调用，LLM 调 create_plan 提交结构化计划 |
| EXECUTE | DagExecutor | Kahn 拓扑排序，同层步骤并行执行，失败传播 |
| REVIEW | PlanAndExecuteAgent | LLM 审阅结果，失败时重规划（最多 3 轮） |

## 技术栈

| 依赖 | 用途 |
|---|---|
| Java 17 | 语言 |
| Maven | 构建 |
| anthropic-java 2.32.0 | Claude API |
| openai-java 4.35.0 | DeepSeek/GLM/Kimi 等 |
| JLine 4.0.14 | 终端行编辑 |
| JUnit 5 + Surefire | 测试框架（37 条测试） |

## 文档

- [第一期：ReAct Agent + Tool Use](docs/javelin-tutorial.md)
- [第二期：Plan and Execute + DAG](docs/javelin-tutorial-phase2.md)
- [源码合集](docs/javelin-source.md)

## 分期规划

- **第一期** ← 已完成：ReAct + Tool Use 完整循环
- **第二期** ← 当前：Plan and Execute + DAG 执行 + 重规划
- 后续：流式输出、上下文压缩、权限沙箱、子 Agent …
