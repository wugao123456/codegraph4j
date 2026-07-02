# codegraph_explore 核心流程详解

> 实现文件：[MCPToolHandler.java#L413-L823](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/mcp/MCPToolHandler.java#L413-L823)
> 搜索管道：[ContextBuilder.java#L89-L482](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/context/ContextBuilder.java#L89-L482)
> 搜索工具：[SearchUtils.java](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/context/SearchUtils.java)
> 数据库查询：[QueryBuilder.java#L704-L798](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/db/QueryBuilder.java#L704-L798)

## 概述

`codegraph_explore` 是 CodeGraph MCP 工具集中最核心的工具，它接收自然语言查询（中英文混合），通过 **外层 8 步流水线 + 内层 6 步搜索管道** 将代码知识图谱中的信息压缩为结构化 Markdown 输出，供 AI 助手理解代码库。

**设计目标**：在有限 token 预算内，最大化返回与用户查询最相关的代码段和关系信息。

---

## 输入/输出

| | 内容 |
|---|---|
| **输入** | 自然语言查询字符串，如 `"MCPToolHandler 探索流程的搜索和渲染逻辑"` |
| **输出** | Markdown 格式的结构化响应，包含代码片段(带行号)、爆炸半径、关系图、完整性信号、预算说明 |

---

## 外层流水线（8 步）

```
query (字符串)
  │
  ├─ Step 1: ExploreOutputBudget → budget {maxFiles, maxOutputChars}
  │
  ├─ Step 2: ContextBuilder.findRelevantContext()  ← 内层 6 步搜索管道（详见下文）
  │     └─ Subgraph {nodes, edges, roots}
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

### Step 1: 自适应输出预算

**位置**：[MCPToolHandler.java#L419-L424](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/mcp/MCPToolHandler.java#L419-L424)

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

### Step 2: 混合搜索获取子图（内层 6 步搜索管道）

**位置**：[ContextBuilder.java#L89-L482](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/context/ContextBuilder.java#L89-L482)

这是整个 explore 流程中最核心的部分。`findRelevantContext()` 内部实现了一个 **分数驱动的 6 步搜索管道**，所有步骤产生的候选结果统一用 `SearchResult(node, score)` 排序，保证多通道结果可比。

#### 整体设计理念

传统搜索方法各有利弊：精确匹配精准但召回低，模糊匹配召回高但噪音大，前缀匹配适合类型定义但不适合方法名。**分数驱动管道** 的核心思想是让所有搜索通道的产出通过统一的 score 排序，让"真正相关"的结果自然浮到顶端——无论它们来自哪个通道。

#### 管道全景

```
query = "MCPToolHandler handleExplore search"
     │
     ├─ Step 2.1: extractSymbols() → ["MCPToolHandler", "handleExplore", "search"]
     │
     ├─ Step 2.2: Exact Match（精确匹配）score: 70-80
     │     getNodesByQualifiedName / getNodesByName
     │     + co-location boost（同文件多符号 +20/符号）
     │
     ├─ Step 2.3: Prefix Match（前缀匹配 + 词干变体）score: 15-25
     │     searchNodes with TitleCase + stem variants
     │     + brevityBonus（偏爱短类名）
     │     → 匹配: MCPToolHandler, MCPToolHandlerTest
     │
     ├─ Step 2.4: FTS 文本搜索（多词累计 boost）score: 10-25
     │     extractSearchTerms → 提取搜索词 + 词干变体
     │     逐词搜索 → 多词命中 boost（每多一词 +5）
     │     → 匹配: SearchResult, SearchUtils, handleExplore
     │
     ├─ Step 2.5a: 多术语共现重排序
     │     词项分组（子串视为同一概念）
     │     多词命中 multiplicative boost（2词→2x, 3词→2.5x）
     │     单词 dampen（0.3-0.6x）
     │
     ├─ Step 2.5b: CamelCase 边界匹配（LIKE 子串查询）score: 8-40+
     │     findNodesByNameSubstring + CamelCase 边界检测
     │     多词命中 boost：score × (1 + nTerms) + (nTerms-1) × 30
     │     → "Search" 匹配 TransportSearchAction
     │
     ├─ Step 2.5c: 复合词匹配 score: 10-70+
     │     ≥2 个 query term 出现在同一类名中
     │     → "handle" + "search" 匹配 HandleSearchRequest
     │
     └─ Step 2.6: BFS Graph Expansion
           从 root 节点出发，沿语义边扩展 3 层
```

---

#### Step 2.1: 符号提取

**位置**：[ContextBuilder.java#L590-L614](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/context/ContextBuilder.java#L590-L614)

**做什么**：从查询字符串中提取可识别的代码符号名。

**实现**：

```java
// 分词 → 清理扩展名 → 验证标识符模式 → 限制 16 个
String[] parts = query.split("[\\s,()\\[\\]]+");
for (String raw : parts) {
    String t = FILE_EXT_PATTERN.matcher(raw).replaceAll("").trim();
    if (t.length() >= 3 && VALID_TOKEN_PATTERN.matcher(t).matches()) {
        symbols.add(t);
    }
}
```

支持格式：`UserService`, `com.codegraph.UserService`, `UserService.login`, `User::getName`

---

#### Step 2.2: 精确匹配（Exact Match）

**位置**：[ContextBuilder.java#L118-L165](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/context/ContextBuilder.java#L118-L165)

**做什么**：按完全限定名和简单名在数据库中精确匹配，这是最高置信度的搜索通道。

**评分规则**：

| 匹配方式 | 基础分 | 说明 |
|---------|--------|------|
| `qualified_name` 精确匹配 | **80** | 最高置信度 |
| `name` 精确匹配 | **70** | 高置信度 |
| Co-location boost | **+20 / 额外符号** | 同文件多符号命中表示高度相关 |

**Co-location boost 详解**：如果同一文件中匹配到了多个 query 符号，说明这个文件高度相关。例如查询 `"MCPToolHandler handleExplore"`，如果 `MCPToolHandler.java` 中同时匹配到了 `MCPToolHandler`（类）和 `handleExplore`（方法），则该文件的每个匹配节点额外加 20 分。

```java
// 统计每个文件中被命中的不同符号名
Map<String, Set<String>> fileSymbolCounts = new HashMap<>();
for (SearchResult sr : searchResults) {
    fileSymbolCounts.computeIfAbsent(sr.node.getFilePath(), k -> new HashSet<>())
        .add(sr.node.getName().toLowerCase());
}
// 同文件多符号 → 加权
for (SearchResult sr : searchResults) {
    int symbolCount = fileSymbolCounts.get(sr.node.getFilePath()).size();
    if (symbolCount > 1) sr.score += (symbolCount - 1) * 20;
}
```

**为什么这样写**：

精确匹配是搜索的基石。高置信度的结果必须排在前面，这是 AI 助手最需要的。co-location boost 利用了代码的结构特性：相关符号通常聚集在同一文件中。如果一个文件同时命中多个搜索词，它几乎肯定是用户要找的。

**截断策略**：精确匹配结果最多保留 `searchLimit × 2`（默认 16 个），防止某个通用词（如 `"User"`）匹配过多结果。

---

#### Step 2.3: 前缀匹配（Prefix Match + 词干变体）

**位置**：[ContextBuilder.java#L167-L199](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/context/ContextBuilder.java#L167-L199)

**做什么**：对类型定义（class/interface/enum 等）进行 Title-Case 前缀匹配，并使用词干变体扩展搜索范围。

**实现**：

```java
for (String sym : symbols) {
    // 1. 扩展词干变体："building" → ["build", "builde"]
    Set<String> expandedSyms = new LinkedHashSet<>();
    expandedSyms.add(sym);
    for (String variant : SearchUtils.getStemVariants(sym)) {
        expandedSyms.add(variant);
    }
    for (String expandedSym : expandedSyms) {
        // 2. Title-case 转换 → 只搜索类型定义
        String titleCased = expandedSym.substring(0, 1).toUpperCase()
            + expandedSym.substring(1).toLowerCase();
        List<Node> prefixResults = queries.searchNodes(titleCased);
        for (Node n : prefixResults) {
            // 3. 只保留真正以 titleCased 开头的定义类型节点
            if (!DEFINITION_KINDS.contains(n.getKind().name())) continue;
            if (!n.getName().toLowerCase().startsWith(titleCased.toLowerCase())) continue;
            // 4. brevityBonus: 偏爱短类名
            double brevityBonus = Math.max(0, 10 - (n.getName().length() - titleCased.length()) / 3.0);
            searchResults.add(new SearchResult(n, 15 + brevityBonus));
        }
    }
}
```

**词干变体**（[SearchUtils.java#L46-L109](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/context/SearchUtils.java#L46-L109)）：

| 后缀 | 变体示例 | 说明 |
|------|---------|------|
| `-ing` | caching → cach, cache | 去除进行时 |
| `-tion/-sion` | eviction → evict | 名词化还原 |
| `-ment` | management → manage | 名词化还原 |
| `-ies` | entries → entry | 复数还原 |
| `-es/-s` | processes → process | 复数还原 |
| `-ed` | handled → handle, handl | 过去式还原 |
| `-er` | builder → build, builde | 施动者还原 |

**为什么需要词干变体**：

用户的查询往往是自然语言描述，如 `"缓存构建逻辑"`。精确匹配 `"缓存"` 可能找到 `CacheManager`，但找不到 `CachingStrategy` 或 `CacheBuilder`。词干变体通过去除英语后缀来扩展搜索范围：`"caching"` 的变体 `"cach"` 和 `"cache"` 可以匹配到 `CacheBuilder`（前缀 `Cache`）和 `CachingStrategy`（前缀 `Cach`）。

**brevityBonus（简洁度奖励）**：

```java
double brevityBonus = Math.max(0, 10 - (name.length() - titleCased.length()) / 3.0);
```

偏好短类名。例如 `"Cache"` (5 字符) 匹配 `CacheManager` (12 字符)，bonus = 10 - (12-5)/3 ≈ 7.7；匹配 `CacheInvalidationStrategy` (25 字符)，bonus = 10 - (25-5)/3 ≈ 3.3。短类名通常更可能是核心类。

**示例**：

```
查询 "building" → 词干变体 ["build", "builde"]
  → TitleCase: "Build", "Builde"
  → 前缀匹配: Builder, BuildContext, BuildingConfig...
  → brevityBonus: Builder(7字符→bonus≈9) > BuildConfiguration(18字符→bonus≈5.7)
```

---

#### Step 2.4: FTS 文本搜索（多词累计 Boost）

**位置**：[ContextBuilder.java#L201-L249](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/context/ContextBuilder.java#L201-L249)

**做什么**：使用 `extractSearchTerms` 从查询中提取搜索词（含词干变体），逐词进行数据库 LIKE 搜索，累计多词命中的 boost。

**实现**：

```java
List<String> searchTerms = SearchUtils.extractSearchTerms(query);
// 逐词搜索，累计多词命中
Map<String, double[]> termResultsMap = new LinkedHashMap<>(); // nodeId → [maxScore, termHits]
for (String term : searchTerms) {
    List<Node> termResults = queries.searchNodes(term,
        options.searchLimit * 3, searchKinds.toArray(new String[0]));
    for (Node n : termResults) {
        double[] entry = termResultsMap.get(n.getId());
        if (entry == null) {
            entry = new double[]{0, 1};  // 首次命中：score=0, hits=1
        } else {
            entry[1]++;  // 多词命中计数递增
        }
        entry[0] = Math.max(entry[0], 10);  // 保持最高基础分
    }
}
// 多词命中 boost
for (Map.Entry<String, double[]> e : termResultsMap.entrySet()) {
    double score = v[0] + (v[1] - 1) * 5;  // 每多一个 term 匹配 +5
}
```

**搜索词提取**（[SearchUtils.java#L129-L193](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/context/SearchUtils.java#L129-L193)）：

```
query = "handleExplore search logic in MCPToolHandler"
     │
     ├─ 1. 提取复合标识符 (CamelCase): "handleExplore", "MCPToolHandler"
     ├─ 2. 提取 snake_case: (none)
     ├─ 3. CamelCase → 单词序列: "Handle Explore"
     ├─ 4. 替换 _ 和 . 为空格
     ├─ 5. 按非字母数字拆分: ["handle", "explore", "search", "logic", "in", "MCPTool", "Handler"]
     ├─ 6. 过滤停用词: 移除 "in", "the", "a", "logic"...
     ├─ 7. 生成词干变体: "search"→[], "explore"→[], "handle"→["handl"]
     └─ 结果: ["handleexplore", "mcptoolhandler", "handle", "explore",
               "search", "mcptool", "handler", "handl"]
```

**为什么需要 FTS 文本搜索**：

精确匹配和前缀匹配只能找到"名字完全匹配"的符号。但用户的查询 `"搜索缓存清理逻辑"` 中，`"缓存"` 可能对应代码中的 `CacheEvictionManager`，`"清理"` 对应 `purgeEntries`。它们之间没有字符串重叠——但通过 FTS 文本搜索，每个词在数据库中的 `name`、`qualified_name`、`docstring` 字段做 LIKE 子串匹配，可以跨命名边界找到关联。

**为什么需要多词累计 boost**：

单词匹配噪音大（`"User"` 可能匹配到上百个 User 相关类）。但当 `"User"` 和 `"Login"` 同时命中 `UserLoginService` 时，它是极大概率正确的。多词命中 boost 让"同时匹配多个搜索词"的结果得分更高，自然排在前面。

**排除 import 节点**：FTS 搜索时只搜索定义类型节点（class/interface/enum/method/function 等），排除 IMPORT/EXPORT 节点，因为它们数量大且噪音高。

---

#### Step 2.5a: 多术语共现重排序

**位置**：[ContextBuilder.java#L260-L332](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/context/ContextBuilder.java#L260-L332)

**做什么**：当查询包含 2 个以上搜索词时，对候选结果按多词共现情况进行 boost 或 dampen。

**核心逻辑**：

```java
// 1. 词项分组：子串关系的词视为同一概念
//    "cache" ⊂ "caching" → 归入同一组
//    "search" 独立一组 → 共 2 组

// 2. 对每个候选结果，统计匹配了多少个不同的词项组
for (SearchResult sr : searchResults) {
    int matchCount = 0;
    for (List<String> group : termGroups) {
        // 该组中有任意一个词在节点名或目录段中匹配 → 算作命中
        if (nameLower.contains(term) || dirSegMatch(term)) {
            matchCount++;
            break;
        }
    }

    // 3. 根据匹配组数调整分数
    if (matchCount >= 2) {
        sr.score *= (1 + matchCount * 0.5);  // 2词→2x, 3词→2.5x
    } else if (distinctiveExactMatch) {
        // 区分性标识符精确匹配，保持原分
    } else if (sr.score >= 70) {
        sr.score *= 0.3;  // 普通词精确匹配无佐证→降权
    } else {
        sr.score *= 0.6;  // 单术语→温和降权
    }
}
```

**词项分组**的设计：

```
searchTerms = ["cache", "caching", "search", "builder"]
                  │          │        │         │
                  └──┬───────┘        │         │
               group1: cache        group2    group3
               (子串关系合并)
```

`"cache"` 和 `"caching"` 在概念上相同（一个是名词，一个是进行时），通过词干变体已经产生了重叠。将它们归入同一组，避免对"同一概念的不同变体"重复计数，导致 boost 虚高。

**区分性标识符豁免**（[SearchUtils.java#L309-L316](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/context/SearchUtils.java#L309-L316)）：

```java
public static boolean isDistinctiveIdentifier(String token) {
    if (token.matches(".*[_0-9].*")) return true;  // snake_case / 含数字
    if (token.substring(1).matches(".*[A-Z].*")) return true;  // CamelCase 边界
    return false;
}
```

如果一个 token 看起来像代码标识符（如 `"MCPToolHandler"`）而非普通英文单词（如 `"search"`），则它获得"精确匹配豁免"——即使没有其他词佐证，也不降权。因为用户明确写了一个类名，说明他确实在找这个类。

**为什么这样写**：

纯粹靠基础分排序可能把"单术语模糊匹配的弱相关结果"排在"精确术语匹配但无多词佐证的结果"前面。多术语重排序的核心思想是：**多个不同概念的词同时命中 → 高置信度；单个词命中 → 需要更多上下文佐证**。

**示例**：

```
查询: "cache eviction builder"

候选结果:
├─ CacheEvictionBuilder  → name命中 "cache"+"eviction"+"builder" → 3组 → ×2.5 → 高分
├─ CacheManager          → name命中 "cache" → 1组 → ×0.6 → 降权
├─ EvictionPolicy        → name命中 "eviction" → 1组 → ×0.6 → 降权
└─ MCPToolHandler        → 精确匹配(score=70) 但无其他词佐证 → ×0.3 → 大幅降权
```

---

#### Step 2.5b: CamelCase 边界匹配（LIKE 子串查询）

**位置**：[ContextBuilder.java#L334-L391](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/context/ContextBuilder.java#L334-L391)

**做什么**：通过 LIKE 子串查询找到"名字中包含搜索词的复合类名"，并检测 CamelCase 边界以确保匹配的是独立的单词部分。

**实现**：

```java
// 对每个符号搜索 LIKE '%TitleCase%' 但不是 LIKE 'TitleCase%'
List<Node> likeResults = queries.findNodesByNameSubstring(titleCased, 200,
    DEFINITION_KINDS.toArray(new String[0]), true);  // excludePrefix=true

for (Node n : likeResults) {
    String name = n.getName();
    int idx = name.indexOf(titleCased);
    if (idx <= 0) continue;  // 必须是中间匹配（前缀已被 Step 2.3 覆盖）
    // CamelCase 边界检测：匹配位置前一个字符必须是字母
    if (idx > 0 && !Character.isLetter(name.charAt(idx - 1))) continue;
    // ...
}
```

**CamelCase 边界检测详解**：

```
搜索词: "Search"
  ├─ TransportSearchAction  → idx=9, name[8]='t' (字母) ✓ 匹配！
  ├─ SearchManager          → idx=0 (前缀) → 跳过（Step 2.3 已覆盖）
  ├─ BinarySearchTree       → idx=6, name[5]='y' (字母) ✓ 匹配！
  └─ TSearcher              → idx=1, name[0]='T' → "Search"在"TSearcher"中不是独立词 → 仍然匹配（都是字母）
```

**评分规则**：

```java
int pathScore = SearchUtils.scorePathRelevance(n.getFilePath(), query);
double brevityBonus = Math.max(0, 6 - (name.length() - titleCased.length()) / 4.0);
double score = 8 + brevityBonus + pathScore;

// 多词命中 boost
double finalScore = score * (1 + nTerms) + (nTerms - 1) * 30;
```

**路径相关性评分**（[SearchUtils.java#L205-L253](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/context/SearchUtils.java#L205-L253)）：

| 匹配位置 | 得分 | 含义 |
|---------|------|------|
| 文件名中匹配 | **+10** | 最相关 |
| 目录名中匹配 | **+5** | 较相关 |
| 路径中匹配 | **+3** | 弱相关 |
| 测试文件 | **-15** | 降权（除非查询提到了 test） |

**为什么需要 CamelCase 边界匹配**：

前缀匹配（Step 2.3）只能找到 `Search*` 开头的类名。但 Java 中大量使用复合命名：`TransportSearchAction`、`ElasticsearchHealthIndicator`、`BinarySearchTree`。用户的查询 `"search"` 理论上应该匹配到这些类——它们确实和 search 相关，只是 search 在名字中间。

CamelCase 边界匹配填补了前缀匹配的盲区。

**示例**：

```
查询: "search"

Step 2.3 前缀匹配结果:
  SearchManager, SearchUtils, SearchController

Step 2.5b CamelCase 匹配结果:
  TransportSearchAction    → "Search" 在中间，CamelCase 边界 ✓
  BinarySearchTree         → "Search" 在中间
  ElasticsearchHealthIndicator → "search" 在小写部分，也是有效的 CamelCase 边界
  FullTextSearchService    → 同上
```

---

#### Step 2.5c: 复合词匹配

**位置**：[ContextBuilder.java#L394-L439](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/context/ContextBuilder.java#L394-L439)

**做什么**：当查询有 2 个以上符号时，找出同时包含多个查询词的复合类名。

**实现**：

```java
// 对每个符号，做 LIKE 子串查询（不排除前缀）
for (String sym : symbols) {
    List<Node> likeResults = queries.findNodesByNameSubstring(titleCased, 200,
        DEFINITION_KINDS.toArray(new String[0]), false);  // excludePrefix=false
    for (Node n : likeResults) {
        // 累计该节点被多少个不同的查询词命中
        compoundTermMap.computeIfAbsent(n.getId(), k -> new double[]{0, 1});
        // or increment termCount
    }
}

// 只保留被 ≥2 个词命中的节点
for (Map.Entry<String, double[]> e : compoundTermMap.entrySet()) {
    if (v[1] < 2) continue;  // 至少匹配 2 个不同词
    double score = 10 + (v[1] - 1) * 20 + pathScore + brevityBonus;
}
```

**为什么需要复合词匹配**：

CamelCase 边界匹配（Step 2.5b）一次只搜索一个词。当用户输入 `"handle search"` 时，Step 2.5b 会分别搜索 `"Handle"` 和 `"Search"`，产生两组独立结果。但 `HandleSearchRequest` 这个类可能两组的分数都不够高，被截断掉了。

复合词匹配通过检测"同一类名被 ≥2 个查询词命中"来发现这种交叉信号，给这类结果一个额外的 boost。

**示例**：

```
查询: "handle explore search"

Step 2.5b 各词独立搜索:
  "Handle" → HandleResolver, ErrorHandler, ...
  "Explore" → ExploreResult, ...
  "Search" → SearchManager, ...

Step 2.5c 复合词匹配:
  HandleSearchRequest   ← "Handle"+"Search" 同时命中 → score=10+20+pathScore
  SearchExploreService  ← "Search"+"Explore" 同时命中
```

---

#### Step 2.6: 统一排序截断 → BFS 图扩展

**位置**：[ContextBuilder.java#L441-L481](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/context/ContextBuilder.java#L441-L481)

**做什么**：所有搜索通道的结果按 score 统一排序，截断到 `searchLimit × 3`，然后以这些节点为入口做 BFS 图扩展。

```java
// 统一排序
searchResults.sort((a, b) -> Double.compare(b.score, a.score));
// 截断
if (searchResults.size() > options.searchLimit * 3) {
    searchResults = new ArrayList<>(searchResults.subList(0, options.searchLimit * 3));
}
// 最低分过滤
for (SearchResult sr : searchResults) {
    if (sr.score >= options.minScore) filtered.add(sr);
}
// 入口点数量上限
if (filtered.size() > options.searchLimit) {
    filtered = new ArrayList<>(filtered.subList(0, options.searchLimit));
}

// BFS 图扩展（从 root 节点出发，沿语义边扩展 traversalDepth 层）
for (String rootId : allRootIds) {
    GraphTraverser.Subgraph sg = traverser.traverseBFS(rootId, tOpts);
    for (Node n : sg.nodes.values()) result.addNode(n);
    for (Edge e : sg.edges) result.addEdge(e);
}
```

**图扩展边类型**：

```java
CALLS, REFERENCES, EXTENDS, IMPLEMENTS, OVERRIDES,
INSTANTIATES, RETURNS, TYPE_OF, IMPORTS
```

排除 CONTAINS（AST 父子关系），只保留语义关系边。

**为什么分数驱动的多通道统一排序**：

不同搜索通道的原始结果质量不同。精确匹配的结果几乎肯定正确（score 70-80），FTS 的结果可能噪音较大（score 10），CamelCase 匹配可能很精准（score 30+）。通过赋予不同通道合理的基准分，所有结果可以在统一的分数量表上比较，真正相关的节点自然排在前面。

**为什么需要 BFS 图扩展**：

搜索只找到了"入口节点"。但理解代码需要上下文——谁调用了这个方法？这个类继承了谁？它引用了哪些类型？BFS 图扩展从入口节点出发沿语义边展开，补全这些关系信息。

---

### Step 3: 图感知粘合（Glue Nodes）

**位置**：[MCPToolHandler.java#L440-L469](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/mcp/MCPToolHandler.java#L440-L469)

**做什么**：对每个 root 节点，查找其在 **同文件内** 的 callers 和 callees，填补子图中的"空隙"。

**实现**：

```java
for (String rootId : subgraph.roots) {
    List<CallerInfo> callers = traverser.getCallers(rootId, 1);
    List<CalleeInfo> callees = traverser.getCallees(rootId, 1);
    // 只注入同文件内的节点，最多 60 个
    if (subgraphFiles.contains(ci.node.getFilePath())) {
        subgraph.addNode(ci.node);
        glueNodeIds.add(ci.node.getId());
    }
}
```

**为什么这样写**：

Step 2 的图扩展可能跳过了同文件内的"中间节点"。举个例子：

```
文件: MCPToolHandler.java
  handleExplore()         ← Step 2 找到了
      │ CALLS
      ▼
  renderSkeleton()        ← Step 2 没找到（因为不是直接 root）
      │ CALLS
      ▼
  numberSourceLines()     ← Step 2 可能也找到了
```

`renderSkeleton()` 虽然很重要，但在图扩展中可能被跳过。粘合操作确保同文件内的中间节点也被补全，形成一个更完整的"局部上下文"。

**限制**：
- 只补充同文件内的节点，避免引入无关文件
- 最多 60 个粘合节点，防止大文件消耗过多预算

---

### Step 4: 命名符号播种（Named Seed）

**位置**：[MCPToolHandler.java#L471-L491](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/mcp/MCPToolHandler.java#L471-L491)

**做什么**：从查询中提取 token，在 **全项目范围** 内搜索这些符号，注入子图。

**三级降级搜索**：

```java
getNodesByQualifiedName(token)  // 1. 精确全限定名
    → getNodesByName(token)      // 2. 简单名
    → searchNodes(token)         // 3. 模糊搜索
```

**为什么这样写**：

Step 3 只补全同文件内的节点，但用户可能提到跨文件的符号。命名播种确保这些符号对应的文件也被加入子图。最多 40 个种子节点。

---

### Step 5: 文件分组 + 评分

**位置**：[MCPToolHandler.java#L493-L542](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/mcp/MCPToolHandler.java#L493-L542)

**做什么**：将子图中的节点按文件分组，给每组打分，过滤低价值和测试文件。

**评分规则**：

| 节点类型 | 得分 | 含义 |
|---------|------|------|
| namedSeed（用户名称的符号） | +50 | 最相关 |
| entry（搜索入口节点） | +10 | 高度相关 |
| connectedToEntry（与 entry 直接相连） | +3 | 一定相关 |
| 其他 | +1 | 弱相关 |

**过滤规则**：
1. 跳过 import/export 节点
2. 跳过配置文件叶子节点
3. 只保留 score ≥ 3 的文件
4. 非测试查询时排除测试文件

**为什么这样写**：

评分 + 过滤是**质量守门员**。没有这步，结果中会充斥大量无关的"噪音节点"。

---

### Step 6: RWR 图相关性 + 文本命中

**位置**：[MCPToolHandler.java#L544-L604](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/mcp/MCPToolHandler.java#L544-L604)

**做什么**：通过 Random Walk with Restart (RWR) 算法 + 文本命中统计，精细计算每个文件与查询的相关性，进行门控淘汰。

**RWR 原理**：从 entry 节点出发，随机游走。每次有 α=0.15 的概率"重新开始"，0.85 的概率沿边走到邻居。迭代收敛后，每个节点得到一个稳定概率分布分数。

**门控规则**（文件必须满足以下之一才保留）：
1. 图分数 ≥ maxGraph × 0.06
2. 是 centralFile
3. 是 entryFile
4. term 命中 ≥ 2

**为什么这样写**：

RWR 比简单的"边计数"更准确——不会因为一个文件有很多边就被错误地高估。文本命中补全图相关性盲区——如果某个文件因为路径中断没在图里出现，但文件名匹配了查询词，可以"挽救"它。门控阈值 6% 在敏感度和噪音控制之间取得平衡。

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

**位置**：[MCPToolHandler.java#L606-L639](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/mcp/MCPToolHandler.java#L606-L639)

| 优先级 | 排序条件 | 含义 |
|--------|---------|------|
| **Tier 1** | namedSeed 所在文件 | 用户明确提到的符号排最前 |
| **Tier 2** | corroborate（entry/central + ≥2 term 命中） | "强证据"文件 |
| **Tier 3** | 图相关性分数 | RWR 分数高的排前面 |
| **Tier 4** | term 命中数 | 查询词命中多的排前面 |
| **Tier 5** | 低价值文件降权 | 测试文件、配置文件排后面 |
| **Tier 6** | 生成文件降权 | 自动生成的代码排最后 |

**为什么这样写**：AI 助手只会阅读输出中前几个代码段。排序决定了 AI"先看到什么"。

---

### Step 8: 构建输出（3 种渲染策略）

**位置**：[MCPToolHandler.java#L641-L800](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/mcp/MCPToolHandler.java#L641-L800)

**输出结构**：

```markdown
**Exploration: <query>**
Found N symbols across M files.

**Blast radius**
- `SymbolName` — 3 callers in ...

**Relationships**
CALLS:
- handleExplore → writeExploreLog

**Source Code**
```java
// 带行号的源码
```
```

**三种渲染策略**：

| 策略 | 触发条件 | 输出方式 |
|------|---------|---------|
| **A: 骨架化** | 多态兄弟类 或 神主文件 | 只输出方法签名，省略方法体 |
| **B: 整文件** | 行数 ≤220（central ≤280）且预算够 | 完整源码 + 行号 |
| **C: 聚类分组** | 大文件不满足上述条件 | 只输出相关的代码块 |

**为什么需要三种策略**：不同文件大小和重要性不同，一种渲染方式无法适应所有情况。骨架化节省 token 但保留结构信息，整文件输出提供最完整上下文，聚类分组在噪音和信号之间取得平衡。

---

## 数据流图

```
query (字符串)
  │
  ├─ Step 1: ExploreOutputBudget → budget
  │
  ├─ Step 2: ContextBuilder.findRelevantContext()
  │     ├─ Step 2.1: extractSymbols → ["Token1", "Token2"]
  │     ├─ Step 2.2: Exact Match (score: 70-80 + co-location boost)
  │     ├─ Step 2.3: Prefix Match + Stem Variants (score: 15-25 + brevityBonus)
  │     ├─ Step 2.4: FTS Text Search (score: 10-25, multi-term boost +5/term)
  │     ├─ Step 2.5a: 多术语共现重排序 (multi-term boost/dampen)
  │     ├─ Step 2.5b: CamelCase 边界匹配 (score: 8-40+, LIKE 子串查询)
  │     ├─ Step 2.5c: 复合词匹配 (score: 10-70+, ≥2 terms 同一类名)
  │     ├─ 统一排序截断 → 最多 searchLimit 个入口
  │     └─ Step 2.6: BFS Graph Expansion → Subgraph
  │
  ├─ Step 3: glueNodeIds (≤60, 同文件 callers/callees)
  │
  ├─ Step 4: namedSeedIds (≤40, 三级搜索全项目播种)
  │
  ├─ Step 5: FileGroup 评分 + 过滤
  │
  ├─ Step 6: RWR relevance + 门控淘汰
  │
  ├─ Step 7: 6-tier 排序
  │
  └─ Step 8: 3 种渲染策略 → Markdown 输出
```

---

## 关键设计决策总结

| 决策 | 理由 |
|------|------|
| 外层 8 步 + 内层 6 步的双层流水线 | 外层负责预算管理和输出渲染，内层负责搜索质量 |
| 分数驱动的多通道统一排序 | 精确匹配、前缀匹配、FTS、CamelCase 的结果在同一分数量表上可比 |
| 精确匹配 → 前缀匹配 → FTS → CamelCase → 复合词的分层搜索 | 从高精准到高召回逐步扩展，每层填补前一层的盲区 |
| Co-location boost（同文件多符号 +20/符号） | 代码中相关符号聚集在同一文件，多符号命中几乎肯定正确 |
| 词干变体扩展（7 种后缀） | 弥合自然语言和代码命名的形态差异（caching → cache） |
| FTS 多词累计 boost | 单词匹配噪音大，多词同时命中的置信度指数级上升 |
| 多术语共现重排序（multi-term boost/dampen） | 多概念交叉验证提升置信度，单术语无佐证温和降权 |
| 区分性标识符豁免 | 用户明确写的类名不应因缺佐证而降权 |
| CamelCase 边界匹配（LIKE '%Term%'） | 填补前缀匹配的盲区，匹配复合类名中间部分 |
| 复合词匹配（≥2 terms 同命中） | 发现跨词交叉信号，捕捉独立搜索通道遗漏的结果 |
| 测试文件降权（×0.3，除非查询提到 test） | 非测试查询中，测试代码通常是噪音 |
| brevityBonus + pathScore | 短类名通常更核心，路径名匹配提供额外上下文信号 |
| 图扩展限制 3 层、200 节点 | 防止图遍历爆炸 |
| 同文件粘合 ≤60 节点 | 补齐局部上下文，不引入无关文件 |
| 命名播种 ≤40 节点 | 跨文件补全，但限制总量 |
| RWR 替代简单边计数 | 更精准的图相关性度量 |
| 相关性门控 6% 阈值 | 至少保留 2 个文件，防止过度过滤 |
| 6 级排序 | 多层排序确保最重要的文件在最前 |
| 3 种渲染策略 | 根据文件特征选择最优输出方式，平衡信息量与预算 |
| 完整性信号 + 预算说明 | 让 AI 了解输出是否完整，决定是否需要进一步探索 |
