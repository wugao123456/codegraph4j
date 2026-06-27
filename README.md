# CodeGraph4j

> Java 代码语义知识图谱，为 AI 编码助手提供代码理解能力

## 功能特性

- **Java 代码解析**：基于 Tree-sitter 的高性能 Java 解析器
- **框架感知**：支持 Spring、Spring Boot、Spring Cloud 等主流框架
- **知识图谱**：构建代码符号之间的语义关系（CALLS、INHERITS、IMPLEMENTS 等）
- **MCP 工具**：提供 MCP (Model Context Protocol) 接口，集成 AI 编码助手
- **SQLite 存储**：本地化部署，无需外部数据库
- **JDK 8 兼容**：广泛的项目兼容性

## 快速开始

### 前置要求

- JDK 8 或更高版本
- Maven 3.6+
- Tree-sitter Java grammar（构建时自动下载）

### 构建项目

```bash
# 克隆项目
git clone <repository-url>
cd codegraph4j

# 构建
mvn clean package
```

构建完成后，可执行 JAR 文件位于 `target/codegraph4j.jar`

### 基本使用

#### 1. 初始化项目

```bash
java -jar target/codegraph4j.jar init -p /path/to/your/project
```

这会在项目根目录创建 `.codegraph/codegraph4j.db` 数据库文件。

#### 2. 索引代码

```bash
java -jar target/codegraph4j.jar index -p /path/to/your/project
```

这会扫描项目中的代码文件，解析并存储代码符号信息。

#### 3. 启动 MCP 服务

```bash
java -jar target/codegraph4j.jar serve -p /path/to/your/project
```

启动 MCP 服务，供 AI 编码助手调用。

#### 4. 查看状态

```bash
java -jar target/codegraph4j.jar status -p /path/to/your/project
```

显示索引统计信息。

## 命令详解

### init

初始化 CodeGraph4j 数据库。

```bash
java -jar codegraph4j.jar init -p <project-path>
```

| 选项 | 说明 |
|------|------|
| `-p, --project` | 项目根目录（默认当前目录） |
| `-f, --force` | 强制重新初始化（覆盖现有数据库） |

### index

索引项目代码。

```bash
java -jar codegraph4j.jar index -p <project-path> [options]
```

| 选项 | 说明 |
|------|------|
| `-p, --project` | 项目根目录（默认当前目录） |
| `--force` | 强制重新索引所有文件 |
| `--watch` | 监听文件变化（开发中） |

### status

显示索引状态和统计信息。

```bash
java -jar codegraph4j.jar status -p <project-path>
```

示例输出：

```
CodeGraph4j Status
==================
Project: /path/to/your/project
Database: /path/to/your/project/.codegraph/codegraph4j.db
Nodes: 1234
Edges: 5678
Files: 89
```

### serve

启动 MCP 服务，供 AI 编码助手调用。

```bash
java -jar codegraph4j.jar serve -p <project-path> [options]
```

| 选项 | 说明 |
|------|------|
| `-p, --project` | 项目根目录（默认当前目录） |
| `--port` | 服务端口（默认 8765） |

### traverse

遍历代码图，查看调用关系。

```bash
java -jar codegraph4j.jar traverse <node-id> [options]
```

| 选项 | 说明 |
|------|------|
| `--callers` | 向上遍历（查找调用者） |
| `--callees` | 向下遍历（查找被调用者） |
| `--depth` | 遍历深度（默认 3） |

## MCP 工具

CodeGraph4j 提供以下 MCP 工具，供 AI 编码助手调用：

### codegraph_explore

探索代码符号和文件，返回相关源码和调用关系。

```json
{
  "name": "codegraph_explore",
  "arguments": {
    "query": "用户登录流程",
    "maxFiles": 5
  }
}
```

返回格式示例：

```markdown
**Exploration: 用户登录流程**

Found 45 symbols across 8 files.

---

**Relationships**

**CALLS:**
- LoginController.login → UserService.authenticate
- UserService.authenticate → UserRepository.findByEmail

---

**Source Code**

`src/main/java/com/example/controller/LoginController.java`
```java
   15| @RestController
   16| @RequestMapping("/api/auth")
   17| public class LoginController {
   18|     @PostMapping("/login")
   19|     public ResponseEntity<?> login(@RequestBody LoginRequest request) {
   20|         String token = userService.authenticate(request.getEmail(), request.getPassword());
   21|         return ResponseEntity.ok(new LoginResponse(token));
   22|     }
   23| }
```

*Explore output budget: 3200/8000 chars, 2/5 files.*
```

### codegraph_search

搜索代码符号。

```json
{
  "name": "codegraph_search",
  "arguments": {
    "query": "UserService",
    "kind": "CLASS"
  }
}
```

### codegraph_node

查看单个符号的详细信息。

```json
{
  "name": "codegraph_node",
  "arguments": {
    "query": "UserService"
  }
}
```

## 数据模型

### Node (节点)

代码符号，如类、方法、字段等。

| 字段 | 类型 | 说明 |
|------|------|------|
| id | String | 唯一标识符（格式：`kind:qualifiedName`） |
| kind | NodeKind | 符号类型 |
| name | String | 符号名称 |
| qualifiedName | String | 完全限定名 |
| filePath | String | 所在文件路径 |
| startLine | int | 起始行号 |
| endLine | int | 结束行号 |
| docstring | String | 文档注释 |

### NodeKind 枚举

| 类型 | 说明 |
|------|------|
| CLASS | 类 |
| INTERFACE | 接口 |
| ENUM | 枚举 |
| METHOD | 方法 |
| CONSTRUCTOR | 构造器 |
| FIELD | 字段 |
| PARAMETER | 参数 |
| LOCAL_VARIABLE | 局部变量 |
| IMPORT | 导入语句 |
| PACKAGE | 包声明 |

### Edge (边)

符号之间的关系。

| 字段 | 类型 | 说明 |
|------|------|------|
| source | String | 源节点 ID |
| target | String | 目标节点 ID |
| kind | EdgeKind | 关系类型 |
| provenance | String | 边来源（`parsed`/`heuristic`） |

### EdgeKind 枚举

| 类型 | 说明 |
|------|------|
| CALLS | 方法调用 |
| DEFINES | 定义关系 |
| INHERITS | 继承关系 |
| IMPLEMENTS | 接口实现 |
| CONTAINS | 包含关系 |
| REFERENCES | 引用关系 |
| ANNOTATES | 注解关系 |
| OVERRIDES | 方法覆盖 |

## 项目结构

```
codegraph4j/
├── src/main/java/com/codegraph/
│   ├── cli/                  # 命令行入口
│   │   └── commands/         # init, index, status, serve, traverse
│   ├── core/                 # 核心类型定义
│   │   └── types/           # NodeKind, EdgeKind 枚举
│   ├── db/                   # 数据库层
│   │   ├── DatabaseConnection.java
│   │   ├── QueryBuilder.java
│   │   └── SchemaManager.java
│   ├── graph/                # 图遍历
│   │   └── GraphTraverser.java
│   ├── mcp/                  # MCP 协议实现
│   │   └── MCPToolHandler.java
│   ├── parser/               # 代码解析器
│   │   ├── CodeParser.java
│   │   ├── ParseResult.java
│   │   └── JavaParser.java
│   ├── parser/treesitter/   # Tree-sitter 解析器
│   │   ├── TreeSitterCodeParser.java
│   │   ├── TreeSitterExtractor.java
│   │   └── TreeSitterNative.java
│   └── context/              # 上下文构建
│       ├── ContextBuilder.java
│       └── GraphRelevanceComputer.java
├── src/main/resources/
│   ├── db/schema.sql         # 数据库 schema
│   ├── logback.xml           # 日志配置
│   └── treesitter/           # Tree-sitter grammar
└── src/test/                # 单元测试
```

## 数据库

CodeGraph4j 使用 SQLite 作为本地存储，数据库文件位于项目根目录的 `.codegraph/codegraph4j.db`。

### 主要表

- `nodes` - 代码符号节点
- `edges` - 符号关系边
- `files` - 文件记录
- `schema_versions` - Schema 版本追踪

## 开发

### 运行测试

```bash
mvn test
```

### 仅运行解析器测试

```bash
mvn test -Dtest=JavaParserTest
```

### 查看帮助

```bash
java -jar target/codegraph4j.jar --help
```

输出：

```
CodeGraph4j - Java 代码语义知识图谱，为 AI 编码助手提供代码理解能力

Usage: codegraph4j [COMMAND]
Commands:
  init    Initialize CodeGraph4j database
  index   Index project code
  status  Show index status
  serve   Start MCP server
  traverse  Traverse code graph

Run 'codegraph4j [COMMAND] --help' for more information on a command.
```

## 许可证

MIT License
