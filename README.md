# CodeGraph4j

> Java 代码语义知识图谱，为 AI 编码助手提供代码理解能力

[![JDK](https://img.shields.io/badge/JDK-8%2B-blue.svg)](https://adoptium.net/)
[![Maven](https://img.shields.io/badge/Maven-3.6%2B-C71A36.svg)](https://maven.apache.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

---

## 为什么需要代码知识图谱

当 AI 编码助手需要理解代码时——回答问题、定位 Bug、实现需求——它只能像人类一样逐文件搜索：grep → glob → Read，一个文件接一个文件地拼凑调用链和依赖关系。在开始真正工作之前，大量的 tool call 和往返对话已经消耗了 token 和时间。代码库越大，java微服务化越多，调用链路越深，这种盲人摸象式的探索越慢。

**CodeGraph 将整个代码库变成一次查询即可获得的精准上下文。** 它预先构建了每行代码中每个符号、每条调用关系、每个依赖的语义知识图谱——AI 助手不用再逐文件爬取，只需一个问题就能得到：

- **相关代码的完整源码**（带行号）
- **符号间的调用路径**（包括 grep 无法追踪的动态分派和多态跳转）
- **修改的影响范围**（哪些模块会受影响）

**精准确认，而非逐文件搜索** —— 意味着更少的 tool call、更快的回答速度、更低的 token 消耗，在任意大小的代码库上都能稳定获益。

> **关于成本**：在任意代码库上，CodeGraph 的核心价值是精准度和速度——更少的 tool call、更快的回答。它同样降低 token 和费用成本，但这种节省**与代码规模正相关**：在小项目上节约量有限，但在大仓、多模块的 Java 企业项目中——乘以整个团队每日的 AI 使用量后——会累计成显著的成本节省。

> 详细基准测试数据（7 个开源项目、7 种语言，58% 更少 tool call、22% 更快），请参阅 [codegraph 官方 benchmark →](https://github.com/colbymchenry/codegraph#why-codegraph)

---

## 为什么选择 CodeGraph4j

CodeGraph4j 是 [codegraph](https://github.com/colbymchenry/codegraph) 的 Java 移植版本，核心语义图谱逻辑保持一致，同时在 中文和java生态上提供更深度的支持。

| | codegraph (TypeScript) | CodeGraph4j (Java) |
|---|---|---|
| **运行时** | TypeScript / Node.js | **JDK 8+** 安装简单  java -jar命令  一键运行）|
| **中文检测** | 通用语言解析 | **中文友好**：利用模型能力，中文转换英文 |
| **框架感知** | 通用语言解析 | **深度 Java 框架感知**：Spring Boot、Dubbo、OpenFeign |
| **代码解析** | Tree-sitter | **Tree-sitter（JNA 桥接）**，同等级别的 AST 解析 |

**适用场景**：

- 中文java项目、Spring 微服务体系
- 包含 Dubbo / OpenFeign RPC 调用的分布式系统
- 需要对 AI 助手提供精准 Java 代码上下文理解的团队

---

## 核心特性

- **Java 代码解析** — 基于 Tree-sitter 的高性能 AST 解析器，JNA 桥接原生 C 库
- **框架感知** — 识别 Spring Boot MVC、Dubbo RPC、OpenFeign 等框架的调用关系
- **知识图谱** — 构建代码符号间的语义关系（CALLS、INHERITS、IMPLEMENTS、REFERENCES 等 12 种）
- **MCP 工具集** — 8 个 MCP 工具供 AI 助手调用（`codegraph_explore` 为核心）
- **增量同步** — FileWatcher 自动监听 + Git hooks 备选，索引实时更新
- **SQLite 本地存储** — FTS5 全文搜索，零外部依赖，数据库路径可自定义
- **JDK 8 兼容** — 覆盖绝大多数企业级 Java 项目

---

## 快速开始

### 前置要求

- JDK 8 或更高版本
- Maven 3.6+

### 1. 构建项目

```bash
git clone https://github.com/codegraph/codegraph4j.git
cd codegraph4j
mvn clean package -DskipTests
```

构建完成后，Fat JAR 位于 `target/codegraph4j-1.0.0.jar`。

### 3. 初始化项目

```bash
java -jar target/codegraph4j-1.0.0.jar init -p /path/to/your/project
```

这会在项目根目录创建 `.codegraph/codegraph4j.db`。

### 4. 索引代码

```bash
java -jar target/codegraph4j-1.0.0.jar index -p /path/to/your/project
```

### 5. 启动 MCP 服务（由 AI 助手自动调用）

重启 AI 助手后，MCP 配置生效，助手将自动启动 CodeGraph 服务。

也可手动测试：

```bash
java -jar target/codegraph4j-1.0.0.jar serve --mcp -p /path/to/your/project
```

### 6. 启动 Web 可视化界面

启动时加上 `--web-port` 参数，即可在浏览器中查看代码知识图谱：

```bash
java -jar target/codegraph4j-1.0.0.jar serve --mcp --web-port 9999 -p /path/to/your/project
```

浏览器打开 `http://localhost:9999` 即可查看：

![CodeGraph Viewer](docs/viewer-screenshot.png)

**Web Viewer 功能**：

- **符号查询**：输入符号名（如 `GraphQueryManager`）进行聚焦查询
- **度数控制**：支持 1-5 度扩展，默认 1 度
- **节点详情**：点击节点查看右侧面板详情（ID、类型、文件路径等）
- **右键展开**：右键点击节点按当前度数扩展子图
- **文件查询**：支持按文件名过滤
## 命令详解

### 集成trae — 安装 MCP 配置

为 AI 助手自动配置 CodeGraph MCP 连接。

在 Trae 的 `.trae/mcp.json` 中添加以下配置：

```json
{
  "mcpServers": {
    "codegraph4j": {
      "command": "java",
      "args": [
        "-jar",
        "/install/codegraph4j-1.0-release.jar",
        "serve",
        "--mcp",
        "-p",
        "/Project/codegraph4j"
      ]
    }
  }
}
```
### init — 初始化建立codegraph4j库
```bash
java -jar codegraph4j.jar init -p <project-path> [options]
```

| 选项 | 说明 |
|------|------|
| `-p, --project` | 项目根目录（默认当前目录） |
| `-f, --force` | 覆盖现有数据库 |

### index — 全量索引

扫描项目中所有源文件，解析并构建知识图谱。

```bash
java -jar codegraph4j.jar index -p <project-path> [options]
```

| 选项 | 说明 |
|------|------|
| `-p, --project` | 项目根目录（默认当前目录） |
| `--force` | 强制重新索引所有文件（忽略 hash 缓存） |
| `--watch` | 索引后启动文件监听（待实现） |

### sync — 增量同步

对账文件系统与数据库，仅处理变更的文件（新增、修改、删除）。适合作为 Git hook 或 CI 步骤使用。

```bash
java -jar codegraph4j.jar sync -p <project-path> [options]
```

| 选项 | 说明 |
|------|------|
| `-p, --project` | 项目根目录（默认当前目录） |
| `-q, --quiet` | 静默模式（用于 Git hook 触发时不输出） |

示例输出：

```
========== 同步完成 ==========
✓ Sync completed in 1234ms
  检查文件数: 156
  新增文件: 3
  修改文件: 2
  删除文件: 1
  更新节点: 45
==============================
```

**Git hooks 自动同步**：在 `.git/hooks/` 中安装 `post-commit`、`post-merge`、`post-checkout` 钩子：

```bash
# 安装 git hooks
java -jar codegraph4j.jar git-hooks install -p /path/to/project

# 卸载 git hooks
java -jar codegraph4j.jar git-hooks uninstall -p /path/to/project
```

### status — 查看状态

```bash
java -jar codegraph4j.jar status -p <project-path>
```

| 选项 | 说明 |
|------|------|
| `-p, --project` | 项目根目录（默认当前目录） |

示例输出：

```
CodeGraph Status
================
Project: /path/to/your/project
Database: /path/to/your/project/.codegraph/codegraph4j.db
Nodes: 1234
Edges: 5678
```

### serve — 启动 MCP 服务

```bash
java -jar codegraph4j.jar serve --mcp -p <project-path>
```

| 选项 | 说明 |
|------|------|
| `-p, --project` | 项目根目录（默认当前目录） |
| `--mcp` | 以 MCP 模式运行（stdio JSON-RPC 2.0 传输） |

### git-hooks — Git 钩子管理

安装或卸载 Git hooks，在 commit/merge/checkout 时自动触发增量同步。

```bash
# 安装 git hooks
java -jar codegraph4j.jar git-hooks install -p /path/to/project

# 卸载 git hooks
java -jar codegraph4j.jar git-hooks uninstall -p /path/to/project
```

| 选项 | 说明 |
|------|------|
| `-p, --project` | 项目根目录（默认当前目录） |

安装的 hooks：`post-commit`、`post-merge`、`post-checkout`。每次触发时自动运行 `sync -q`（静默模式）。


---

## MCP 工具

CodeGraph4j 通过 MCP 协议对外暴露 8 个工具，供 AI 编码助手调用：

| 工具名 | 功能 |
|--------|------|
| `codegraph_explore` | **核心工具** — 自然语言查询，混合搜索 + 图扩展 + RWR 相关性排序 |
| `codegraph_search` | 按名称搜索代码符号 |
| `codegraph_callers` | 查找指定符号的调用者 |
| `codegraph_callees` | 查找指定符号的被调用者 |
| `codegraph_impact` | 影响范围分析 |
| `codegraph_node` | 查看单个符号的详细信息（位置、签名、引用） |
| `codegraph_status` | 检查索引健康状态 |
| `codegraph_files` | 列出已索引的文件 |

---

## 框架感知

CodeGraph4j 内置框架解析系统，通过 `FrameworkResolver` 接口可扩展。

### Spring / Spring Boot

- 识别 `@RestController`、`@Service`、`@Repository` 等注解
- 构建 `Controller → Service → Repository` 调用链
- 提取 `@RequestMapping` 路由为 ROUTE 节点
- 识别 `@Value("${...}")` 配置键绑定

### Dubbo

- 检测 `@DubboService` 注解标记的 RPC 提供者
- 提取暴露的接口方法为 ROUTE 节点
- 解析 `@DubboReference` 标记的消费者引用
- 支持基于 XML 配置和注解的 Dubbo 项目

### OpenFeign

- 检测 `@FeignClient` 注解的远程 HTTP 客户端
- 提取远程端点的路径和方法作为 ROUTE 节点
- 解析 `@Autowired` 注入的 Feign 客户端引用
- 支持跨文件的远程服务依赖关系追踪

### 扩展框架

实现 `FrameworkResolver` 接口并注册到 `FrameworkRegistry` 即可：

```java
public class MyBatisResolver implements FrameworkResolver {
    @Override public String getName() { return "MyBatis"; }
    @Override public List<Language> getLanguages() { return List.of(Language.JAVA); }
    @Override public boolean detect(ResolutionContext ctx) { /* 检测 pom.xml */ }
    @Override public FrameworkExtractionResult extract(String path, String src, ResolutionContext ctx) { /* 提取 Mapper */ }
    // ...
}
```

---

## 数据模型

### Node（节点）

代码符号，如类、方法、字段等。

| 字段 | 类型 | 说明 |
|------|------|------|
| id | String | 唯一标识符（SHA-256） |
| kind | NodeKind | 符号类型 |
| name | String | 符号名称 |
| qualifiedName | String | 完全限定名 |
| filePath | String | 所在文件路径 |
| startLine / endLine | int | 行号范围 |
| docstring | String | 文档注释 |

**NodeKind 枚举**（22 种）：`FILE`、`MODULE`、`CLASS`、`STRUCT`、`INTERFACE`、`METHOD`、`FUNCTION`、`FIELD`、`PROPERTY`、`ENUM`、`PARAMETER`、`VARIABLE`、`CONSTANT`、`IMPORT`、`EXPORT`、`ROUTE`、`COMPONENT` 等。

### Edge（边）

符号之间的关系。

| 字段 | 类型 | 说明 |
|------|------|------|
| source | String | 源节点 ID |
| target | String | 目标节点 ID |
| kind | EdgeKind | 关系类型 |
| provenance | String | 边来源（`parsed` / `heuristic` / `framework`） |

**EdgeKind 枚举**（12 种）：`CONTAINS`、`CALLS`、`IMPORTS`、`EXPORTS`、`IMPLEMENTS`、`EXTENDS`、`OVERRIDES`、`REFERENCES`、`INSTANTIATES`、`RETURNS`、`TYPE_OF`、`DECORATES`。

### FileRecord

已索引的文件追踪记录（路径、hash、size、mtime）。

---

## 项目结构

```
codegraph4j/
├── src/main/java/com/codegraph/
│   ├── cli/                    # 命令行入口
│   │   └── commands/           # 7 个子命令：init, index, status, sync,
│   │                           #            serve, install, uninstall
│   ├── config/                  # CodeGraphConfig 配置
│   ├── core/                   # 核心数据模型（Node, Edge, FileRecord）
│   │   └── types/              # 枚举：NodeKind, EdgeKind, Language, Visibility
│   ├── db/                     # 数据库层（SQLite + FTS5）
│   ├── extraction/             # 代码解析与提取
│   │   ├── bridge/             # JNA 桥接 Tree-sitter C 库
│   │   ├── tree_sitter/        # AST 遍历提取器
│   │   └── languages/          # 语言特定提取器（Java 含 Lombok 支持）
│   ├── resolution/             # 符号解析（框架解析器 + Import解析 + 名称匹配）
│   │   ├── frameworks/         # 框架解析器接口与注册中心
│   │   └── supportedframeworks/ # Spring、Dubbo、OpenFeign 解析器
│   ├── graph/                  # 图遍历引擎（BFS、调用链、影响范围）
│   ├── context/                # AI 上下文构建（RWR 相关性、自适应预算）
│   ├── mcp/                    # MCP 协议实现（JSON-RPC 2.0 + stdio）
│   ├── sync/                   # 增量同步引擎（FileWatcher + Git hooks）
│   ├── installer/              # AI 助手配置管理
│   └── utils/                  # 工具类（Mutex、FileLock、LRU Cache）
├── src/main/resources/
│   ├── db/schema.sql           # 数据库 DDL（nodes, edges, files, unresolved_refs）
│   └── logback.xml             # 日志配置
└── src/test/                   # 单元测试
```

---

## 数据库

使用 SQLite 本地存储，数据库文件默认位于 `<project>/.codegraph/codegraph4j.db`。路径通过 `-p` 参数可自定义，天然支持多项目隔离索引。

### 主要表

| 表 | 说明 |
|------|------|
| `nodes` | 代码符号节点（含 FTS5 全文搜索） |
| `edges` | 符号关系（外键约束，级联删除） |
| `files` | 文件追踪（路径、hash、mtime） |
| `unresolved_refs` | 未解析的跨文件引用 |
| `project_metadata` | 项目元数据（框架检测结果等） |
| `schema_versions` | Schema 版本追踪 |

---

## 环境变量

CodeGraph4j 支持通过环境变量进行运行时配置：

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| `CODEGRAPH_MCP_TOOLS` | 启用的工具列表（逗号分隔），如 `explore,search,callers` | `explore,search,callers,callees,impact,node,status,files` |
| `CODEGRAPH_EXPLORE_LOG_MAX_FILES` | explore 日志保留的最大文件数 | `10` |

---

## 微服务多项目支持

在多项目微服务架构中，每个根项目可独立初始化自己的 `.codegraph/codegraph4j.db`：

```bash
# 为根项目独立索引
java -jar codegraph4j.jar init -p /workspace/
配置数据库路径为 `/workspace/.codegraph/codegraph4j.db`

```

框架解析器（Dubbo、OpenFeign）自动追踪跨服务的 RPC 调用关系，在各自的数据库中建立引用节点。

---







### 技术栈

| 依赖 | 用途 |
|------|------|
| picocli 4.7.5 | CLI 框架 |
| sqlite-jdbc 3.45 | SQLite 驱动 |
| jackson-databind 2.16 | JSON 序列化 |
| jgit 6.8 | Git 集成 |
| jna 5.16 | Tree-sitter C 库桥接 |
| guava 33.0 | 通用工具库 |
| slf4j + logback | 日志 |

---

## 许可证

MIT License
