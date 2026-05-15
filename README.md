# javelin

用 Java 17 模仿 Claude Code 的 Agent 架构，通过"边搭边学"理解其内部原理与机制。

> 不是追求生产可用，而是把 Agent 的核心机制拆开看——每写完一个模块，就理解一个概念。

## 第一期能力

ReAct 模式 + Tool Use 完整闭环，双 LLM 协议支持。

```
── 思考中… ──
╭─ 思考过程 ────
│ 用户问算术，需要调用 calculator
╰─
╭─ 调用工具 · calculator ────
│ {"expression": "11 + 33"}
╰─
╭─ 工具结果 · calculator ────
│ 44
╰─
── 整合工具结果… ──
╭─ 回答 ────
│ 11 + 33 = 44
╰─
```

## 架构

```
Main.java          REPL 入口（JLine + ANSI UI）
Agent.java         ReAct 主循环（调 LLM → 解析 → 执行工具 → 回填 → 再调）
LlmProvider        接口（隔离具体 SDK）
├─ AnthropicProvider      官方 Claude API
└─ OpenAICompatProvider   覆盖 DeepSeek / GLM / Kimi / 智谱 …
Tool / ToolRegistry  工具接口 + 注册表（6 个内置工具）
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
# 确保 UTF-8 终端（Windows）
chcp 65001

# 启动
mvn exec:java
```

### 3. 试试

```
you › 读一下 src/main/java/com/javelin/agent/Agent.java 的前 30 行
you › 搜索所有包含 "ReAct" 的文件
you › 项目里有多少个 Java 文件？
```

### 斜杠命令

| 命令 | 作用 |
|---|---|
| `/help` | 显示帮助 |
| `/tools` | 列出所有工具 |
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

## 技术栈

| 依赖 | 用途 |
|---|---|
| Java 17 | 语言 |
| Maven | 构建 |
| anthropic-java 2.32.0 | Claude API |
| openai-java 4.35.0 | DeepSeek/GLM/Kimi 等 |
| JLine 4.0.14 | 终端行编辑 |

## 文档

- [架构与实现教程](docs/javelin-tutorial.md)
- [源码合集](docs/javelin-source.md)

## 分期规划

- **第一期** ← 当前：ReAct + Tool Use 完整循环
- 后续：流式输出、上下文压缩、权限沙箱、子 Agent …
