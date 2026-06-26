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
     */
    public Subgraph findRelevantContext(String query, FindOptions opts) {
        if (query == null || query.trim().isEmpty()) {
            return emptySubgraph();
        }
        FindOptions options = opts != null ? opts : new FindOptions();

        Subgraph result = new Subgraph();

        // Step 1: 从查询中提取符号名
        List<String> symbols = extractSymbols(query);
        if (symbols.isEmpty()) {
            // 没有有效符号，退化为纯文本搜索
            try {
                List<Node> textResults = queries.searchNodes(query);
                for (Node n : textResults) result.addNode(n);
                for (String id : result.nodes.keySet()) result.addRoot(id);
            } catch (SQLException e) {
                logger.warn("Text search failed: {}", e.getMessage());
            }
            return result;
        }

        // Step 2: Exact match 搜索
        Map<String, Node> exactMatches = new LinkedHashMap<>();
        try {
            List<String> boostedOrder = new ArrayList<>();
            for (String sym : symbols) {
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

            // Co-location boost: 同一文件中命中多个符号，该文件的所有符号加权
            if (exactMatches.size() > 1) {
                Map<String, Set<String>> fileSymbolCounts = new HashMap<>();
                for (Node n : exactMatches.values()) {
                    if (n.getFilePath() != null) {
                        fileSymbolCounts
                            .computeIfAbsent(n.getFilePath(), k -> new HashSet<>())
                            .add(n.getName().toLowerCase());
                    }
                }
                // 按 co-location 重新排序
                List<Node> sorted = new ArrayList<>(exactMatches.values());
                sorted.sort((a, b) -> {
                    int ca = fileSymbolCounts.getOrDefault(a.getFilePath(), Collections.emptySet()).size();
                    int cb = fileSymbolCounts.getOrDefault(b.getFilePath(), Collections.emptySet()).size();
                    return cb - ca;
                });
                exactMatches.clear();
                for (Node n : sorted) exactMatches.put(n.getId(), n);
            }

            // 截断
            int limit = (int) Math.ceil(options.searchLimit * 2.0);
            int count = 0;
            for (Map.Entry<String, Node> e : exactMatches.entrySet()) {
                if (count++ >= limit) break;
                result.addNode(e.getValue());
            }
        } catch (SQLException e) {
            logger.warn("Exact match failed: {}", e.getMessage());
        }

        // Step 3: Prefix match（针对类型定义的模糊匹配）
        try {
            for (String sym : symbols) {
                String titleCased = sym.length() > 0
                    ? Character.toUpperCase(sym.charAt(0)) + sym.substring(1).toLowerCase()
                    : sym;
                if (titleCased.equals(sym)) continue; // 已经是 title-case

                List<Node> prefixResults = queries.searchNodes(titleCased);
                int count = 0;
                for (Node n : prefixResults) {
                    if (count++ >= options.searchLimit) break;
                    if (!result.hasNode(n.getId()) &&
                        DEFINITION_KINDS.contains(n.getKind().name()) &&
                        n.getName().toLowerCase().startsWith(titleCased.toLowerCase())) {
                        result.addNode(n);
                    }
                }
            }
        } catch (SQLException e) {
            logger.warn("Prefix match failed: {}", e.getMessage());
        }

        // 设置入口节点
        for (String id : result.nodes.keySet()) {
            result.addRoot(id);
        }

        // Step 4: 从入口节点 BFS 扩展子图
        if (result.nodeCount() > 0) {
            Set<String> allRootIds = new LinkedHashSet<>(result.roots);

            // 对每个入口节点做 BFS 扩展
            for (String rootId : new ArrayList<>(allRootIds)) {
                TraversalOptions tOpts = new TraversalOptions();
                tOpts.maxDepth = options.traversalDepth;
                tOpts.limit = options.maxNodes / Math.max(1, allRootIds.size());
                tOpts.direction = "both"; // 无向遍历
                tOpts.includeStart = true;

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
     */
    public List<String> extractSymbols(String query) {
        if (query == null) return Collections.emptyList();
        String[] parts = query.split("[\\s,()\\[\\]]+");
        List<String> symbols = new ArrayList<>();
        for (String raw : parts) {
            String t = FILE_EXT_PATTERN.matcher(raw).replaceAll("").trim();
            if (t.length() >= 3 && VALID_TOKEN_PATTERN.matcher(t).matches()) {
                symbols.add(t);
                if (symbols.size() >= 16) break; // 最多 16 个 token
            }
        }
        return symbols;
    }

    private static Subgraph emptySubgraph() {
        return new Subgraph();
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
