# viewer.ts → Java 迁移方案

## 1. 概述

将 `/Users/wugao-pc/Desktop/Project/workDoc/codegraph/src/graph/viewer.ts` 的功能移植到当前 `codegraph4j` Java 项目中。

**viewer.ts 功能**：从 SQLite 索引库中读取节点和边数据，生成基于 vis-network 的独立 HTML 交互式代码关系图，可在浏览器中直接打开查看。

**移植目标**：保持相同的输出格式和行为，利用现有 Java 基础设施（`QueryBuilder`、`DatabaseConnection`、`vis-network.min.js`）。

---

## 2. 新增文件

### 2.1 `src/main/java/com/codegraph/graph/GraphViewer.java`

新的核心类，包含以下组件：

#### 2.1.1 数据模型（内部类或独立类）

| 类型 | 字段 | 说明 |
|------|------|------|
| `GraphViewOptions` | `symbol`, `file`, `includeImports`, `maxNodes` | 查询选项 |
| `ViewNode` | `id`, `label`, `title`, `group`, `color`, `value`, `file` | 可视化节点 |
| `ViewEdge` | `from`, `to`, `label`, `arrows`, `title` | 可视化边 |
| `GraphViewStats` | `totalNodes`, `totalEdges`, `kinds` | 统计信息 |
| `GraphViewData` | `nodes`, `edges`, `stats` | 完整数据包 |

#### 2.1.2 核心方法

| 方法 | 签名 | 说明 |
|------|------|------|
| `buildGraphViewData` | `(QueryBuilder, GraphViewOptions) → GraphViewData` | 从 SQLite 查询数据 |
| `renderViewerHtml` | `(GraphViewData, String titleSuffix) → String` | 生成完整 HTML |
| `readVisNetworkJs` | `() → String` | 从 classpath 加载 vis-network.min.js |
| `safeJson` | `(Object) → String` | JSON 序列化并转义 `<` |
| `getKindColor` | `(String) → String` | 节点类型 → 颜色映射 |

#### 2.1.3 SQL 逻辑映射

对标 viewer.ts 中的三种查询模式（通过 `QueryBuilder` 的原生 SQL 接口 `getDb().getConnection()` 执行）：

| 模式 | 触发条件 | 逻辑 |
|------|----------|------|
| **symbol** | `options.symbol != null` | 按 name/qualified_name 查找 seed 节点 → 1-hop 边扩展 → 获取所有相关节点 |
| **file** | `options.file != null` | LIKE 匹配 file_path → 1-hop 邻居扩展 → 合并去重 |
| **whole-graph** | 默认 | 排除 parameter/import 节点 → 按 degree 降序 → LIMIT maxNodes |

#### 2.1.4 空结果异常

```java
public class EmptyGraphViewException extends RuntimeException { }
```

### 2.2 `src/main/java/com/codegraph/cli/commands/ViewCommand.java`

新增 CLI 子命令，对标 TS 版 `codegraph view` 命令：

```
Usage: codegraph4j view [-p <project>] [--symbol <name>] [--file <path>]
       [--includeImports] [--maxNodes <n>] [-o <output.html>]
```

| 选项 | 默认值 | 说明 |
|------|--------|------|
| `-p, --project` | (required) | 项目路径 |
| `--symbol` | - | 聚焦某个符号及其 1-hop 邻域 |
| `--file` | - | 过滤文件名 |
| `--includeImports` | false | 是否包含 import/export/references 边 |
| `--maxNodes` | 250 | 最大节点数 |
| `-o, --output` | `<project>/.codegraph/codegraph-view.html` | 输出文件路径 |

### 2.3 修改 `CodeGraphCli.java`

在 `@Command(subcommands = {...})` 中添加 `ViewCommand.class`：

```java
subcommands = {
    InitCommand.class,
    IndexCommand.class,
    SyncCommand.class,
    ServeCommand.class,
    ViewCommand.class   // 新增
}
```

---

## 3. 无需修改的文件

| 文件 | 原因 |
|------|------|
| `pom.xml` | 无新依赖（已有 Jackson、SQLite、picocli） |
| `QueryBuilder.java` | 使用其 `getDb().getConnection()` 执行原生 SQL |
| `DatabaseConnection.java` | 无变更 |
| `schema.sql` | 无变更 |
| `vis-network.min.js` | 已存在于 `src/main/resources/graph/` |

---

## 4. 关键差异处理

| viewer.ts (TypeScript) | Java 实现 | 说明 |
|------------------------|-----------|------|
| `SqliteDatabase` (better-sqlite3) | `DatabaseConnection` + `java.sql.Connection` | 使用 `PreparedStatement` |
| `fs.readFileSync` | `ClassLoader.getResourceAsStream` | vis-network.min.js 从 classpath 加载 |
| `__dirname` 路径查找 | 直接使用 classpath 资源 | 更简洁可靠 |
| Node.js 运行时 | JVM | 无差异 |

---

## 5. 执行步骤

1. 创建 `GraphViewer.java`（核心逻辑）
2. 创建 `ViewCommand.java`（CLI 命令）
3. 修改 `CodeGraphCli.java`（注册命令）
4. 编译验证（`mvn compile`）
5. 功能测试

---

## 6. 待确认问题

在执行前，请确认以下几点：
