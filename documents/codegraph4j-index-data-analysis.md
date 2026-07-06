# CodeGraph4j Index 数据生成过程详解

> 本文档详细分析 codegraph4j 解析一个 Java 文件时，生成了多少条 Node 和 Edge，每条数据的每个字段填什么值，以及这种数据结构对后续搜索（codegraph\_explore）有什么好处，最后列出当前设计的待解决问题。

***

## 1. 示例：解析一个 Java 文件

假设文件 `com/example/service/UserService.java`：

```java
package com.example.service;

import java.util.List;

/**
 * 用户服务类
 */
public class UserService extends BaseService implements IUserService {

    private String userName;

    /**
     * 登录方法
     */
    public String login(String name) {
        this.userName = name;
        return greet(name);
    }

    private String greet(String name) {
        return "Hello, " + name;
    }

    public void logout() {
        System.out.println("logout");
    }
}
```

### 1.1 生成的 Node 列表

| # | NodeKind | name                  | qualified\_name                            | 关键字段                                                                    |
| - | -------- | --------------------- | ------------------------------------------ | ----------------------------------------------------------------------- |
| 1 | MODULE   | `com.example.service` | `com.example.service`                      | 包声明                                                                     |
| 2 | IMPORT   | `java.util.List`      | `java.util.List`                           | import 语句                                                               |
| 3 | CLASS    | `UserService`         | `com.example.service.UserService`          | visibility=PUBLIC, exported=true, docstring="用户服务类"                     |
| 4 | FIELD    | `userName`            | `com.example.service.UserService.userName` | visibility=PRIVATE, exported=false                                      |
| 5 | METHOD   | `login`               | `com.example.service.UserService.login`    | visibility=PUBLIC, signature=`login(String name)`, returnType=`String`  |
| 6 | METHOD   | `greet`               | `com.example.service.UserService.greet`    | visibility=PRIVATE, signature=`greet(String name)`, returnType=`String` |
| 7 | METHOD   | `logout`              | `com.example.service.UserService.logout`   | visibility=PUBLIC, signature=`logout()`, returnType=`void`              |

**共 7 个 Node。**

### 1.2 生成的 Edge 列表

| # | EdgeKind   | source           | target                    | 说明              |
| - | ---------- | ---------------- | ------------------------- | --------------- |
| 1 | CONTAINS   | `UserService.id` | `userName.id`             | 类包含字段           |
| 2 | CONTAINS   | `UserService.id` | `login.id`                | 类包含方法           |
| 3 | CONTAINS   | `UserService.id` | `greet.id`                | 类包含方法           |
| 4 | CONTAINS   | `UserService.id` | `logout.id`               | 类包含方法           |
| 5 | EXTENDS    | `UserService.id` | `BaseService.id(pseudo)`  | 继承关系            |
| 6 | IMPLEMENTS | `UserService.id` | `IUserService.id(pseudo)` | 接口实现            |
| 7 | CALLS      | `login.id`       | `greet.id`                | login 调用了 greet |

**共 7 条 Edge。**

### 1.3 总结公式

对于 Java 文件，节点和边的数量大致遵循：

* **Node 数** = 1(MODULE包) + N(IMPORT导入) + 1(CLASS/INTERFACE/ENUM) + M(METHOD/FIELD/ENUM\_MEMBER)

* **Edge 数** = (M+N+1)(CONTAINS) + E(EXTENDS) + I(IMPLEMENTS) + C(CALLS跨方法调用)

***

## 2. Node 数据模型详解

每个 Node 的 21 个字段（对应 nodes 表）在 Java 提取中的填充逻辑：

### 2.1 标识字段

| 字段               | 类型      | 填充逻辑                                                      | 示例                                                                                 |
| ---------------- | ------- | --------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| `id`             | TEXT PK | `SHA-256(filePath:kind:qualifiedName:line)` 前加 `kind:` 前缀 | `CLASS:a1b2c3...`                                                                  |
| `kind`           | TEXT    | 枚举 `NodeKind`，从 AST 节点类型映射                                | `CLASS`, `METHOD`, `FIELD`, `MODULE`, `IMPORT`, `ENUM`, `ENUM_MEMBER`, `INTERFACE` |
| `name`           | TEXT    | AST 中 `name` 字段节点的文本                                      | `UserService`, `login`                                                             |
| `qualified_name` | TEXT    | `包名.作用域链.name`（scopeStack 从栈底到栈顶拼接）                       | `com.example.service.UserService.login`                                            |

### 2.2 位置字段

| 字段             | 类型      | 填充逻辑                                                               |
| -------------- | ------- | ------------------------------------------------------------------ |
| `file_path`    | TEXT    | 文件绝对路径字符串                                                          |
| `language`     | TEXT    | 固定 `JAVA`                                                          |
| `start_line`   | INTEGER | `ts_node_start_point(node).row + 1`（tree-sitter 0-based → 1-based） |
| `end_line`     | INTEGER | `ts_node_end_point(node).row + 1`                                  |
| `start_column` | INTEGER | `ts_node_start_point(node).column + 1`                             |
| `end_column`   | INTEGER | `ts_node_end_point(node).column + 1`                               |

### 2.3 语义字段

| 字段            | 类型      | 填充逻辑                                                                                   |
| ------------- | ------- | -------------------------------------------------------------------------------------- |
| `visibility`  | TEXT    | Java: 从 `modifiers` 子节点提取 → `PUBLIC`/`PRIVATE`/`PROTECTED`/`INTERNAL`(package-private) |
| `is_exported` | INTEGER | PUBLIC 或 PROTECTED 的成员为 true；CLASS/INTERFACE/ENUM 总是 true                              |
| `is_static`   | INTEGER | modifiers 中包含 `static` 为 1                                                             |
| `is_abstract` | INTEGER | modifiers 中包含 `abstract` 为 1                                                           |
| `is_async`    | INTEGER | Java 无此概念，始终为 0                                                                        |

### 2.4 方法专用字段

| 字段            | 类型   | 填充逻辑                                                  | 示例                                          |
| ------------- | ---- | ----------------------------------------------------- | ------------------------------------------- |
| `signature`   | TEXT | `nameField文本 + paramsField文本`，constructor 名为 `<init>` | `login(String name)`, `<init>(String name)` |
| `return_type` | TEXT | 从 `type` field 子节点提取文本，constructor 为 null             | `String`, `void`                            |

### 2.5 文档与元数据字段

| 字段                | 类型      | 填充逻辑                                                                               |
| ----------------- | ------- | ---------------------------------------------------------------------------------- |
| `docstring`       | TEXT    | `getPrecedingDocstring()` 向前收集连续的 `block_comment` / `line_comment`，清理 `/**` `*` 标记 |
| `decorators`      | TEXT    | JSON 数组（Java 中暂未填充注解信息）                                                            |
| `type_parameters` | TEXT    | JSON 数组（泛型参数暂未提取）                                                                  |
| `updated_at`      | INTEGER | `System.currentTimeMillis()`                                                       |

### 2.6 节点 ID 生成规则

[TreeSitterHelpers.java](file:///Users/wugao-pc/Desktop/Project/codegraph4j/src/main/java/com/codegraph/extraction/tree_sitter/TreeSitterHelpers.java#L36-L50)

```
nodeId = kind + ":" + SHA256(filePath + ":" + kind + ":" + qualifiedName + ":" + line)
```

关键设计决策：

* **确定性**：同一文件同一符号总是生成相同 ID（幂等）

* **前缀包含 kind**：方便调试时识别节点类型

* **使用 qualifiedName 而非 name**：避免不同类中同名方法的 ID 冲突

***

## 3. Edge 数据模型详解

每个 Edge 的 7 个字段（对应 edges 表）：

| 字段           | 类型         | 填充逻辑                                                               |
| ------------ | ---------- | ------------------------------------------------------------------ |
| `id`         | INTEGER PK | SQLite 自增，未显式设置                                                    |
| `source`     | TEXT FK    | 源节点 ID，ON DELETE CASCADE                                           |
| `target`     | TEXT FK    | 目标节点 ID，ON DELETE CASCADE                                          |
| `kind`       | TEXT       | 枚举 `EdgeKind`                                                      |
| `line`       | INTEGER    | 关系所在行号（1-based）                                                    |
| `col`        | INTEGER    | 关系所在列号（1-based）                                                    |
| `provenance` | TEXT       | 来源标记：null（AST直接提取）、`"heuristic"`（启发式推断）、`"framework:Spring"`（框架提取） |

### 3.1 EdgeKind 详解

| EdgeKind       | 生成场景     | Java 中的触发条件                                                    |
| -------------- | -------- | -------------------------------------------------------------- |
| `CONTAINS`     | AST 父子关系 | class 包含 field/method、method 包含局部变量（未实现）                       |
| `EXTENDS`      | 继承关系     | `class A extends B` → `superclass` field 节点                    |
| `IMPLEMENTS`   | 接口实现     | `class A implements B,C` → `interfaces` field → 遍历 `type_list` |
| `CALLS`        | 方法调用     | 方法体内的 `method_invocation` → 启发式解析                              |
| `REFERENCES`   | 框架引用     | 框架提取阶段（`framework:Spring` 等）                                   |
| `RETURNS`      | 返回类型关系   | 暂未实现                                                           |
| `INSTANTIATES` | 实例化      | 暂未实现                                                           |
| `OVERRIDES`    | 方法重写     | 暂未实现                                                           |
| `TYPE_OF`      | 类型关系     | 暂未实现                                                           |
| `DECORATES`    | 装饰器/注解   | 暂未实现                                                           |
| `IMPORTS`      | 导入关系     | 暂未实现（IMPORT 仅作为节点存在）                                           |
| `EXPORTS`      | 导出关系     | 暂未实现                                                           |

### 3.2 CONTAINS 边的生成逻辑

[ExtractorContext.java](file:///Users/wugao-pc/Desktop/Project/codegraph4j/src/main/java/com/codegraph/extraction/tree_sitter/ExtractorContext.java#L86-L100)

* 通过 **作用域栈**（`parentIdStack`）维护当前容器节点

* 每当创建子节点时，自动添加到当前栈顶节点的 CONTAINS 边

* `pushScope()` 进入类作用域，`popScope()` 离开

### 3.3 CALLS 边的启发式解析

[TreeSitterExtractor.java](file:///Users/wugao-pc/Desktop/Project/codegraph4j/src/main/java/com/codegraph/extraction/tree_sitter/TreeSitterExtractor.java#L879-L955)

两阶段处理：

1. **AST 遍历阶段**：收集所有 `method_invocation` → 存入 `callReferences` 列表（记录 callerId、calleeName、receiverType、位置）
2. **后置解析阶段**（`resolvePendingReferences`）：对已收集的调用引用进行启发式匹配：

   * `this.xxx()` → 在本类中查找同名方法

   * 无 receiver 调用 → 在当前文件的方法列表中按名称匹配

   * `obj.xxx()` → 优先匹配 public/protected 方法

   * `super.xxx()` → 沿 EXTENDS 边找父类，再在父类中找方法

***

## 4. 数据库 Schema 与索引

### 4.1 核心表

| 表                 | 用途             | 外键                                           |
| ----------------- | -------------- | -------------------------------------------- |
| `nodes`           | 代码符号（21 列）     | —                                            |
| `edges`           | 符号间关系（7 列）     | source/target → nodes(id) ON DELETE CASCADE  |
| `files`           | 已追踪源文件元数据（8 列） | —                                            |
| `unresolved_refs` | 未解析引用（8 列）     | from\_node\_id → nodes(id) ON DELETE CASCADE |

### 4.2 关键索引与搜索加速

[schema.sql](file:///Users/wugao-pc/Desktop/Project/codegraph4j/src/main/resources/db/schema.sql#L90-L127)

| 索引                         | 加速的查询                             |
| -------------------------- | --------------------------------- |
| `idx_nodes_kind`           | 按类型过滤（按 CLASS/METHOD/FIELD 查询）    |
| `idx_nodes_name`           | 精确名称查找（`getNodesByName`）          |
| `idx_nodes_qualified_name` | 全限定名查找（`getNodesByQualifiedName`） |
| `idx_nodes_file_path`      | 按文件查找所有节点（`getNodesInFile`）       |
| `idx_nodes_file_line`      | 按文件+行号定位                          |
| `idx_nodes_lower_name`     | 大小写不敏感查找                          |
| `idx_edges_source_kind`    | 出边 + 类型联合查询（BFS 遍历核心）             |
| `idx_edges_target_kind`    | 入边 + 类型联合查询（callers/impact 分析核心）  |
| `idx_edges_provenance`     | 框架提取来源过滤                          |

### 4.3 FTS5 全文搜索

```sql
CREATE VIRTUAL TABLE nodes_fts USING fts5(
    id, name, qualified_name, docstring, signature,
    content='nodes', content_rowid='rowid'
);
```

* 使用 SQLite FTS5 外部内容表模式（`content='nodes'`）

* 通过 INSERT/UPDATE/DELETE 触发器自动同步

* 加速 `searchNodes()` 的 LIKE 查询（name + qualified\_name + docstring）

***

## 5. 对搜索的好处

### 5.1 codegraph\_explore 的搜索链路

[ContextBuilder.java](file:///Users/wugao-pc/Desktop/Project/codegraph4j/src/main/java/com/codegraph/context/ContextBuilder.java#L89-L233)

```
用户查询 "UserService login"
        │
        ▼
Step 1: 符号提取 (extractSymbols)
  └─ 分词 → 去文件扩展名 → 过滤短词 → ["UserService", "login"]
        │
        ▼
Step 2: 精确匹配 (qualified_name + name)
  └─ getNodesByQualifiedName("UserService") → 找到 UserService 类
  └─ getNodesByQualifiedName("login")       → 找到 login 方法
  └─ Co-location boost: 同一文件中多个命中 → 加权
        │
        ▼
Step 3: 前缀匹配 (Title-Case, 仅类型定义)
  └─ searchNodes("UserService") → 前缀过滤
        │
        ▼
Step 4: BFS 图扩展 (traversalDepth=3)
  └─ 从 search results 沿 CONTAINS/CALLS/EXTENDS/IMPLEMENTS 等边扩展
  └─ 排除 CONTAINS 边（避免子图爆炸）
        │
        ▼
Step 5: 文件分组 + 评分 + 排序
        │
        ▼
Step 6: RWR 图相关性计算
        │
        ▼
Step 7: 渲染输出（骨架化 / 整文件 / 聚类分组）
```

### 5.2 数据结构设计如何支撑搜索

| 能力         | 依赖的数据                             | 实现方式                                           |
| ---------- | --------------------------------- | ---------------------------------------------- |
| **符号查找**   | `name`, `qualified_name` 索引       | 精确匹配 → 模糊匹配 → 前缀匹配三层搜索                         |
| **调用链追踪**  | CALLS + REFERENCES + IMPLEMENTS 边 | BFS 沿出边/入边递归                                   |
| **影响分析**   | CONTAINS + CALLS + 入边             | 从目标节点沿入边向上，遇到容器节点自动展开子成员                       |
| **类型层级**   | EXTENDS + IMPLEMENTS 边            | 递归查找祖先/后代                                      |
| **死代码检测**  | 入边过滤（排除 CONTAINS）                 | 无入边且非 exported → 疑似死代码                         |
| **循环依赖**   | 文件级依赖图（跨文件边）                      | DFS 检测文件间环                                     |
| **全限定名定位** | `qualified_name` 包含完整包路径          | `com.example.service.UserService.login` 精确唯一定位 |
| **关系可视化**  | 多类型 EdgeKind                      | CALLS/EXTENDS/IMPLEMENTS/CONTAINS 分开展示         |
| **配置过滤**   | NodeKind + name 模式                | 过滤 `@Value` 注入的 config leaf 节点                 |
| **多态骨架化**  | EXTENDS/IMPLEMENTS 入边计数           | ≥3 个实现的父类/接口 → 骨架化渲染节省 token                   |

### 5.3 自适应输出预算

[ExploreOutputBudget.java](file:///Users/wugao-pc/Desktop/Project/codegraph4j/src/main/java/com/codegraph/context/ExploreOutputBudget.java)

根据索引文件数量自适应调整：

* 小项目（<50 文件）→ 更多文件，更大字符预算

* 大项目（>5000 文件）→ 限制输出，避免超出 LLM 上下文窗口

***

## 6. 当前设计的问题

### 6.1 🔴 严重问题

#### 问题 1: 单文件异常导致同批次回滚

**位置**: [SyncOrchestrator.java#L193-L196](file:///Users/wugao-pc/Desktop/Project/codegraph4j/src/main/java/com/codegraph/sync/SyncOrchestrator.java#L193-L196)

batchSize=100，第 73 个文件解析异常触发 `db.rollback()`，前 72 个已处理的文件结果也丢失。

#### 问题 2: 框架提取无事务提交

**位置**: [SyncOrchestrator.java#L215-L293](file:///Users/wugao-pc/Desktop/Project/codegraph4j/src/main/java/com/codegraph/sync/SyncOrchestrator.java#L215-L293)

`runFrameworkExtraction()` 逐条 insertNode/insertEdge 但无 `db.commit()`，依赖外层隐式提交，数据可能丢失。

#### 问题 3: CALLS 边仅限同文件启发式匹配

**位置**: [TreeSitterExtractor.java#L886-L955](file:///Users/wugao-pc/Desktop/Project/codegraph4j/src/main/java/com/codegraph/extraction/tree_sitter/TreeSitterExtractor.java#L886-L955)

`resolvePendingReferences` 只在**当前文件的 methods 列表**中查找 callee。跨文件的调用（如 `userService.login()` 调用另一个文件中的方法）无法在单文件解析阶段生成 CALLS 边。跨文件边依赖后续增量 sync 时两个文件都被重新解析才能补全。

#### 问题 4: EXTENDS/IMPLEMENTS 目标节点是伪 ID

**位置**: [TreeSitterExtractor.java#L708-L710](file:///Users/wugao-pc/Desktop/Project/codegraph4j/src/main/java/com/codegraph/extraction/tree_sitter/TreeSitterExtractor.java#L708-L710)

```java
private String buildExternalNodeId(String filePath, String kind, String name) {
    return TreeSitterHelpers.generateNodeId(filePath, kind, name, 0); // ← line=0!
}
```

EXTENDS/IMPLEMENTS 的 target 使用 `line=0` 生成 ID，这是一个**伪 ID**——因为父类/接口在另一个文件中，真正的节点 ID 使用其实际行号。两者永远不匹配。这意味着 EXTENDS 和 IMPLEMENTS 边指向无法解析的节点，图遍历时这些边实际断裂。

### 6.2 🟡 中等问题

#### 问题 5: 排除目录用 contains() 误伤合法文件

**位置**: [SyncOrchestrator.java#L390-L398](file:///Users/wugao-pc/Desktop/Project/codegraph4j/src/main/java/com/codegraph/sync/SyncOrchestrator.java#L390-L398)

`pathStr.contains("target")` 会排除 `TargetSelector.java`。

#### 问题 6: JavaScript/TypeScript 使用正则而非 AST

**位置**: [JavaScriptParser.java](file:///Users/wugao-pc/Desktop/Project/codegraph4j/src/main/java/com/codegraph/extraction/JavaScriptParser.java)

JS/TS/JSX/TSX 使用正则表达式匹配，无法生成准确的边。

#### 问题 7: searchNodes 使用 LIKE 而非 FTS5

**位置**: [QueryBuilder.java#L112-L136](file:///Users/wugao-pc/Desktop/Project/codegraph4j/src/main/java/com/codegraph/db/QueryBuilder.java#L112-L136)

虽然 schema 中定义了 FTS5 虚拟表和触发器，但 `searchNodes()` 实际使用的是 `LOWER(name) LIKE ?`，并未利用 FTS5 全文索引。FTS5 索引处于"创建但未使用"状态。

#### 问题 8: 节点 ID 中 key-value 只有一个字段

**位置**: [TreeSitterHelpers.java#L36-L50](file:///Users/wugao-pc/Desktop/Project/codegraph4j/src/main/java/com/codegraph/extraction/tree_sitter/TreeSitterHelpers.java#L36-L50)

ID 前缀只有 `kind` 没有 `filePath` 的缩写，多个文件中同类型符号的 ID 前缀完全一样，不利于调试定位。

### 6.3 🟢 改进建议

#### 问题 9: force=true 跳过全部增量优化

即使 `force=true`，也可以只对 hash 不同的文件做 AST 解析，避免不必要的 I/O。

#### 问题 10: 缺少 decorators（注解）提取

Java 的 `decorators` 字段始终为 null。注解信息（如 `@Service`, `@Autowired`, `@Transactional`）对理解代码架构非常有价值，但目前未被提取。

#### 问题 11: 缺少 type\_parameters（泛型）提取

`type_parameters` 字段始终为 null。泛型信息（如 `List<User>` 中的 `User`）可用于类型引用追踪。

#### 问题 12: INSTANTIATES / OVERRIDES 边未实现

`new XXX()` 实例化调用和 `@Override` 方法重写关系未被提取为边。

#### 问题 13: MODULE/IMPORT 节点无关联边

`MODULE`（包）和 `IMPORT`（导入）节点被创建为独立节点，但它们与 CLASS 节点之间没有边连接。无法通过图遍历回答"哪些类导入了 java.util.List"。

#### 问题 14: 框架提取不支持 SPI 扩展

框架解析器硬编码在 `FrameworkRegistry.registerDefaults()`，添加新框架需修改源码。

#### 问题 15: 搜索缺少语义理解

当前搜索是纯文本匹配（LIKE 子串），缺乏对同义词、缩写、自然语言描述的理解能力。

***

## 7. 架构总结

```
                          ┌─────────────────────────┐
                          │    IndexCommand /        │
                          │    SyncCommand           │
                          └───────────┬─────────────┘
                                      │
                          ┌───────────▼─────────────┐
                          │   SyncOrchestrator       │
                          │  ┌─────────────────────┐ │
                          │  │ 文件发现 (walk)      │ │
                          │  │ 对账删除 (差集)      │ │
                          │  │ 三层过滤 (size/mtime │ │
                          │  │         /sha256)     │ │
                          │  │ 批量解析 (batch=100) │ │
                          │  │ 框架提取 (Spring等)  │ │
                          │  └─────────────────────┘ │
                          └───────────┬─────────────┘
                                      │
              ┌───────────────────────┼───────────────────────┐
              │                       │                       │
    ┌─────────▼─────────┐  ┌─────────▼─────────┐  ┌─────────▼─────────┐
    │  TreeSitterExtractor│  │  FrameworkRegistry │  │  QueryBuilder      │
    │  ┌───────────────┐ │  │  SpringResolver    │  │  insertNode/Edge   │
    │  │ visitNode DFS │ │  │  DubboResolver     │  │  searchNodes       │
    │  │ visitClass    │ │  │  OpenFeignResolver │  │  getNodesByName    │
    │  │ visitMethod   │ │  └───────────────────┘  │  getOutgoingEdges  │
    │  │ visitField    │ │                         │  getIncomingEdges  │
    │  │ visitEnum     │ │                         └───────────────────┘
    │  │ resolveCalls  │ │
    │  └───────────────┘ │
    └───────────────────┘
                                      │
                          ┌───────────▼─────────────┐
                          │      SQLite DB           │
                          │  nodes / edges / files   │
                          │  unresolved_refs         │
                          │  FTS5 全文索引           │
                          └───────────┬─────────────┘
                                      │
                          ┌───────────▼─────────────┐
                          │   MCP Server (查询层)    │
                          │  ┌─────────────────────┐ │
                          │  │ codegraph_explore   │ │
                          │  │ codegraph_search    │ │
                          │  │ codegraph_callers   │ │
                          │  │ codegraph_callees   │ │
                          │  │ codegraph_impact    │ │
                          │  │ codegraph_node      │ │
                          │  │ codegraph_status    │ │
                          │  │ codegraph_files     │ │
                          │  └─────────────────────┘ │
                          └─────────────────────────┘
```

### 数据流向

```
Java 源码
  → tree-sitter 解析 → AST
  → TreeSitterExtractor.visitNode() DFS 遍历
  → ExtractorContext.createNode() 创建 Node（填充 21 个字段）
  → ExtractorContext.addEdge() 创建 Edge（填充 7 个字段）
  → resolvePendingReferences() 启发式生成 CALLS 边
  → QueryBuilder.insertNode/insertEdge() 写入 SQLite
  → FTS5 触发器自动同步全文索引
  → GraphTraverser BFS/DFS 遍历边进行图查询
  → ContextBuilder 混合搜索 + 图扩展 + 评分排序
  → MCPToolHandler 渲染输出
```

### 核心设计原则

1. **确定性 ID**：SHA-256 哈希确保幂等，重复索引不产生重复数据
2. **作用域栈**：Deque 维护类嵌套层级，自动构建 qualifiedName 和 CONTAINS 边
3. **两阶段调用解析**：遍历时收集引用，遍历后启发式匹配，避免边生成干扰 AST 遍历
4. **批量提交**：batchSize=100 平衡事务开销和异常影响范围
5. **分层搜索**：精确匹配 → 前缀匹配 → 图扩展，逐步扩大搜索范围
6. **自适应预算**：根据项目大小动态调整输出量

