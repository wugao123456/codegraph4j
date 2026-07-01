# codegraph_explore 中文友好改进方案

## 1. 现状分析

### 1.1 问题链路

```
用户输入: "IndexCommand 索引流程的完整调用链和核心步骤"
                    │
                    ▼
  extractSymbols() → split → ["IndexCommand", "索引流程的完整调用链和核心步骤"]
                    │
                    ▼
  VALID_TOKEN_PATTERN = "^[A-Za-z_$][\\w$]*(?:(?:::|\\.)[\\w$]+)*$"
  "IndexCommand" ✅ 匹配
  "索引流程的完整调用链和核心步骤" ❌ 不匹配 → 静默丢弃
                    │
                    ▼
  symbols = ["IndexCommand"] → exact match → 只搜 IndexCommand
  中文意图完全丢失 → 结果: 1 symbol, 0 files
```

**根因**：[ContextBuilder.extractSymbols()](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/context/ContextBuilder.java#L341-L365) 的 `VALID_TOKEN_PATTERN` 只匹配 ASCII 标识符，中文等 Unicode 字符被静默丢弃。

### 1.2 现有降级路径的局限

```java
// ContextBuilder.java L102-L112
if (symbols.isEmpty()) {
    // 降级为 searchNodes(query) — 全文模糊搜索
    List<Node> textResults = queries.searchNodes(query);
    ...
}
```

- 只在 `symbols` **完全为空**时触发（纯中文查询）
- 中英混合查询（如 `"IndexCommand 索引流程"`）不会触发降级，中文部分被丢弃
- `searchNodes` 在 `name`/`qualified_name`/`docstring` 中做 `LIKE %query%` 模糊匹配，代码库中很少有中文内容，几乎匹配不到

### 1.3 受影响场景

| 查询类型 | 示例 | 现状 |
|---------|------|------|
| 纯英文 | `"MCPToolHandler handleExplore"` | ✅ 正常工作 |
| 纯中文 | `"索引流程"` | ❌ 降级到 text search，几乎 0 结果 |
| 中英混合 | `"IndexCommand 索引流程"` | ❌ 中文丢弃，英文 token 太少匹配效果差 |

---

## 2. 方案设计

三个方案按复杂度递进排列，可独立或组合实施。

### 方案 A：分层搜索增强（推荐优先实施）

**思路**：不修改 `extractSymbols` 逻辑，在 `findRelevantContext()` 中增加一个**中文感知的文本搜索层**作为补充。

**改动文件**：仅 [ContextBuilder.java](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/context/ContextBuilder.java)

**改动点**：

```java
// 在 findRelevantContext() 方法中，symbols 提取之后
// 新增：检测中文内容，作为补充搜索

// 1. 新增常量：检测中文字符的正则
private static final Pattern CHINESE_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]+");

// 2. 新增方法：提取中文子串
private List<String> extractChineseTerms(String query) {
    List<String> terms = new ArrayList<>();
    Matcher m = CHINESE_PATTERN.matcher(query);
    while (m.find()) {
        String term = m.group();
        if (term.length() >= 2) terms.add(term);  // 跳过单字
    }
    return terms;
}

// 3. 在 findRelevantContext() 中，增加中文搜索层
// 位置：Step 2 Exact match 之后、Step 3 Prefix match 之前
List<String> chineseTerms = extractChineseTerms(query);
if (!chineseTerms.isEmpty()) {
    // 用中文词做文本搜索作为补充
    for (String ct : chineseTerms) {
        List<Node> textResults = queries.searchNodes(ct);
        for (Node n : textResults) {
            if (!result.nodes.containsKey(n.getId())) {
                result.addNode(n);
            }
        }
    }
}
```

**优点**：
- 单一文件改动，影响面小
- 不破坏现有符号匹配逻辑
- 中文词单独做 text search，命中 docstring 中的中文注释
- 当 docstring 中有中文描述时（如 `/** 用户认证服务 */`），可匹配到

**缺点**：
- 依赖代码库中有中文注释/docstring
- 中文词 vs 英文标识符之间的语义映射仍然缺失，纯英文代码库效果有限

---

### 方案 B：关键词映射增强

**思路**：在方案 A 基础上，增加一个中→英关键词映射字典，将常见中文技术术语转为英文搜索关键词。

**改动文件**：
- [ContextBuilder.java](file:///Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/context/ContextBuilder.java)
- 新增：`src/main/java/com/codegraph/context/ChineseTermMapper.java`

**核心逻辑**：

```java
// ChineseTermMapper.java — 中→英术语映射
public class ChineseTermMapper {
    // 基础映射表（可配置化 / 可扩展）
    private static final Map<String, List<String>> MAPPINGS = Map.of(
        "索引",   List.of("index", "sync", "orchestrator"),
        "流程",   List.of("pipeline", "flow", "process"),
        "搜索",   List.of("search", "query", "finder"),
        "服务",   List.of("service", "server", "handler"),
        "请求",   List.of("request", "req", "invoke"),
        "响应",   List.of("response", "resp", "result"),
        "配置",   List.of("config", "settings", "properties"),
        "数据库", List.of("database", "db", "datasource", "jdbc"),
        "日志",   List.of("log", "logger", "logging"),
        "认证",   List.of("auth", "authenticate", "login"),
        "用户",   List.of("user", "account", "member"),
        "工具",   List.of("tool", "util", "helper"),
        "解析",   List.of("parse", "parser", "extract"),
        "构建",   List.of("build", "builder", "construct"),
        "同步",   List.of("sync", "synchronize", "orchestrator"),
        "执行",   List.of("execute", "run", "handle", "process"),
        "查询",   List.of("query", "search", "find", "lookup"),
        "调用",   List.of("call", "invoke", "trigger"),
        "依赖",   List.of("dependency", "depend", "import")
    );

    public List<String> mapToEnglish(List<String> chineseTerms) {
        // 返回所有映射的英文关键词
    }
}
```

**改动点**：

```java
// ContextBuilder.findRelevantContext() 中
List<String> chineseTerms = extractChineseTerms(query);
if (!chineseTerms.isEmpty()) {
    // A: 中文词做 text search
    for (String ct : chineseTerms) {
        for (Node n : queries.searchNodes(ct)) {
            if (!result.nodes.containsKey(n.getId())) result.addNode(n);
        }
    }
    // B: 映射为英文关键词搜索
    List<String> englishTerms = ChineseTermMapper.mapToEnglish(chineseTerms);
    for (String et : englishTerms) {
        List<Node> byName = queries.getNodesByName(et);
        if (byName.isEmpty()) byName = queries.searchNodes(et);
        for (Node n : byName) {
            if (!result.nodes.containsKey(n.getId())) result.addNode(n);
        }
    }
}
```

**优点**：
- 真正解决"中→英"的语义映射问题
- 对纯英文代码库也有效（因为最终搜索的还是英文标识符）
- 映射表可扩展，可通过配置文件定制

**缺点**：
- 需要维护映射字典，覆盖率取决于字典规模
- 新增一个文件

---

### 方案 C：中文分词 + 智能匹配（长期方向）

**思路**：引入 jieba 分词或 HanLP 对中文查询做分词，结合方案 B 的映射字典 + 编辑距离模糊匹配。

**改动范围**：
- 引入中文分词依赖（如 jieba-analysis 或 HanLP）
- 新增 `ChineseQueryProcessor` 类
- 修改 `ContextBuilder` 和 `MCPToolHandler` 的查询处理流程

**优点**：最彻底的中文支持方案

**缺点**：
- 引入额外依赖增加 JAR 体积
- 分词准确率依赖词典质量
- 开发和测试周期最长

---

## 3. 推荐实施路径

### 第一阶段（本次实施）：方案 A + 方案 B

1. **ContextBuilder.java** — 新增中文检测 + 中文 text search
2. **ChineseTermMapper.java** — 新增中→英关键词映射
3. **ExploreFlowLogTest.java** — 增加中文查询验证用例

### 第二阶段（后续迭代）：方案 C

按需引入分词库，升级为完整的中文语义理解。

---

## 4. 具体实施计划

### 文件 1：新增 `src/main/java/com/codegraph/context/ChineseTermMapper.java`

- 定义 20-30 个常见技术术语的中→英映射
- 提供 `mapToEnglish(List<String>)` 方法
- 支持通过 JSON 配置文件扩展（可选）

### 文件 2：修改 `src/main/java/com/codegraph/context/ContextBuilder.java`

| 改动点 | 位置 | 说明 |
|--------|------|------|
| 新增常量 | L63 附近 | `CHINESE_PATTERN` 匹配中文字符 |
| 新增方法 | L365 附近 | `extractChineseTerms()` 提取中文子串 |
| 修改 findRelevantContext | L89-L233 | 在符号匹配后增加中文搜索层 |
| 修改降级路径 | L102-L112 | 纯中文时 split 中文词分别搜索 |

### 文件 3：修改 `src/test/java/com/codegraph/mcp/ExploreFlowLogTest.java`

- 增加中文查询测试（如 `"索引流程"`、`"MCPToolHandler 搜索流程"`）
- 验证中英混合查询能返回更多结果

---

## 5. 验证标准

| 测试用例 | 当前行为 | 期望行为 |
|---------|---------|---------|
| `"IndexCommand 索引流程"` | 1 symbol, 0 files | ≥2 files（中文映射到 index/sync） |
| `"服务启动"` | 0 results | ≥1 file（映射到 server/start） |
| `"MCPToolHandler"` | 正常工作 | 不受影响，结果不变 |
| 纯英文查询 | 正常工作 | 不受影响 |

---

## 6. 风险与注意事项

1. **中文 text search 性能**：每次额外增加 `chineseTerms.size()` 次 DB 查询，需控制中文词数量上限
2. **映射噪音**：过度映射可能引入无关结果，需设置节点数量上限
3. **向后兼容**：方案 A+B 是增量添加，不修改现有 extractSymbols 逻辑，纯英文查询路径完全不变
