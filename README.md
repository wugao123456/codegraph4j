# codegraph4j

CodeGraph 的 Java 版本 - 为 AI 编码助手提供语义代码知识图谱。

## 功能特性

- 支持 Java 和 JavaScript/TypeScript 代码解析
- SQLite 本地存储，无需外部数据库
- 命令行工具，易于集成到 CI/CD
- Maven 项目结构，JDK 8 兼容

## 快速开始

### 前置要求

- JDK 8 或更高版本
- Maven 3.6+

### 构建项目

```bash
# 克隆项目
git clone <repository-url>
cd codegraph4j

# 构建
mvn clean package
```

构建完成后，可执行 JAR 文件位于 `target/codegraph4j-1.0-SNAPSHOT.jar`

### 基本使用

#### 1. 初始化项目

```bash
java -jar target/codegraph4j-1.0-SNAPSHOT.jar init -p /path/to/your/project
```

这会在项目根目录创建 `.codegraph/codegraph.sqlite` 数据库文件。

#### 2. 索引代码

```bash
java -jar target/codegraph4j-1.0-SNAPSHOT.jar index -p /path/to/your/project
```

这会扫描项目中的代码文件，解析并存储代码符号信息。

#### 3. 查看状态

```bash
java -jar target/codegraph4j-1.0-SNAPSHOT.jar status -p /path/to/your/project
```

显示索引统计信息：
```
CodeGraph Status
================
Project: /path/to/your/project
Database: /path/to/your/project/.codegraph/codegraph.sqlite
Nodes: 100
Edges: 50
```

## 命令详解

### init

初始化 CodeGraph 数据库。

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

## 项目结构

```
codegraph4j/
├── src/main/java/com/codegraph/
│   ├── cli/           # 命令行接口
│   │   └── commands/  # init, index, status, serve 命令
│   ├── core/         # 核心数据模型
│   │   └── types/    # 枚举类型定义
│   ├── db/           # 数据库层
│   ├── parser/       # 代码解析器
│   └── utils/        # 工具类
├── src/main/resources/
│   ├── db/schema.sql # 数据库 schema
│   └── logback.xml   # 日志配置
└── src/test/         # 单元测试
```

## 数据模型

### Node (节点)

代码符号，如类、方法、字段等。

| 字段 | 类型 | 说明 |
|------|------|------|
| id | String | 唯一标识符 |
| kind | NodeKind | 符号类型（CLASS, METHOD, FIELD 等） |
| name | String | 符号名称 |
| qualifiedName | String | 完全限定名 |
| filePath | String | 所在文件路径 |
| language | Language | 编程语言 |
| startLine | int | 起始行号 |
| endLine | int | 结束行号 |

### Edge (边)

符号之间的关系。

| 字段 | 类型 | 说明 |
|------|------|------|
| source | String | 源节点 ID |
| target | String | 目标节点 ID |
| kind | EdgeKind | 关系类型（CALLS, DEFINES, IMPLEMENTS 等） |

## 支持的语言

| 语言 | 状态 | 说明 |
|------|------|------|
| Java | ✅ 完整支持 | 类、接口、方法、字段解析 |
| JavaScript | ✅ 基础支持 | 类、函数、方法解析 |

## 数据库

CodeGraph 使用 SQLite 作为本地存储，数据库文件位于项目根目录的 `.codegraph/codegraph.sqlite`。

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

## 许可证

MIT License
