# javelin 第一期：ReAct Agent + Tool Use 完整实现

> **学习目标**：理解 Agent 最基础也最核心的机制——LLM 思考 → 工具调用 → 结果回填 → 再思考的完整闭环。
> 不涉及流式输出、上下文压缩、子 Agent、权限沙箱等高级机制。

## 1. 项目概览

javelin 是一个学习型命令行 Agent，目标是复刻 Claude Code 的核心机制，通过"边写边学"理解 Agent 的各个组成部分。

| 维度 | 选型 |
|---|---|
| 语言 | Java 17 |
| 构建 | Maven（anthropic-java + openai-java + JLine） |
| 交互 | 命令行 REPL（JLine 行编辑 + ANSI 终端渲染） |
| LLM | 双协议：Anthropic Messages API / OpenAI Chat Completions API |
| 学习重点 | ReAct 循环、Tool use 完整链路、Provider 抽象、双协议对比 |

---

## 2. 架构分层

```
┌─────────────────────────────────────────┐
│  Main.java         REPL 入口             │  ← JLine 读输入、选择 Provider、打印 UI
├─────────────────────────────────────────┤
│  Agent.java        ReAct 主循环          │  ← 调 LLM → 解析 → 执行工具 → 回填 → 再调
├─────────────────────────────────────────┤
│  LlmProvider       接口                  │  ← 对接 LLM 的抽象，隔离具体 SDK
├─────────────────────────────────────────┤
│  AnthropicProvider  OpenAICompatProvider │  ← 两种协议实现，封装 SDK 差异
├─────────────────────────────────────────┤
│  Tool / ToolRegistry                    │  ← 工具接口 + 注册表
├─────────────────────────────────────────┤
│  Calculator / ReadFile / WriteFile ...  │  ← 6 个内置工具
├─────────────────────────────────────────┤
│  Ansi / Box / MdAnsi   DotEnv            │  ← UI 渲染 + 配置加载
└─────────────────────────────────────────┘
```

**依赖规则**：上层可依赖下层，反之不可。Agent 只依赖 `LlmProvider` 接口，不 import 任何 SDK 类型。

---

## 3. ReAct 循环（核心机制）

ReAct = Reasoning + Acting。Agent 不是"一次性回答"，而是在一个 `while` 循环里反复调用 LLM，每次根据 LLM 的响应决定下一步。

### 3.1 循环步骤

```
一次用户输入后，Agent 进入循环：

  ┌─→ 1. 调 LLM：发送完整消息历史 + 工具定义
  │   2. 收响应：可能含 text、tool_use（工具调用）、reasoning（思考过程）
  │   3. 判断 stopReason：
  │      tool_use / tool_calls → 继续，走步骤 4
  │      end_turn / stop       → 退出，返回最终文本
  │   4. 执行工具：本地调用 Tool.execute()
  │   5. 回填历史：将工具结果以 tool_result 消息追加到历史
  └── 回到步骤 1
```

对应代码在 [Agent.java 的 chat() 方法](src/main/java/com/javelin/agent/Agent.java)。

### 3.2 消息历史的累积

以一个计算任务为例，历史消息列表的演变过程：

```
迭代 1 开始:
  [0] user:       "请计算 23 * 47"

迭代 1 调 LLM 后:
  [0] user:       "请计算 23 * 47"
  [1] assistant:  tool_use: calculator("23*47")    ← LLM 决定使用计算器

执行工具 calculator → "1081" 后:
  [0] user:       "请计算 23 * 47"
  [1] assistant:  tool_use: calculator("23*47")
  [2] user:       tool_result: calculator → "1081"  ← 工具结果回灌

迭代 2 调 LLM，LLM 看到完整历史后:
  [0] user:       "请计算 23 * 47"
  [1] assistant:  tool_use: calculator("23*47")
  [2] user:       tool_result: calculator → "1081"
  [3] assistant:  "23 × 47 = 1081"                  ← LLM 给出最终答案
  stopReason = end_turn → 退出循环
```

### 3.3 stopReason 判断

这是循环的退出条件。两种协议用不同字段，含义相同：

| 协议 | 需要执行工具 | 对话结束 |
|---|---|---|
| Anthropic | `stop_reason: "tool_use"` | `stop_reason: "end_turn"` |
| OpenAI | `finish_reason: "tool_calls"` | `finish_reason: "stop"` |

Agent 通过 `LlmResponse.needsToolExecution()` 统一判断，不关心底层是哪种协议。

### 3.4 错误回灌

工具执行失败时不抛异常，而是将错误信息以 `ToolResultBlock(isError=true)` 的形式返回给 LLM。LLM 看到错误后可以自行修正（例如换一种参数格式重试）。

### 3.5 防死循环

`MAX_ITERATIONS = 10`，超过后抛 RuntimeException，防止工具实现有 bug 导致 LLM 反复重试。

---

## 4. 消息与类型系统

设计目标：Agent 只能操作中性类型，完全不感知底层是 Anthropic 还是 OpenAI。

### 4.1 类型全景

```
LlmMessage        一条对话消息（user 或 assistant）
  ├─ Role          USER / ASSISTANT
  ├─ text          纯文本
  ├─ reasoningContent  推理模型的思考过程
  ├─ toolCalls     assistant 消息里的工具调用列表（List<ToolCall>）
  └─ toolResults   user 消息里的工具结果列表（List<ToolResultBlock>）

LlmResponse       一次 LLM 调用的返回值
  ├─ text          回复文本
  ├─ toolCalls     工具调用列表
  ├─ stopReason    停止原因（tool_use / tool_calls / end_turn / stop）
  └─ reasoningContent  思考过程

ToolCall          一个工具调用
  ├─ id            工具调用 ID（回灌时与结果一一对应）
  ├─ name          工具名
  └─ argumentsJson 参数的 JSON 字符串

ToolDef           一个工具定义
  ├─ name          工具名
  ├─ description   给 LLM 看的功能说明
  └─ parametersJson  参数的 JSON Schema（含 type/properties/required）
```

### 4.2 为什么 Role 只有两种

没有 `SYSTEM` 和 `TOOL` 角色。原因：

- **System prompt** 在整个对话中不变，不适合参与消息累积。作为 `LlmProvider.chat()` 的独立参数传入，Provider 内部决定放哪个位置。
- **Tool 结果** 在 Anthropic 协议中是 user 消息里的 content block，在 OpenAI 协议中是独立的 `role: "tool"` 消息。用子结构 `ToolResultBlock` 附加在 user 消息上，由 Provider 内部按需转换，避免双向适配。

### 4.3 reasoningContent 的透传

DeepSeek V4 Pro 等推理模型在思考模式下会返回 `reasoning_content`。这个字段必须在后续请求的 assistant 消息中原样回传，否则 API 报 400。

透传链路：`OpenAICompatProvider 解析响应` → `LlmResponse.reasoningContent` → `LlmMessage.reasoningContent` → `OpenAICompatProvider 构建下轮请求时通过 _additionalProperties 塞回`

---

## 5. Provider 抽象

### 5.1 接口设计

```java
public interface LlmProvider {
    LlmResponse chat(
        List<LlmMessage> messages,   // 完整消息历史
        List<ToolDef> tools,         // 工具定义列表
        String systemPrompt          // 系统提示词
    );
}
```

极简单的单一方法。不做流式、不做异步，保持接口能在一屏内看完。

### 5.2 两种协议的核心差异

| 维度 | Anthropic | OpenAI 兼容 |
|---|---|---|
| 请求端点 | `/v1/messages` | `/v1/chat/completions` |
| 工具定义字段 | `tools[].input_schema` | `tools[].function.parameters` |
| 工具调用位置 | `content[]` 数组中的 `tool_use` 块 | `message.tool_calls[]` 独立字段 |
| 工具参数 | `input` 是 JSON 对象 | `arguments` 是 JSON 字符串 |
| 工具结果回灌 | user 角色消息 + `tool_result` content block | `role: "tool"` 独立消息 + `tool_call_id` |
| 停止原因 | `stop_reason` | `choices[0].finish_reason` |
| 系统提示词 | 请求体的 `system` 字段 | `messages[]` 中的一条 `role: "system"` 消息 |

详见 `AnthropicProvider.java` 和 `OpenAICompatProvider.java` 中的注释。

### 5.3 OpenAICompatProvider 的覆盖范围

由于 DeepSeek、GLM、Kimi、通义千问等都提供 OpenAI 兼容接口（`/v1/chat/completions`），`OpenAICompatProvider` 一份代码覆盖所有这些服务。切换只需改 `.env` 中的 `LLM_BASE_URL` 和 `LLM_MODEL`。

---

## 6. 工具系统

### 6.1 工具接口

```java
public interface Tool {
    String name();             // 唯一标识符，英文小写下划线
    String description();      // 给 LLM 看的功能描述
    JsonNode inputSchema();    // JSON Schema，描述参数结构
    String execute(JsonNode input);  // 本地执行逻辑
}
```

工具的两面性：
- **面向 LLM**：name + description + inputSchema 打包发给 LLM，告诉它"有哪些能力、怎么调用"
- **面向本地**：execute 是真正的执行逻辑，LLM 永远不跑代码，只决定要不要调、参数是什么

### 6.2 工具注册

`ToolRegistry` 用 LinkedHashMap 存储，保留注册顺序。Agent 启动时将所有工具转为 `ToolDef` 列表，每次 LLM 调用都带上。

### 6.3 当前工具一览

| 工具 | 作用 | 典型输入 |
|---|---|---|
| calculator | 四则运算表达式求值 | `{"expression": "(23+7)*4"}` |
| read_file | 读取文件，带行号 | `{"file_path": "src/Main.java", "offset": 10, "limit": 20}` |
| write_file | 创建或覆盖文件 | `{"file_path": "out.txt", "content": "hello"}` |
| list_dir | 列出目录，支持递归 | `{"path": "src/", "depth": 2}` |
| grep | 正则搜索文件内容 | `{"pattern": "class Agent", "glob": "*.java"}` |
| glob | 通配符匹配文件名 | `{"pattern": "**/*.java"}` |

---

## 7. 配置与启动

### 7.1 .env 文件

```
LLM_API_KEY=sk-xxx          # 必填：API Key
LLM_PROVIDER=openai          # 可选：anthropic | openai（默认 openai）
LLM_BASE_URL=https://...     # 可选：API 端点
LLM_MODEL=deepseek-chat      # 可选：模型名
LLM_THINKING=disabled        # 可选：禁用 DeepSeek 思考模式
```

`DotEnv` 是一个约 60 行的 .env 解析器，不引第三方库。支持 `KEY=VALUE`、`#` 注释、双引号/单引号、`export` 前缀。

### 7.2 Provider 选择逻辑

```
LLM_PROVIDER 变量:
  "anthropic" → AnthropicProvider
  "openai"    → OpenAICompatProvider
  未设置      → OpenAICompatProvider（覆盖面更广）
```

### 7.3 环境变量优先级

`.env` 文件优先，找不到再回退到系统环境变量 `System.getenv()`。

---

## 8. UI 层

### 8.1 组件职责

| 类 | 负责 |
|---|---|
| `Ansi` | ANSI 转义序列常量 + 颜色 helper（`Ansi.cyan("...")`等） |
| `Box` | 圆角左边框渲染器，用于工具调用/结果的卡片式展示 |
| `MdAnsi` | 极简 Markdown → ANSI 转义，覆盖粗体、斜体、代码、标题、围栏 |

### 8.2 交互方式

JLine 的 `LineReader` 提供行编辑、历史、Tab 补全。斜杠命令（`/help`、`/tools`、`/clear`、`/exit`）通过 `StringsCompleter` 自动补全。

### 8.3 ReAct 过程的可视化

`AgentListener` 接口有 5 个回调：`onPhase`、`onReasoning`、`onAssistantText`、`onToolUse`、`onToolResult`。`ConsoleListener`（位于 `ui` 包）把它们渲染为 ANSI 着色的 Box，让用户肉眼看到每一步。

### 8.4 与 Claude Code UI 的差距

当前是"基础套餐"。以下故意留到后续期次：
- 流式输出（依赖 SSE / streaming API）
- 状态栏（模型名、token 数固定在底部）
- 中断（Ctrl+C 取消当前请求继续 REPL）
- `@filename` 文件引用补全

---

## 9. 项目文件结构

```
D:\javelin\
├── .env                          API 配置（不入 git）
├── .env.example                  配置模板
├── .gitignore
├── pom.xml                       Maven 构建
├── .claude/
│   └── settings.local.json      Claude Code 权限（本机）
├── .vscode/
│   ├── settings.json            VS Code 工作区配置（UTF-8 终端）
│   └── launch.json              调试启动配置
├── docs/
│   └── javelin-source.md        源码合集（机器生成）
└── src/main/java/com/javelin/
    ├── Main.java                REPL 入口、依赖组装
    ├── agent/
    │   ├── Agent.java           ReAct 主循环 ★
    │   └── AgentListener.java   ReAct / Plan-Execute 观察者接口
    ├── llm/
    │   ├── LlmProvider.java     接口
    │   ├── LlmMessage.java      中性消息
    │   ├── LlmResponse.java     中性响应
    │   ├── ToolCall.java        中性工具调用
    │   ├── ToolDef.java         中性工具定义
    │   └── impl/
    │       ├── AnthropicProvider.java    包装 anthropic-java
    │       └── OpenAICompatProvider.java 包装 openai-java
    ├── tool/
    │   ├── Tool.java            接口
    │   ├── ToolRegistry.java    注册表
    │   └── builtin/
    │       ├── CalculatorTool.java  计算器
    │       ├── ReadFileTool.java    读文件
    │       ├── WriteFileTool.java   写文件
    │       ├── ListDirTool.java     列目录
    │       ├── GrepTool.java        内容搜索
    │       └── GlobTool.java        文件名匹配
    ├── config/
    │   ├── DotEnv.java          .env 加载器
    │   └── ProviderFactory.java 根据配置创建 LlmProvider
    └── ui/
        ├── Ansi.java              ANSI 颜色
        ├── Box.java               圆角框渲染
        ├── MdAnsi.java            Markdown→ANSI
        ├── ConsoleListener.java   AgentListener 的终端渲染实现
        └── CommandDispatcher.java 斜杠命令分发
```
