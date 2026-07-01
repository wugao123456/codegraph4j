# codegraph_explore 核心流程详解

> 实现文件：[MCPToolHandler.java#L413-L823](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/mcp/MCPToolHandler.java#L413-L823)

## 概述

`codegraph_explore` 是 CodeGraph MCP 工具集中最核心的工具，它接收自然语言查询（中英文混合），通过 **8 步流水线** 将代码知识图谱中的信息压缩为结构化 Markdown 输出，供 AI 助手理解代码库。

**设计目标**：在有限 token 预算内，最大化返回与用户查询最相关的代码段和关系信息。

---

## 输入/输出

| | 内容 |
|---|---|
| **输入** | 自然语言查询字符串，如 `"MCPToolHandler 探索流程的搜索和渲染逻辑"` |
| **输出** | Markdown 格式的结构化响应，包含代码片段(带行号)、爆炸半径、关系图、完整性信号、预算说明 |

---

## 核心流程（8 步流水线）

### Step 1: 自适应输出预算

**位置**：[L419-L424](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/mcp/MCPToolHandler.java#L419-L424)

**做什么**：根据已索引的文件数量，动态调整输出上限。

**实现**：

```java
int fileCount = countIndexedFiles();
ExploreOutputBudget budget = ExploreOutputBudget.getForFileCount(fileCount);
```

| 项目文件数 | maxFiles | maxOutputChars | 策略 |
|-----------|----------|---------------|------|
| ≤ 50 | 12 | 30,000 | 小项目，宽裕 |
| ≤ 200 | 8 | 20,000 | 中型项目 |
| > 500 | 4 | 13,000 | 大型项目，严格限制 |

**为什么这样写**：

AI 助手的上下文窗口有限（通常 80K~200K tokens），对于大型项目，必须严格限制输出量。小项目文件少，可以放宽上限，确保返回足够多的上下文。

**示例**：

```
codegraph4j 项目（72 个文件）→ maxFiles=4, maxOutputChars=13,000
```

---

### Step 2: 混合搜索获取子图

**位置**：[L427-L439](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/mcp/MCPToolHandler.java#L427-L439)

**做什么**：从查询字符串中提取符号名 → 精确匹配 → 前缀匹配 → 图遍历扩展，构建初始的代码上下文子图。

**实现**：

```java
ContextBuilder.FindOptions opts = new ContextBuilder.FindOptions();
opts.searchLimit = 8;      // 最多搜索 8 个符号
opts.traversalDepth = 3;   // 图遍历深度 3 层
opts.maxNodes = 200;       // 子图最多 200 个节点

Subgraph subgraph = ctxBuilder.findRelevantContext(query, opts);
```

**子流程**（[ContextBuilder.java#L89-L233](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/context/ContextBuilder.java#L89-L233)）：

```
query = "MCPToolHandler handleExplore"
     │
     ├─ extractSymbols() → ["MCPToolHandler", "handleExplore"]
     │      │
     │      ▼
     ├─ Exact Match (精确匹配)
     │   getNodesByQualifiedName("MCPToolHandler") → 1 个节点 (com.codegraph.mcp.MCPToolHandler)
     │   getNodesByName("MCPToolHandler")          → 1 个节点
     │   getNodesByName("handleExplore")           → 1 个节点 (handleExplore 方法)
     │   ---
     │   Co-location boost: 同文件多符号命中 → 加权
     │      │
     │      ▼
     ├─ Prefix Match (前缀匹配，用于类型定义)
     │   determinePrefixPattern("MCPToolHandler") → "MCPToolHandler"
     │   searchNodes("MCPToolHandler") → 匹配 MCPToolHandler / MCPToolHandlerTest 等
     │      │
     │      ▼
     └─ BFS Graph Expansion (广度优先图扩展)
         从 root 节点出发，沿边扩展 3 层
         边类型: CALLS, REFERENCES, EXTENDS, IMPLEMENTS, OVERRIDES,
                 INSTANTIATES, RETURNS, TYPE_OF, IMPORTS
         发现更多相关节点 → 补全子图
```

**为什么这样写**：

1. **精确匹配优先**：如果用户输入了精确的类名/方法名，应该优先返回，这是最有价值的结果
2. **前缀匹配兜底**：用户可能只记得类名的一部分（如 `MCP`），前缀匹配可以提高召回率
3. **图扩展补全上下文**：找到核心节点后，沿调用关系扩展，可以发现"围绕这个符号的生态系统"
4. **searchLimit=8**：防止搜索过多符号导致结果膨胀、内存消耗过大
5. **traversalDepth=3**：3 层深度在"覆盖范围"和"噪音控制"之间取得平衡

**示例**：

```
查询 "MCPToolHandler" → 匹配到 MCPToolHandler 类
                     → 通过 CALLS 边发现 handleExplore、execute、writeExploreLog 等方法
                     → 通过 REFERENCES 边发现 MCPSession、MCPServer 等依赖
                     → 子图: 3 nodes, 8 edges
```

---

### Step 3: 图感知粘合（Glue Nodes）

**位置**：[L441-L470](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/mcp/MCPToolHandler.java#L441-L470)

**做什么**：对每个 root 节点，查找其在 **同文件内** 的 callers 和 callees，填补子图中的"空隙"。

**实现**：

```java
// 对每个 root 节点
for (String rootId : subgraph.roots) {
    // 查 1 层深度的 callers（谁调用了 root）
    List<CallerInfo> callers = traverser.getCallers(rootId, 1);
    // 查 1 层深度的 callees（root 调用了谁）
    List<CalleeInfo> callees = traverser.getCallees(rootId, 1);
    // 只注入同文件内的节点，最多 60 个
}
```

**为什么这样写**：

Step 2 的图扩展可能跳过了同文件内的"中间节点"。举个例子：

```
文件: MCPToolHandler.java
  handleExplore()         ← Step 2 找到了
      │ CALLS
      ▼
  renderSkeleton()        ← Step 2 没找到（因为不是直接 root，也没被精确匹配）
      │ CALLS
      ▼
  numberSourceLines()     ← Step 2 可能也找到了
```

`renderSkeleton()` 虽然很重要，但在图扩展中可能被跳过（因为有 searchLimit 限制）。粘合操作确保同文件内的中间节点也被补全，形成一个更完整的"局部上下文"。

**限制**：
- 只补充同文件内的节点（`subgraphFiles.contains(ci.node.getFilePath())`），避免引入无关文件
- 最多 60 个粘合节点，防止大文件（如 1000+ 行的单文件）消耗过多预算

**示例**：

```
查询 "MCPToolHandler" → root = MCPToolHandler 类
→ callers: MCPSession.java 中的调用（不同文件，跳过）
→ callees: handleExplore()（同文件，注入！）
         → 继续: writeExploreLog()（同文件，注入！）
         → 继续: rotateExploreLogs()（同文件，注入！）
→ 粘合完成: glueNodes=3, subgraphFiles=1
```

---

### Step 4: 命名符号播种（Named Seed）

**位置**：[L472-L492](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/mcp/MCPToolHandler.java#L472-L492)

**做什么**：从查询中提取 token，在 **全项目范围**（不限于同文件）内搜索这些符号，注入子图。

**实现**：

```java
List<String> tokens = ctxBuilder.extractSymbols(query);
for (String token : tokens) {
    // 三级降级搜索
    getNodesByQualifiedName(token)    // 1. 精确全限定名
        → getNodesByName(token)      // 2. 简单名
        → searchNodes(token)         // 3. 模糊搜索
    // 注入子图，最多 40 个种子节点
}
```

**为什么这样写**：

Step 3 只补全同文件内的节点，但用户可能提到跨文件的符号。例如：

```
查询: "MCPServer MCPToolHandler"
Step 2 找到了 MCPServer 和 MCPToolHandler，子图中有它们的关系
Step 4 再次提取 "MCPServer" 和 "MCPToolHandler"
    → getNodesByName("MCPServer") 找到 MCPServer.java 中的 MCPServer 类
    → 但 MCPServer 的子图可能只通过图扩展找到了 MCPSession
    → 命名播种确保 "MCPServer" 这个符号对应的文件也被加入子图（即使它之前不在图扩展路径上）
```

**三级降级搜索**的设计理念：
1. `qualified_name` 精确匹配 → 最精确，用户的 token 直接等于全限定名
2. `name` 匹配 → 较精确，可能有多个同名类（如不同包下的 `UserService`）
3. `searchNodes` 模糊匹配 → 容错，用户可能拼写不完全正确

---

### Step 5: 文件分组 + 评分

**位置**：[L494-L543](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/mcp/MCPToolHandler.java#L494-L543)

**做什么**：将子图中的节点按文件分组，给每组打分，过滤低价值和测试文件。

**评分规则**：

| 节点类型 | 得分 | 含义 |
|---------|------|------|
| namedSeed（用户名称的符号） | +50 | 最相关：用户直接提到了这个符号 |
| entry（root 节点，搜索的入口） | +10 | 高度相关：搜索的直接结果 |
| connectedToEntry（与 entry 直接相连） | +3 | 一定相关：和 entry 有直接边关系 |
| 其他 | +1 | 弱相关：通过图扩展间接引入 |

**过滤规则**：

1. **跳过 import/export 节点**：这些是元数据节点，不应出现在输出中
2. **跳过配置文件叶子节点**：如 `application.properties` 中的某个字段，过于琐碎
3. **文件过滤**：只保留 score ≥ 3 的文件（即至少有一个 entry 节点或与 entry 直接相连的节点）
4. **测试文件过滤**：非测试查询时，排除测试文件和配置文件（`excludeLowValueFiles` 为 true 时）

**为什么这样写**：

评分 + 过滤是**质量守门员**。没有这步，结果中会充斥大量无关的"噪音节点"（如配置文件中的字段、import 语句、无关文件的间接关联节点）。

举例：一个大型 Spring Boot 项目中，查询 `"UserService"` 可能通过图扩展关联到几十个文件（因为依赖传播很远），但大多数文件对理解 `UserService` 没有直接帮助。通过评分过滤，只保留真正相关的 2-3 个文件。

---

### Step 6: RWR 图相关性 + 文本命中

**位置**：[L545-L605](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/mcp/MCPToolHandler.java#L545-L605)

**做什么**：通过 Random Walk with Restart (RWR) 算法 + 文本命中统计，精细计算每个文件与查询的相关性，进行门控淘汰。

**子步骤**：

#### 6.1 RWR 图相关性计算

```java
Map<String, Double> nodeRwr = new GraphRelevanceComputer().compute(
    subgraph.nodes.keySet(), subgraph.edges, entryNodeIds);
// 聚合为文件级图分数
Map<String, Double> fileGraphScore = ...
```

**RWR 原理**：从 entry 节点出发，随机游走。每次有 α=0.15 的概率"重新开始"（回到 entry），0.85 的概率沿边走到邻居。迭代收敛后，每个节点得到一个"稳定概率分布"分数。

#### 6.2 查询词文本命中

```java
// 拆分查询为 token（按空白分割）
for (String t : query.toLowerCase().split("\\s+")) {
    if (t.length() >= 3) uniqueTerms.add(t);
}
// 对每个文件，统计 token 在 文件路径 + 符号名 中出现的次数
Map<String, Integer> fileTermHits = ...
```

#### 6.3 Central Files 选出

```java
// 图分数 > 0 且文本命中 >= 1 的文件，取前 2 个
Set<String> centralFiles = // top 2
```

#### 6.4 相关性门控（最终裁决）

```java
// 文件 gs 必须满足以下之一才保留：
// 1. gs >= maxGraph * 0.06（图相关性不低于最高分数的 6%）
// 2. 是 centralFile
// 3. 是 entryFile
// 4. term 命中 >= 2
if (gs >= maxGraph * 0.06 || centralFiles || entryFiles || termHits >= 2) {
    // 保留
}
```

**为什么这样写**：

- **RWR 比简单的"边计数"更准确**：不会因为一个文件有很多边就被错误地高估。RWR 考虑的是"稳定状态下从 entry 到达该节点需要多少步"，间接但重要的节点也能获得合理分数
- **文本命中补全图相关性盲区**：RWR 只看图结构，如果某个文件因为路径中断（如动态调用）没在图里出现，但文件名/符号名匹配了查询词，文本命中可以"挽救"它
- **门控阈值 6%**：太高的阈值会漏掉重要但间接相关的文件，太低会引入噪音。6% 是经验值
- **至少保留 2 个文件**：`gated.size() >= 2` 时剔除，少于 2 时不剔除。确保小项目不会被过度过滤

**示例**：

```
查询 "MCPToolHandler"
→ RWR: MCPToolHandler.java 分数 0.6667, MCPServer.java 分数 0.1111, MCPSession.java 分数 0.0556
→ maxGraph = 0.6667, 阈值 = 0.04
→ MCPToolHandler.java: 通过 (0.6667 ≥ 0.04 + 是 entryFile)
→ MCPServer.java: 通过 (0.1111 ≥ 0.04)
→ SyncCommand.java: 淘汰 (0.0222 < 0.04)
```

---

### Step 7: 文件排序（6 级排序）

**位置**：[L607-L640](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/mcp/MCPToolHandler.java#L607-L640)

**做什么**：将筛选后的文件按相关性排序，确保最重要的文件排在最前。

**排序规则（6 级，逐级比较）**：

| 优先级 | 排序条件 | 含义 |
|--------|---------|------|
| **Tier 1** | namedSeed 所在文件 | 用户明确提到的符号所在文件排最前 |
| **Tier 2** | corroborate（entry/central + ≥2 term 命中） | "强证据"文件：又是入口又是中心，且多个查询词命中 |
| **Tier 3** | 图相关性分数 | RWR 分数高的排前面 |
| **Tier 4** | term 命中数 | 查询词命中多的排前面 |
| **Tier 5** | 低价值文件降权 | 测试文件、配置文件排到后面 |
| **Tier 6** | 生成文件降权 | 自动生成的代码（如 protobuf generated）排最后 |

**为什么这样写**：

AI 助手通常只会阅读输出中**前几个**代码段。排序决定了 AI "先看到什么"。最相关的文件必须排在最前面，否则 AI 可能先看了不重要的大文件，在阅读预算耗尽前都没看到关键代码。

**示例**：

```
查询 "MCPToolHandler execute"
3 个相关文件:
  ┌─ MCPToolHandler.java: namedSeed(MCPToolHandler) + entry + central + 2 term hits
  ├─ MCPServer.java: no namedSeed, entry + central + 1 term hit
  └─ MCPToolHandlerTest.java: no namedSeed, 0 term hits, 低价值(测试文件)

排序结果:
  1. MCPToolHandler.java     ← Tier 1 (namedSeed)
  2. MCPServer.java           ← Tier 2 (central + entry)
  3. MCPToolHandlerTest.java  ← Tier 5 (低价值文件降权)
```

---

### Step 8: 构建输出（3 种渲染策略）

**位置**：[L642-L800](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/mcp/MCPToolHandler.java#L642-L800)

**做什么**：将排序后的文件渲染为 Markdown 格式的源码段落，选择最优渲染策略。

**输出结构**：

```markdown
**Exploration: <query>**                ← 标题块
Found N symbols across M files.         ← 统计块

**Blast radius**                        ← 爆炸半径块（影响范围分析）
- `SymbolName` — 3 callers in ...

**Relationships**                       ← 关系图块
CALLS:
- handleExplore → writeExploreLog
- handleExplore → numberSourceLines

**Source Code**                         ← 源码块（核心）
```java
// 带行号的源码
```
```

#### 渲染策略决策树

```
进入渲染阶段
    │
    ├── isPolymorphicSibling OR isSpineGodFile?
    │       │
    │       YES: 策略 A — 骨架化渲染
    │       │      ├─ 普通多态兄弟类 → 只输出签名（省略方法体）
    │       │      └─ Spine god file → 用户命名的方法输出完整内容，其余输出签名
    │       │
    │       NO: 继续判断
    │
    ├── 文件行数 ≤ 220（central 文件 ≤ 280）且总字符数在预算内?
    │       │
    │       YES: 策略 B — 整文件输出（带行号）
    │       │
    │       NO: 策略 C — 聚类分组渲染
    │
    ▼
```

#### 策略 A: 多态同构骨架化

**适用场景**：接口有 5 个实现类，用户查了接口名，需要展示所有实现类的结构但不浪费 token。

**判断条件**：
- `isPolymorphicSibling`: 节点的父类/接口有 ≥3 个子类（多态兄弟）
- `definesPolymorphicSupertype`: 该文件定义了被多个子类继承的父类
- `onSpineGodFile`: 文件在 Flow 路径上但有大量内容（"神主文件"）

**输出**：
- 普通多态兄弟：只输出方法签名，不输出方法体。标注 `skeleton`
- Spine god file（用户命名的符号 + flow spine 交叉命中）：用户命名的方法输出完整内容，其余只输出签名。标注 `focused`

**为什么这样写**：

多态是 Java 中非常普遍的代码模式。当用户查询接口 `UserService` 时，可能有多个实现类 `UserServiceImpl`、`UserServiceV2`、`MockUserService`。如果每个实现类都输出完整代码，token 消耗会很大。骨架化让 AI 看到结构但不消耗预算。

"神主文件"的处理更精细：如果用户在 query 中提到了某个方法名（如 `createUser`），而该文件在 flow spine 上还有其他方法——AI 最关心的是 `createUser` 的完整实现，其他方法只需要签名作参考。

#### 策略 B: 小文件整文件输出

**适用场景**：文件足够小，可以完整放入上下文。

**判断条件**：
- 行数 ≤ 220（central 放宽到 280）
- 字节数在预算内

**输出**：完整源码，每行带行号 `1→   ...`

**为什么这样写**：

对于小文件，完整输出比"聚类分组"更有效。AI 可以直接看到文件的全貌，理解类的整体结构——哪些 import、哪些字段、哪些方法，不会遗漏。

central 文件放宽到 280 行是因为这是用户最关心的文件，值得多分配一些 token 预算。

#### 策略 C: 聚类分组渲染

**适用场景**：大文件，预算不够整文件输出，也不满足骨架化条件。

**输出**：将文件内相关节点按调用关系聚类分组，只输出有节点的代码片段。

**为什么这样写**：

对于 500+ 行的大文件，输出整文件不现实。聚类分组只输出"和查询相关的代码段"，每一段都是一个连续的代码块，包含所有被匹配到的节点及其上下文行。

---

### 末尾信号 & 日志

**完整性信号**（[L802-L808](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/mcp/MCPToolHandler.java#L802-L808)）：

```markdown
*Some files were trimmed or omitted due to output budget limits.*
```

**为什么这样写**：

告诉 AI"这里的内容不完整"，提示 AI 如果还需要更多上下文，可以用 `codegraph_search` 或 `codegraph_callers` 等工具进一步探索。没有这行，AI 可能以为返回的内容就是全部了。

**预算说明**（[L811-L813](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/mcp/MCPToolHandler.java#L811-L813)）：

```markdown
*Explore output budget: 12345/30000 chars, 5/8 files.*
```

**为什么这样写**：

让 AI 知道"还剩多少预算没用"，AI 可以据此决定是否调用更多工具。

---

## 数据流图

```
query (字符串)
  │
  ├─ Step 1: ExploreOutputBudget → budget {maxFiles, maxOutputChars}
  │
  ├─ Step 2: ContextBuilder.findRelevantContext()
  │     ├─ extractSymbols → ["Token1", "Token2"]
  │     ├─ Exact Match → nodes
  │     ├─ Prefix Match → nodes
  │     └─ BFS Expansion → Subgraph {nodes: Set<Node>, edges: Set<Edge>, roots: Set<String>}
  │
  ├─ Step 3: glueNodeIds (≤60, 同文件 callers/callees)
  │
  ├─ Step 4: namedSeedIds (≤40, extractSymbols + 三级搜索)
  │
  ├─ Step 5: FileGroup {score, nodes} → relevantFiles (filtered)
  │
  ├─ Step 6: RWR relevance + term hits → centralFiles, gatedFiles
  │
  ├─ Step 7: 6-tier sort → sortedFiles
  │
  └─ Step 8: 3 rendering strategies → Markdown output
        ├─ BlastRadius
        ├─ Relationships
        └─ Source code blocks (skeleton / whole file / clustered)
```

---

## 关键设计决策总结

| 决策 | 理由 |
|------|------|
| 8 步流水线而非一步搜索 | 每一步解决一个特定问题，各步骤可独立调优 |
| 精确匹配 → 前缀匹配 → 模糊匹配的分层搜索 | 平衡精确度(Precision)与召回率(Recall) |
| 图扩展限制 3 层、200 节点 | 防止图遍历爆炸 |
| 同文件粘合 ≤ 60 节点 | 补齐局部上下文，不引入无关文件 |
| 命名播种 ≤ 40 节点 | 跨文件补全，但限制总量 |
| 文件评分 + 3 分门槛 | 过滤噪音节点，只保留有实质关系的文件 |
| RWR 替代简单边计数 | 更精准的图相关性度量 |
| 相关性门控 6% 阈值 | 保留至少 2 个文件，防止过度过滤 |
| 6 级排序 | 多层排序确保最重要的文件在最前 |
| 3 种渲染策略 | 根据文件特征选择最优输出方式，平衡信息量与预算 |
| 完整性信号 + 预算说明 | 让 AI 了解输出是否完整，决定是否需要进一步探索 |
