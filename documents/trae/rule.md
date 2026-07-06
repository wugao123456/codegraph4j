---
description: CodeGraph4J MCP 使用指南 — 使用 codegraph_explore 代替 SearchCodebase 进行结构化代码查询
alwaysApply: true
---

## CodeGraph4J

本项目已配置 CodeGraph MCP 服务器，暴露单个工具：`codegraph_explore`。CodeGraph4J 是基于 tree-sitter 解析的代码知识图谱，包含每个符号、边和文件的结构信息。读取速度为毫秒级，能够返回 grep 无法获取的结构化信息。

### 使用 codegraph_explore 代替 SearchCodebase

对于任何**结构化问题**（如 "X 如何工作"、"X 如何调用 Y"、"谁调用了什么"、"X 在哪里定义"、概览某个代码区域），**优先使用 `codegraph_explore`，而不是 SearchCodebase、grep/find 或 Read**。

`codegraph_explore` 接受自然语言问题或一组符号/文件名，并返回：
- **带行号的逐字源码**，按文件分组（与 Read 返回的 `<n>\t<line>` 格式相同，可安全用于 Edit）
- **调用路径**，包括 grep 无法追踪的动态分派跳转（如回调、React 重新渲染、JSX 子组件）
- **影响范围摘要**，展示哪些代码依赖于查询结果

在查询中指定文件名或符号名即可读取其当前源码。

### 与 SearchCodebase 的对比

| 场景 | 推荐工具 | 原因 |
|-----|---------|------|
| **理解代码结构**（调用关系、继承链、数据流） | `codegraph_explore` | 基于 AST 解析，能理解代码语义 |
| **查找符号定义位置** | `codegraph_explore` | 直接返回定义及其上下文 |
| **追踪调用路径** | `codegraph_explore` | 支持动态分派、回调等 grep 无法追踪的关系 |
| **概览代码区域** | `codegraph_explore` | 一次调用返回相关源码 + 调用图 |
| **纯文本内容搜索**（配置、文档） | `SearchCodebase` / `Grep` | 适用于非代码文件或文本匹配 |
| **确认 。CodeGraph4J 未覆盖的细节** | `Read` / `Grep` | 仅在 codegraph4j 无法提供时使用 |

### 使用规则

- **直接回答，不要委派探索任务**：一次 `codegraph_explore` codegraph4j 本身就是预构建的索引，因此运行 grep + read 循环或委派给单独的文件读取子任务会重复 codegraph4j 已完成的工作，效率更低。
- **信任 codegraph4j 的结果**：它们来自完整的 AST 解析。不要用 grep 重新验证，这会更慢、更不准确且浪费上下文。
- **不要先用 SearchCodebase 或 Read**：一次 `codegraph_explore` 调用即可在单次往返中返回相关符号的源码。仅在确认 codegraph4j 未覆盖的特定细节时，或对于 codegraph4j 未索引的内容（配置、文档），才使用 `SearchCodebase`、`Read` 或 `Grep`。
- **注意索引延迟**：当工具响应以 "⚠️ 以下引用的某些文件自上次索引同步后已被编辑..." 开头时，列出的文件正在等待重新索引——直接读取这些特定文件以获取准确内容。不在该警告中的所有文件都是最新的，可以信任 codegraph4j

### 如果 `.codegraph/` 不存在

MCP 服务器会返回 "not initialized"。询问用户："我注意到这个项目尚未初始化 codegraph4j `codegraph4j init -i` 来构建索引？"