# Plan: 实现完整的 `handleExplore` (codegraph_explore)

## 1. Summary

将 Java 版本的 `codegraph_explore` 从当前极度简化的实现，重构为**功能完整对标 Node.js codegraph** 的版本。Node.js 版本包含：自适应输出预算、混合搜索、图感知粘合、命名符号播种、RWR 图相关性排序、爆炸半径分析、关系图、适应性源码渲染（多态同构文件骨架化/神主文件裁剪/聚类分组）等复杂逻辑。

当前 Java 版本仅做了简单的 FTS 搜索 + 按文件分组，几乎是空白实现。

## 2. Current State Analysis

### 现有代码

| 文件 | 现状 |
|---|---|
| `MCPToolHandler.handleExplore()` | 极简：FTS 搜索 → 按文件分组 → 展示节点名/签名 → children 列表 → 状态信息 |
| `GraphQueryManager` | 有 `getContext()`, `getFileDependencies()`, `getFileDependents()`, `findCircularDependencies()`, `findDeadCode()`, `getNodeMetrics()` |
| `GraphTraverser` | 有 BFS 遍历、getCallers/getCallees/getAncestors/getChildren/findPath 等 |
| `QueryBuilder` | 有 `searchNodes()`, `getNodesByName()`, `getNodesByQualifiedName()`, `getNodesByKind()`, `getAllNodes()`, `getIncomingEdges()`, `getOutgoingEdges()` |
| `com.codegraph.context/` | **不存在** — 需要新建 |

### 关键差距

Node.js `handleExplore` 有约 **700 行逻辑**，Java 当前约 **70 行**。主要缺失：

1. **自适应输出预算** (`getExploreOutputBudget`) — 根据项目规模（文件数分段）返回不同预算参数
2. **混合搜索** (`findRelevantContext`) — exact match + prefix match + text search + co-location boost
3. **图感知粘合** — 注入 entry 节点的 callers/callees（同文件内的）
4. **命名符号播种** — 从 query 中提取 token，解析为实体节点，注入子图
5. **RWR 图相关性** (`computeGraphRelevance`) — Random Walk with Restart，从种子节点计算每个节点的相关性得分
6. **文件评分与排序** — graph score + term hits + centrality + entry files + low-value deprioritization + generated file deprioritization
7. **相关性门控** — graph score 阈值 / central files / entry files / ≥2 term hits
8. **爆炸半径分析** (`buildBlastRadiusSection`) — entry 符号的调用者列表
9. **关系图** — 按 kind 分组的非 contains 边
10. **Flow spine** (`buildFlowFromNamedSymbols`) — 命名符号间的调用路径
11. **适应性源码渲染** — 多态同构检测 / 神主文件检测 / 骨架化 vs 整文件 vs 聚类分组

## 3. Proposed Changes

### 新建文件

#### 3.1 `com.codegraph.context.ContextBuilder`

**职责**：封装 `findRelevantContext` 混合搜索逻辑 + `buildFlowFromNamedSymbols` 流程路径构建。

```java
package com.codegraph.context;

public class ContextBuilder {
    private final QueryBuilder queries;
    private final GraphTraverser traverser;

    // 公开方法
    public Subgraph findRelevantContext(String query, FindOptions opts);
    public FlowInfo buildFlowFromNamedSymbols(String query);

    // 内部
    private List<String> extractSymbolsFromQuery(String query);
    private List<SearchResult> exactMatchSearch(List<String> symbols, int limit);
    private List<SearchResult> prefixMatchSearch(List<String> symbols, int limit);
    private List<SearchResult> textSearch(List<String> terms, int limit);
    private Subgraph buildSubgraph(List<SearchResult> results, int maxNodes, int depth);
}
```

#### 3.2 `com.codegraph.context.Subgraph`

```java
package com.codegraph.context;

public class Subgraph {
    public Map<String, Node> nodes = new LinkedHashMap<>();
    public List<Edge> edges = new ArrayList<>();
    public List<String> roots = new ArrayList<>();  // 入口节点 ID 列表
}
```

#### 3.3 `com.codegraph.context.ExploreOutputBudget`

**职责**：对标 `getExploreOutputBudget()`，根据项目规模返回输出预算参数。

```java
package com.codegraph.context;

public class ExploreOutputBudget {
    public int maxOutputChars;           // 总输出上限
    public int defaultMaxFiles;          // 默认最大文件数
    public int maxCharsPerFile;          // 单文件最大字符数
    public int gapThreshold;             // 聚类合并间隙阈值
    public int maxSymbolsInFileHeader;  // 文件头最大符号数
    public int maxEdgesPerRelationshipKind; // 每类边最大显示数
    public boolean includeRelationships;     // 是否包含关系图
    public boolean includeAdditionalFiles;   // 是否包含额外文件
    public boolean includeCompletenessSignal;// 是否包含完整性信号
    public boolean includeBudgetNote;        // 是否包含预算说明
    public boolean excludeLowValueFiles;     // 是否排除低价值文件
}
```

#### 3.4 `com.codegraph.context.GraphRelevanceComputer`

**职责**：实现 Random Walk with Restart 算法，计算图中每个节点相对于种子节点的相关性得分。

```java
package com.codegraph.context;

public class GraphRelevanceComputer {
    // RWR 算法，alpha=0.25，迭代 25 次
    // 边类型过滤：calls, references, extends, implements, overrides, instantiates, returns, type_of, imports
    public Map<String, Double> compute(Map<String, Node> nodes, List<Edge> edges, Set<String> seedIds);
}
```

#### 3.5 `com.codegraph.context.BlastRadiusBuilder`

**职责**：构建爆炸半径分析段落。

```java
package com.codegraph.context;

public class BlastRadiusBuilder {
    public String build(Subgraph subgraph, QueryBuilder queries, GraphTraverser traverser);
}
```

### 修改文件

#### 3.6 `MCPToolHandler.handleExplore()` — 重写为完整实现

将现有的约 70 行简化代码替换为约 **400 行**完整实现，严格对标 Node.js `handleExplore` 的步骤：

**Step 1**：获取自适应输出预算 (`ExploreOutputBudget`)

**Step 2**：调用 `contextBuilder.findRelevantContext()` 获取混合搜索子图

**Step 3**：图感知粘合 — 注入 entry 节点的 callers/callees（同文件内）

**Step 4**：命名符号播种 — tokenize query → 解析为实体节点 → 注入子图

**Step 5**：节点按文件分组 + 评分
- named-seed 节点 +50 分
- entry 节点 +10 分
- 直接连接 entry 的节点 +3 分
- 其他 +1 分

**Step 6**：相关性门控（graph score / central files / entry files / ≥2 term hits）

**Step 7**：文件排序（named files > corroborated > graph > term hits > low-value > generated）

**Step 8**：构建输出
- 爆炸半径段落 (`BlastRadiusBuilder`)
- 关系图段落（按 kind 分组，cap=每类边数）
- **源码段落**（适应性渲染策略）：
  - 多态同构骨架化（isPolymorphicSibling 检测）
  - 神主文件自适应（onSpineGodFile 检测）
  - 小文件整文件输出
  - 大文件聚类分组

**Step 9**：追加状态/预算说明（根据 budget 配置）

## 4. Key Algorithms Detail

### 4.1 `getExploreOutputBudget` 分段规则

| 项目文件数 | maxOutputChars | defaultMaxFiles | maxCharsPerFile | includeRelationships | excludeLowValueFiles |
|---|---|---|---|---|---|
| < 150 | 13000 | 4 | 3800 | false | true |
| < 500 | 18000 | 5 | 3800 | false | true |
| < 5000 | 24000 | 8 | 6500 | true | false |
| ≥ 5000 | 24000 | 8 | 7000 | true | false |

### 4.2 `findRelevantContext` 混合搜索

1. `extractSymbolsFromQuery`: 用正则 `[A-Za-z_$][\w$]*(?::?::?[\w$]+)*` 提取有效符号（长度≥3，最多16个）
2. **exact match**: `queries.getNodesByName()` 或 `getNodesByQualifiedName()`，按 co-location boost 排序
3. **prefix match**: 对 title-case 化的 token（前缀匹配 class/interface/struct/trait/protocol/enum/type_alias），如 "Rest" → "RestController"
4. **text search**: `queries.searchNodes()` 对每个 term
5. **子图构建**: 从 exact/prefix/text 结果节点出发，BFS 深度 3，最大 200 节点

### 4.3 RWR (Random Walk with Restart)

- 无向图邻接（双向可达）
- 边类型过滤：`calls, references, extends, implements, overrides, instantiates, returns, type_of, imports`
- α = 0.25（重启概率）
- 迭代 25 次或收敛
- 结果：每个 nodeId → relevance score [0,1]

### 4.4 源码渲染三策略

**策略 A — 骨架化**：多态同构文件（非 flow spine 上，无 unique named 符号）
- 每个符号一行：签名（无 body）
- 命中的 spine/unique named 符号显示完整 body
- per-file body cap = `maxCharsPerFile * 1.5`

**策略 B — 整文件**：小文件（≤220 行，≤`maxCharsPerFile * 3` 字符）
- 直接输出全文
- central 文件限 280 行，`maxCharsPerFile * 1.5` 字符

**策略 C — 聚类分组**：大文件
- 按行号排序节点，合并相邻/重叠范围（gap≤gapThreshold）
- 每个范围带 importance 权重：entry=10, named=9, glue=6, connected=3, peripheral=1
- 按权重选择要展示的范围，超 cap 时丢 peripheral
- 输出格式：完整 body 范围 + 签名范围 + "+N more" 提示

### 4.5 低价值文件过滤

```java
private boolean isLowValue(String path) {
    String lp = path.toLowerCase();
    return  lp.contains("/tests/") || lp.contains("/spec/") || lp.contains("/__tests__/") ||
            lp.contains("/test/") || lp.contains("/specs/") ||
            path.endsWith("_test.go") || path.endsWith("_test.py") ||
            path.endsWith("_spec.rb") || path.endsWith("_test.rb") ||
            path.endsWith(".test.ts") || path.endsWith(".spec.ts") ||
            path.endsWith(".test.tsx") || path.endsWith(".spec.tsx") ||
            path.endsWith(".test.js") || path.endsWith(".spec.js") ||
            path.endsWith(".test.java") || path.endsWith(".spec.java") ||
            path.contains("/icons/") || path.contains("/i18n/");
}
```

## 5. Assumptions & Decisions

1. **是否复用 `GraphQueryManager`？** 是。所有图查询通过 `GraphQueryManager` + `GraphTraverser` 暴露的接口完成，不直接操作 QueryBuilder。
2. **是否需要 FTS5？** `QueryBuilder.searchNodes()` 已使用 FTS（`db/index.sql` 中有 `INSERT INTO nodes_fts`），无需新增。
3. **文件读取**：使用 `Files.readAllLines()` 直接从磁盘读取源码（对标 Node.js `readFileSync`），磁盘读取比 DB 查询稳定。
4. **多态同构检测**：需要查询 `IMPLEMENTS`/`EXTENDS` 入边计数≥3 的接口/父类。当前 `queries.getIncomingEdges()` 已支持。
5. **生成文件识别**：检测 `.pb.go`, `._mocks.go`, `generated_*.java` 等模式。
6. **测试文件例外**：当 query 本身包含 "test/testing/spec/verify" 时，不过滤测试文件。
7. **Scope**: 仅实现 `handleExplore`。`buildBlastRadiusSection` 和 `buildFlowFromNamedSymbols` 的简化版在 `MCPToolHandler` 内部实现，不单独建类（保持代码内聚）。

## 6. Verification

```bash
# 编译验证
mvn clean compile -q

# 运行 MCP 测试
cat > /tmp/explore_test.json << 'EOF'
{"jsonrpc":"2.0","id":"1","method":"initialize","params":{"protocolVersion":"2024-11-05","clientInfo":{"name":"test","version":"1.0"},"capabilities":{}}}
{"jsonrpc":"2.0","id":"2","method":"tools/call","params":{"name":"codegraph_explore","arguments":{"query":"EsPersistService","maxFiles":5}}}
EOF
cat /tmp/explore_test.json | mvn exec:java -Dexec.mainClass="com.codegraph.cli.CodeGraphCli" -Dexec.args="serve -p /Users/wugao-pc/Desktop/Project/stream" -Dexec.classpathScope=compile 2>/dev/null

# 对比输出：
# - 应包含 "Exploration:" 标题
# - 应包含 "Found X symbols across Y files"
# - 应包含 "Blast radius" 或 "Relationships"（大项目）
# - 应包含 "Source Code" 和文件路径
# - 应包含带行号的源码片段
# - 应包含 "Budget:" 或总节点/边统计
```
