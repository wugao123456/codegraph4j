# README.md 重构计划

## 背景

codegraph4j README 最初基于早期版本撰写，目前已过时（缺 install/uninstall 命令、缺 sync 命令、缺框架感知、缺 Trae 支持等）。需要在保持技术文档风格的前提下，全面重写，反映当前功能现状。

## 用户偏好（已确认）

* **风格**：技术文档型（保持当前风格，不引入营销元素）

* **未来规划**：更多框架支持 + 多项目联合图谱

* **需要版本对比**：对比 codegraph (TS)，突出 Java 版独特优势

* **额外强调**：对中文工具（Trae 等）的友好支持

***

## 新 README 结构

### 1. 标题区

* 项目名、一句话描述

* JDK 8 + Maven badge

* 许可 badge

### 2. 为什么选择 CodeGraph4j（codegraph (TS) 对比）

| codegraph (TS)       | CodeGraph4j (Java)                   |
| -------------------- | ------------------------------------ |
| TypeScript/Node.js 栈 | JDK 8，Java 生态原生                      |
| 通用语言解析               | 深度 Java 框架感知（Dubbo、Spring、OpenFeign） |
| Agent 覆盖广（8 个）       | 原生支持 Claude/Cursor/Trae，对中国 IDE 更友好  |
| npm 安装               | Maven/JAR 分发                         |

### 3. 核心特性

* Java 代码解析（Tree-sitter）

* 框架感知（Spring/Spring Boot、Dubbo、OpenFeign）

* 知识图谱（CALLS/INHERITS/IMPLEMENTS 等）

* MCP 工具（codegraph\_explore 等 8 个工具）

* SQLite 本地存储，零外部依赖

* JDK 8 兼容

* 自动增量同步（FileWatcher + Git hooks）

* 一键安装到 Claude/Cursor/Trae

### 4. 快速开始

* 前置要求（JDK 8+、Maven 3.6+）

* 构建：`mvn clean package`

* 三步上手：

  1. `install` — 安装 MCP 配置到 AI 助手
  2. `init` — 初始化项目数据库
  3. `index` — 索引代码

### 5. 命令详解（完整 8 个命令）

* `install` — 安装 MCP 配置（Claude/Cursor/Trae，--global/--print-config）

* `uninstall` — 卸载配置

* `init` — 初始化数据库

* `index` — 全量索引

* `sync` — 增量同步（支持 --watch）

* `status` — 查看状态

* `serve` — 启动 MCP 服务

* `traverse` — 图遍历
  每个命令含表格列参数 + 示例。

### 6. MCP 工具

* `codegraph_explore` — 主要工具

* `codegraph_search` — 符号搜索

* `codegraph_callers` / `codegraph_callees` — 调用链

* `codegraph_impact` — 影响范围

* `codegraph_node` — 符号详情

* `codegraph_status` — 索引状态

* `codegraph_files` — 文件列表

### 7. 框架感知详解

* Spring/Spring Boot：Controller→Service→Repository 链路

* Dubbo：RPC 接口调用关系

* OpenFeign：跨服务 HTTP 调用追踪

* 扩展机制（FrameworkResolver 接口）

### 8. 数据模型

* Node 表、NodeKind 枚举

* Edge 表、EdgeKind 枚举

* FileRecord

### 9. 项目结构

* 更新后的目录树（含 install/、sync/、resolution/ 等）

### 10. 数据库

* SQLite，路径可自定义

* 主要表（nodes、edges、files、unresolved\_refs）

* FTS5 全文搜索

### 11. 微服务多项目支持

* 自定义数据库路径（`-p` 参数）

* 跨项目知识图谱构建思路

* 当前状态 vs 未来规划

### 12. 未来规划

* **更多框架支持**：MyBatis、gRPC、Motan、Quarkus

* **多项目联合图谱**：跨仓库调用链路、微服务拓扑图

* **更多 AI 工具**：Gemini、Codex 的 installer 支持

* **性能优化**：大项目（10 万+ 文件）增量索引

### 13. 开发

* 运行测试

* 打包

* 查看帮助

### 14. 许可证

* MIT License

***

## 改动清单

| 文件          | 操作 | 说明       |
| ----------- | -- | -------- |
| `README.md` | 重写 | 全量替换为新结构 |

***

## 验证方式

* 用 Markdown 预览确认格式正确

* 确认所有命令与 `CodeGraphCli` 实际子命令一致

* 确认所有命令行示例可在本地执行

