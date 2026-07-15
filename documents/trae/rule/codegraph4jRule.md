---
description: CodeGraph4J MCP 使用指南 — 使用 codegraph_explore 代替 grep、find 进行结构化代码查询
alwaysApply: true
---

## CodeGraph4J

本项目已配置 CodeGraph MCP 服务器，暴露单个工具：`codegraph_explore`。CodeGraph4J 是基于 tree-sitter 解析的代码知识图谱，包含每个符号、边和文件的结构信息。读取速度为毫秒级，能够返回 grep 无法获取的结构化信息。

### 使用 codegraph_explore 

对于任何**结构化问题**（如 "X 如何工作"、"X 如何调用 Y"、"谁调用了什么"、"X 在哪里定义"、概览某个代码区域），**优先使用 `codegraph_explore`，而不是 SearchCodebase、grep/find 或 Read**。

`codegraph_explore` 接受自然语言问题或一组符号/文件名，并返回：
- **带行号的逐字源码**，按文件分组（与 Read 返回的 `<n>\t<line>` 格式相同，可安全用于 Edit）
- **调用路径**，包括 grep 无法追踪的动态分派跳转（如接口多态调用、匿名内部类回调、Lambda 表达式）
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
- **不要先用 SearchCodebase 或 Read**：一次 `codegraph_explore` 调用即可在单次往返中返回相关符号的源码。仅在确认 codegraph4j 未覆盖的特定细节时，或对于 codegraph4j 未索引的内容（配置、文档），才使用 `Read` 或 `Grep`。
- **注意索引延迟**：当工具响应以 "⚠️ 以下引用的某些文件自上次索引同步后已被编辑..." 开头时，列出的文件正在等待重新索引——直接读取这些特定文件以获取准确内容。不在该警告中的所有文件都是最新的，可以信任 codegraph4j

### 中文查询自动翻译

当用户使用中文查询代码时，**利用大语言模型的翻译能力**，将中文查询翻译成英文关键词，**同时保留中文关键词**，组合成 `codegraph_explore` 的查询参数。

**核心逻辑**：
1. 检测用户查询是否为中文（包含中文字符）
2. 使用大语言模型将中文查询翻译成英文，提取核心业务实体和操作关键词
3. **中文关键词和英文关键词都保留**，组合成 `codegraph_explore` 的查询参数

**翻译规则**：
- 保留代码相关的专业术语（如类名、方法名），不翻译
- 将中文业务实体（如"用户"、"订单"）翻译为对应的英文术语
- 将中文操作（如"登录"、"创建"）翻译为对应的英文动词
- 去除无意义的语气词（如"帮我看看"、"我想了解"）
- **中文关键词和英文关键词同时保留**，用空格连接

**翻译示例**：

| 用户中文查询 | 翻译后的关键词（中+英） | `codegraph_explore` 参数 |
|-------------|----------------------|------------------------|
| "帮我看看用户登录逻辑" | 用户 user 登录 login | `codegraph_explore "用户 user 登录 login"` |
| "用户注册流程" | 用户 user 注册 register | `codegraph_explore "用户 user 注册 register"` |
| "订单创建和支付" | 订单 order 创建 create 支付 payment | `codegraph_explore "订单 order 创建 create 支付 payment"` |
| "权限管理服务" | 权限 permission 管理 manage 服务 service | `codegraph_explore "权限 permission 服务 service"` |
| "异常处理机制" | 异常 exception 处理 handle | `codegraph_explore "异常 exception 处理 handler"` |
| "UserService 的实现" | UserService 实现 implementation | `codegraph_explore "UserService 实现"` |
| "如何使用 Redis 缓存" | Redis 缓存 cache | `codegraph_explore "Redis 缓存 cache"` |

**操作步骤**：
1. 检测用户输入是否包含中文字符
2. 如果是中文查询，使用大语言模型进行翻译，提取核心关键词
3. **中文关键词和英文关键词同时保留**，用空格连接
4. 调用 `codegraph_explore`，传入包含中+英的查询参数
5. 如果翻译失败或查询不包含中文，直接使用原查询调用 `codegraph_explore`

**注意事项**：
- 翻译时要注意代码领域的专业术语准确性（如"控制器"→"controller"，"仓库"→"repository"）
- 保留原查询中的英文代码符号（如类名、方法名、变量名），不重新翻译
- **中文关键词保留用于匹配中文注释、文档和变量名**
- **英文关键词用于匹配代码符号（类名、方法名、变量名）**
- 翻译结果应简洁，只保留核心关键词，避免冗长的句子

### 如果 `.codegraph/` 不存在

MCP 服务器会返回 "not initialized"。询问用户："我注意到这个项目尚未初始化 codegraph4j `codegraph4j init -i` 来构建索引？"