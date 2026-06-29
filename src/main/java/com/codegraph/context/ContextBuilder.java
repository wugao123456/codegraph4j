package com.codegraph.context;

import com.codegraph.core.Edge;
import com.codegraph.core.Node;
import com.codegraph.core.types.EdgeKind;
import com.codegraph.core.types.NodeKind;
import com.codegraph.db.QueryBuilder;
import com.codegraph.graph.GraphTraverser;
import com.codegraph.graph.GraphTraverser.CallerInfo;
import com.codegraph.graph.GraphTraverser.CalleeInfo;
import com.codegraph.graph.GraphTraverser.TraversalOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 上下文构建器 — 封装 explore 工具的混合搜索和子图构建逻辑。
 * 对标 codegraph context/index.ts 的 findRelevantContext() 和
 * mcp/tools.ts 的 buildFlowFromNamedSymbols()。
 */
public class ContextBuilder {

    private static final Logger logger = LoggerFactory.getLogger(ContextBuilder.class);

    private static final Set<String> DEFINITION_KINDS = new HashSet<>(Arrays.asList(
        "class", "interface", "struct", "trait", "protocol", "enum", "type_alias",
        "CLASS", "INTERFACE", "STRUCT", "TRAIT", "PROTOCOL", "ENUM", "TYPE_ALIAS"
    ));

    private static final Set<String> CALLABLE_KINDS = new HashSet<>(Arrays.asList(
        "method", "function", "component", "constructor",
        "METHOD", "FUNCTION", "COMPONENT", "CONSTRUCTOR"
    ));

    /**
     * 相关性计算时使用的边类型集合 — 排除 CONTAINS（AST 父子关系），
     * 只保留语义关系边，用于 RWR 图相关性计算和子图扩展。
     */
    private static final Set<com.codegraph.core.types.EdgeKind> RELEVANT_EDGE_KINDS = new HashSet<>(Arrays.asList(
        com.codegraph.core.types.EdgeKind.CALLS,
        com.codegraph.core.types.EdgeKind.REFERENCES,
        com.codegraph.core.types.EdgeKind.EXTENDS,
        com.codegraph.core.types.EdgeKind.IMPLEMENTS,
        com.codegraph.core.types.EdgeKind.OVERRIDES,
        com.codegraph.core.types.EdgeKind.INSTANTIATES,
        com.codegraph.core.types.EdgeKind.RETURNS,
        com.codegraph.core.types.EdgeKind.TYPE_OF,
        com.codegraph.core.types.EdgeKind.IMPORTS
    ));

    /** 文件扩展名模式（用于 token 解析） */
    private static final Pattern FILE_EXT_PATTERN = Pattern.compile(
        "\\.(?:java|kt|kts|ts|tsx|js|jsx|mjs|cjs|cs|py|go|rb|php|swift|rs|cpp|cc|cxx|c|h|hpp|scala|lua|dart|vue|svelte|astro)$",
        Pattern.CASE_INSENSITIVE
    );

    /** 有效的符号名模式 */
    private static final Pattern VALID_TOKEN_PATTERN = Pattern.compile(
        "^[A-Za-z_$][\\w$]*(?:(?:::|\\.)[\\w$]+)*$"
    );

    private final QueryBuilder queries;
    private final GraphTraverser traverser;

    public ContextBuilder(QueryBuilder queries) {
        this.queries = queries;
        this.traverser = new GraphTraverser(queries);
    }

    // ========== 公开 API ==========

    /**
     * 查找相关上下文 — 混合搜索（exact + prefix + text）+ 图遍历。
     * 对标 codegraph context/index.ts findRelevantContext()。
     *
     * <p>设计思路：采用分层搜索策略，从精确到模糊逐步扩展，最后通过图遍历补全上下文。
     * 核心目标是在有限预算内，最大化返回与用户查询最相关的代码节点和关系。
     *
     * <ol>
     *   <li>符号提取：从查询中提取可识别的代码符号（类名、方法名等）</li>
     *   <li>精确匹配：按完全限定名和简单名搜索，确保高精准度</li>
     *   <li>前缀匹配：针对类型定义的 Title-Case 模糊匹配，提高召回率</li>
     *   <li>图扩展：从搜索结果出发进行 BFS，补全相邻节点和边</li>
     * </ol>
     */
    public Subgraph findRelevantContext(String query, FindOptions opts) {
        // 空查询保护：避免无效搜索
        if (query == null || query.trim().isEmpty()) {
            return emptySubgraph();
        }
        FindOptions options = opts != null ? opts : new FindOptions();

        Subgraph result = new Subgraph();

        // Step 1: 从查询中提取符号名
        // 设计原因：将自然语言查询转换为代码符号，是后续精确搜索的基础
        // 提取规则：去除文件扩展名、过滤短 token（<3）、匹配合法标识符模式
        List<String> symbols = extractSymbols(query);
        if (symbols.isEmpty()) {
            // 降级策略：无有效符号时，退化为纯文本搜索
            // 典型场景：用户输入 "how to handle authentication" 这类自然语言问题
            try {
                List<Node> textResults = queries.searchNodes(query);
                for (Node n : textResults) result.addNode(n);
                for (String id : result.nodes.keySet()) result.addRoot(id);
            } catch (SQLException e) {
                logger.warn("Text search failed: {}", e.getMessage());
            }
            return result;
        }

        // Step 2: Exact match 搜索 — 最高优先级，保证精准度
        // 设计原因：精确匹配是最可靠的搜索方式，优先获取确定性结果
        Map<String, Node> exactMatches = new LinkedHashMap<>();
        try {
            for (String sym : symbols) {
                // 双重搜索：先按完全限定名（如 com.codegraph.UserService），再按简单名
                // 原因：qualified name 唯一确定一个符号，普通 name 可能有多个同名
                List<Node> byQName = queries.getNodesByQualifiedName(sym);
                List<Node> byName = queries.getNodesByName(sym);
                Set<String> seen = new HashSet<>();
                for (Node n : byQName) {
                    if (seen.add(n.getId())) exactMatches.put(n.getId(), n);
                }
                for (Node n : byName) {
                    if (seen.add(n.getId())) exactMatches.put(n.getId(), n);
                }
            }

            // Co-location boost: 同文件多符号命中加权
            // 设计原理：如果一个文件中同时包含查询中的多个符号，说明该文件更可能是用户关心的核心文件
            // 例如查询 "UserService login"，如果 UserService.java 同时包含 login() 和 getUser() 方法，
            // 那么 UserService.java 中的所有符号都应优先返回
            if (exactMatches.size() > 1) {
                Map<String, Set<String>> fileSymbolCounts = new HashMap<>();
                for (Node n : exactMatches.values()) {
                    if (n.getFilePath() != null) {
                        fileSymbolCounts
                            .computeIfAbsent(n.getFilePath(), k -> new HashSet<>())
                            .add(n.getName().toLowerCase());
                    }
                }
                // 按文件内命中符号数降序排序
                List<Node> sorted = new ArrayList<>(exactMatches.values());
                sorted.sort((a, b) -> {
                    int ca = fileSymbolCounts.getOrDefault(a.getFilePath(), Collections.emptySet()).size();
                    int cb = fileSymbolCounts.getOrDefault(b.getFilePath(), Collections.emptySet()).size();
                    return cb - ca;
                });
                exactMatches.clear();
                for (Node n : sorted) exactMatches.put(n.getId(), n);
            }

            // 结果截断：限制精确匹配数量
            // 原因：精确匹配通常已经足够相关，过多结果会增加后续处理负担
            // 限制为 searchLimit * 2，给 co-location boost 后的排序留出空间
            int limit = (int) Math.ceil(options.searchLimit * 2.0);
            int count = 0;
            for (Map.Entry<String, Node> e : exactMatches.entrySet()) {
                if (count++ >= limit) break;
                result.addNode(e.getValue());
            }
        } catch (SQLException e) {
            logger.warn("Exact match failed: {}", e.getMessage());
        }

        // Step 3: Prefix match — 针对类型定义的模糊匹配
        // 设计原因：用户可能只记得类型名的一部分，如 "Rest" → "RestController"
        // 只针对定义类型（class/interface/enum 等），避免匹配变量名导致噪音
        try {
            for (String sym : symbols) {
                // 确定前缀匹配模式：
                // - 全小写短词（如 "rest"）：转换为 Title-Case "Rest" 进行前缀匹配
                // - 已首字母大写（如 "Rest"）：直接用原符号进行前缀匹配
                // - camelCase（如 "userService"）：用首字母大写形式 "UserService" 进行前缀匹配
                // - 已包含大写字母的长词：直接用原符号
                String prefixPattern = determinePrefixPattern(sym);
                if (prefixPattern == null) continue;

                // 文本搜索获取候选，再做前缀过滤
                List<Node> prefixResults = queries.searchNodes(prefixPattern);
                int count = 0;
                for (Node n : prefixResults) {
                    if (count++ >= options.searchLimit) break;
                    // 三重过滤：去重 + 定义类型 + 前缀匹配
                    // 确保只添加有意义的类型定义节点
                    if (!result.hasNode(n.getId()) &&
                        DEFINITION_KINDS.contains(n.getKind().name()) &&
                        n.getName().toLowerCase().startsWith(prefixPattern.toLowerCase())) {
                        result.addNode(n);
                    }
                }
            }
        } catch (SQLException e) {
            logger.warn("Prefix match failed: {}", e.getMessage());
        }

        // 设置入口节点：将所有搜索结果标记为 root，作为后续图遍历的起点
        for (String id : result.nodes.keySet()) {
            result.addRoot(id);
        }

        // Step 4: 从入口节点 BFS 扩展子图
        // 设计原因：搜索结果可能只是孤立的节点，需要通过图遍历补全上下文关系
        // 例如找到 UserService.login()，需要扩展到其调用的 AuthService 和被调用的位置
        if (result.nodeCount() > 0) {
            Set<String> allRootIds = new LinkedHashSet<>(result.roots);

            // 对每个入口节点做 BFS 扩展
            // 限制每个根节点的扩展数量：maxNodes / rootCount
            // 原因：避免某个热门节点（如 Utils 类）过度扩展，挤占其他节点的预算
            for (String rootId : new ArrayList<>(allRootIds)) {
                TraversalOptions tOpts = new TraversalOptions();
                tOpts.maxDepth = options.traversalDepth;
                tOpts.limit = options.maxNodes / Math.max(1, allRootIds.size());
                tOpts.direction = "both"; // 无向遍历：同时获取 callers 和 callees
                tOpts.includeStart = true;
                // 边类型过滤：只包含语义关系边，排除 CONTAINS（AST 父子关系）
                // 原因：CONTAINS 边会导致子图膨胀，包含大量无关的方法/字段节点
                // 仅保留：calls, references, extends, implements, overrides, instantiates, returns, type_of, imports
                tOpts.edgeKinds = RELEVANT_EDGE_KINDS;

                com.codegraph.graph.GraphTraverser.Subgraph sg = traverser.traverseBFS(rootId, tOpts);
                for (Node n : sg.nodes.values()) result.addNode(n);
                for (Edge e : sg.edges) result.addEdge(e);
            }
        }

        return result;
    }

    /**
     * 从命名符号构建调用路径信息。
     * 对标 codegraph mcp/tools.ts buildFlowFromNamedSymbols()。
     *
     * <p>从 query 中提取符号名，解析为节点，构建以下信息：
     * <ul>
     *   <li>pathNodeIds: 在调用链上的节点</li>
     *   <li>namedNodeIds: query 中直接命名的节点</li>
     *   <li>uniqueNamedNodeIds: 近似唯一的可调用节点（≤3 个定义）</li>
     * </ul>
     */
    public FlowInfo buildFlowFromNamedSymbols(String query) {
        FlowInfo flow = new FlowInfo();
        if (query == null || query.trim().isEmpty()) return flow;

        List<String> tokens = extractSymbols(query);
        if (tokens.size() < 2) return flow;

        // 收集 token → 节点映射
        Map<String, List<Node>> tokenNodes = new LinkedHashMap<>();
        Map<String, Node> namedNodes = new LinkedHashMap<>();
        Set<String> uniqueNamedIds = new HashSet<>();

        for (String token : tokens) {
            if (namedNodes.size() >= 40) break;
            try {
                // 优先 qualified name
                List<Node> hits = queries.getNodesByQualifiedName(token);
                if (hits.isEmpty()) hits = queries.getNodesByName(token);
                if (hits.isEmpty()) hits = queries.searchNodes(token);

                List<Node> callables = new ArrayList<>();
                for (Node n : hits) {
                    if (CALLABLE_KINDS.contains(n.getKind().name())) callables.add(n);
                }

                tokenNodes.put(token, callables);

                // 特殊名称（≤3 定义）：全量保留
                // 普通名称：只保留与 query 中其他 token 容器匹配的
                boolean isSpecific = callables.size() <= 3;
                for (Node n : callables) {
                    if (namedNodes.size() >= 40) break;
                    namedNodes.put(n.getId(), n);
                    if (isSpecific) uniqueNamedIds.add(n.getId());
                }
            } catch (SQLException e) {
                logger.debug("Token {} resolution failed: {}", token, e.getMessage());
            }
        }

        // 构建调用链
        Map<String, Node> allNodes = new HashMap<>(namedNodes);
        Set<String> pathNodeIds = new LinkedHashSet<>();
        Set<String> namedNodeIds = new HashSet<>(namedNodes.keySet());

        // 简单贪婪：按文件/定义顺序排列，然后找调用关系
        List<Node> sorted = new ArrayList<>(namedNodes.values());
        sorted.sort(Comparator
            .comparing((Node n) -> n.getFilePath() != null ? n.getFilePath() : "")
            .thenComparingInt(n -> n.getStartLine()));

        for (int i = 0; i < sorted.size(); i++) {
            pathNodeIds.add(sorted.get(i).getId());
            // 找调用 sorted[i] 的节点
            if (i > 0) {
                List<CallerInfo> callers = traverser.getCallers(sorted.get(i).getId(), 1);
                for (CallerInfo ci : callers) {
                    if (!namedNodeIds.contains(ci.node.getId())) {
                        pathNodeIds.add(ci.node.getId());
                        break; // 每个节点最多加一个调用者
                    }
                }
            }
        }

        flow.pathNodeIds = pathNodeIds;
        flow.namedNodeIds = namedNodeIds;
        flow.uniqueNamedNodeIds = uniqueNamedIds;
        return flow;
    }

    // ========== 辅助方法 ==========

    /**
     * 从查询字符串中提取有效符号名。
     *
     * <p>设计思路：将用户输入的自然语言查询或代码片段解析为可识别的代码符号，
     * 作为后续混合搜索的基础输入。核心步骤：
     * <ol>
     *   <li>分词：按空白、逗号、括号等分隔符切分</li>
     *   <li>清理：去除文件扩展名（如 `.java`）</li>
     *   <li>验证：过滤短 token（<3），匹配 Java 标识符模式</li>
     *   <li>限制：最多提取 16 个符号，避免搜索范围过大</li>
     * </ol>
     *
     * <p>支持的符号格式：
     * <ul>
     *   <li>简单标识符：`UserService`, `login`, `MAX_SIZE`</li>
     *   <li>完全限定名：`com.codegraph.UserService`, `java.util.List`</li>
     *   <li>带分隔符的路径：`UserService.login`, `User::getName`</li>
     * </ul>
     *
     * @param query 用户查询字符串
     * @return 有效符号列表，最多 16 个
     */
    public List<String> extractSymbols(String query) {
        // 空查询保护
        if (query == null) return Collections.emptyList();

        // 分词：按空白、逗号、括号、方括号等常见分隔符切分
        // 例如："UserService.login() with auth" → ["UserService.login", "with", "auth"]
        String[] parts = query.split("[\\s,()\\[\\]]+");
        List<String> symbols = new ArrayList<>();

        for (String raw : parts) {
            // 清理：去除文件扩展名（如 "User.java" → "User"）
            // 原因：用户可能粘贴文件路径，需要提取纯符号名
            String t = FILE_EXT_PATTERN.matcher(raw).replaceAll("").trim();

            // 双重验证：
            // 1. 长度 ≥3：过滤无意义的短词（如 "a", "of", "to"）
            // 2. 匹配有效标识符模式：确保是合法的代码符号
            if (t.length() >= 3 && VALID_TOKEN_PATTERN.matcher(t).matches()) {
                symbols.add(t);
                // 限制最多 16 个符号：避免搜索范围过大，影响性能和相关性
                if (symbols.size() >= 16) break;
            }
        }
        return symbols;
    }

    private static Subgraph emptySubgraph() {
        return new Subgraph();
    }

    /**
     * 根据符号确定前缀匹配模式。
     * 优化策略：
     * - 全小写短词（如 "rest"）：转换为 Title-Case "Rest"，匹配 PascalCase 类型名
     * - 首字母大写（如 "Rest"）：保留原符号，支持前缀匹配 "RestController"
     * - camelCase（如 "userService"）：首字母大写为 "UserService"，匹配类型定义
     * - 全大写（如 "HTTP"）：跳过，通常是常量而非类型定义
     *
     * @return 前缀匹配模式字符串，不需要前缀匹配时返回 null
     */
    private String determinePrefixPattern(String symbol) {
        if (symbol == null || symbol.length() < 2) return null;

        boolean hasUpperCase = false;
        boolean hasLowerCase = false;
        for (char c : symbol.toCharArray()) {
            if (Character.isUpperCase(c)) hasUpperCase = true;
            if (Character.isLowerCase(c)) hasLowerCase = true;
        }

        // 全大写：通常是常量，不做前缀匹配
        if (hasUpperCase && !hasLowerCase) return null;

        // 首字母大写：直接使用原符号
        if (Character.isUpperCase(symbol.charAt(0))) {
            return symbol;
        }

        // 首字母小写且包含大写（camelCase）：首字母大写
        if (hasUpperCase) {
            return Character.toUpperCase(symbol.charAt(0)) + symbol.substring(1);
        }

        // 全小写：转换为 Title-Case
        return Character.toUpperCase(symbol.charAt(0)) + symbol.substring(1);
    }

    // ========== 内部类 ==========

    /**
     * 查找选项 — 配置上下文查找的搜索参数。
     */
    public static class FindOptions {
        /** 搜索结果数量上限 */
        public int searchLimit = 8;
        /** 图遍历深度 */
        public int traversalDepth = 3;
        /** 最大返回节点数 */
        public int maxNodes = 200;
        /** 最小匹配分数阈值 */
        public double minScore = 0.2;
    }

    /**
     * Flow 信息 — 保存命名符号间的调用路径数据。
     */
    public static class FlowInfo {
        public Set<String> pathNodeIds = new LinkedHashSet<>();
        public Set<String> namedNodeIds = new LinkedHashSet<>();
        public Set<String> uniqueNamedNodeIds = new LinkedHashSet<>();
        public Map<String, Integer> spineCallSites = new HashMap<>();
    }
}
