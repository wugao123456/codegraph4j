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
        "class", "interface", "struct", "trait", "protocol", "enum", "type_alias"
    ));

    private static final Set<String> CALLABLE_KINDS = new HashSet<>(Arrays.asList(
        "method", "function", "component", "constructor"
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
        List<String> symbols = extractSymbols(query);
        if (symbols.isEmpty()) {
            try {
                List<Node> textResults = queries.searchNodes(query);
                for (Node n : textResults) result.addNode(n);
                for (String id : result.nodes.keySet()) result.addRoot(id);
            } catch (SQLException e) {
                logger.warn("Text search failed: {}", e.getMessage());
            }
            return result;
        }

        // === 分数驱动的搜索管道 ===
        // 所有搜索步骤产生的候选结果统一用 score 排序，保证多通道结果可比
        List<SearchResult> searchResults = new ArrayList<>();
        Set<String> searchIdSet = new HashSet<>();
        String queryLower = query.toLowerCase();
        boolean isTestQuery = queryLower.contains("test") || queryLower.contains("spec");

        // ---- Step 2: Exact match 搜索（最高优先级） ----
        try {
            for (String sym : symbols) {
                List<Node> byQName = queries.getNodesByQualifiedName(sym);
                List<Node> byName = queries.getNodesByName(sym);
                Set<String> seen = new HashSet<>();
                for (Node n : byQName) {
                    if (seen.add(n.getId()) && searchIdSet.add(n.getId())) {
                        searchResults.add(new SearchResult(n, 80));
                    }
                }
                for (Node n : byName) {
                    if (seen.add(n.getId()) && searchIdSet.add(n.getId())) {
                        searchResults.add(new SearchResult(n, 70));
                    }
                }
            }

            // Co-location boost: 同文件多符号命中加权
            if (searchResults.size() > 1) {
                Map<String, Set<String>> fileSymbolCounts = new HashMap<>();
                for (SearchResult sr : searchResults) {
                    if (sr.node.getFilePath() != null) {
                        fileSymbolCounts
                            .computeIfAbsent(sr.node.getFilePath(), k -> new HashSet<>())
                            .add(sr.node.getName().toLowerCase());
                    }
                }
                for (SearchResult sr : searchResults) {
                    int symbolCount = fileSymbolCounts
                        .getOrDefault(sr.node.getFilePath(), Collections.emptySet()).size();
                    if (symbolCount > 1) {
                        sr.score += (symbolCount - 1) * 20;
                    }
                }
                searchResults.sort((a, b) -> Double.compare(b.score, a.score));
            }

            // 截断
            int exactLimit = (int) Math.ceil(options.searchLimit * 2.0);
            if (searchResults.size() > exactLimit) {
                searchResults = new ArrayList<>(searchResults.subList(0, exactLimit));
                searchIdSet.clear();
                for (SearchResult sr : searchResults) searchIdSet.add(sr.node.getId());
            }
        } catch (SQLException e) {
            logger.warn("Exact match failed: {}", e.getMessage());
        }

        // ---- Step 3: Prefix match（含 stem variants + brevityBonus） ----
        try {
            for (String sym : symbols) {
                // 扩展词干变体
                Set<String> expandedSyms = new LinkedHashSet<>();
                expandedSyms.add(sym);
                for (String variant : SearchUtils.getStemVariants(sym)) {
                    expandedSyms.add(variant);
                }
                for (String expandedSym : expandedSyms) {
                    // Title-case 转换
                    String titleCased = expandedSym.substring(0, 1).toUpperCase()
                        + expandedSym.substring(1).toLowerCase();
                    if (titleCased.equals(expandedSym) && Character.isUpperCase(expandedSym.charAt(0))) continue;

                    List<Node> prefixResults = queries.searchNodes(titleCased);
                    int count = 0;
                    for (Node n : prefixResults) {
                        if (count++ >= options.searchLimit * 3) break;
                        if (searchIdSet.contains(n.getId())) continue;
                        if (!DEFINITION_KINDS.contains(n.getKind().getValue())) continue;
                        if (!n.getName().toLowerCase().startsWith(titleCased.toLowerCase())) continue;

                        // brevityBonus: 偏爱短类名
                        double brevityBonus = Math.max(0, 10 - (n.getName().length() - titleCased.length()) / 3.0);
                        searchResults.add(new SearchResult(n, 15 + brevityBonus));
                        searchIdSet.add(n.getId());
                    }
                }
            }
        } catch (SQLException e) {
            logger.warn("Prefix match failed: {}", e.getMessage());
        }

        // ---- Step 4: FTS 文本搜索（多词累计 boost） ----
        try {
            List<String> searchTerms = SearchUtils.extractSearchTerms(query);
            if (!searchTerms.isEmpty()) {
                // 排除 import 类型节点（它们会淹没 FTS 结果）
                Set<String> searchKinds = new LinkedHashSet<>(DEFINITION_KINDS);
                searchKinds.addAll(Arrays.asList(
                    "file", "module", "function", "method", "property", "field",
                    "variable", "constant", "enum_member", "namespace", "export", "route", "component"
                ));

                // 逐词搜索，累计多词命中
                Map<String, double[]> termResultsMap = new LinkedHashMap<>(); // nodeId → [maxScore, termHits]
                for (String term : searchTerms) {
                    List<Node> termResults = queries.searchNodes(term,
                        options.searchLimit * 3, searchKinds.toArray(new String[0]));
                    for (Node n : termResults) {
                        double[] entry = termResultsMap.get(n.getId());
                        if (entry == null) {
                            entry = new double[]{0, 1};
                            termResultsMap.put(n.getId(), entry);
                        } else {
                            entry[1]++; // termHints++
                        }
                        // 保持最高分
                        entry[0] = Math.max(entry[0], 10);
                    }
                }

                // 多词命中 boost 并加入结果
                for (Map.Entry<String, double[]> e : termResultsMap.entrySet()) {
                    if (searchIdSet.contains(e.getKey())) continue;
                    double[] v = e.getValue();
                    double score = v[0] + (v[1] - 1) * 5; // 每多一个 term 匹配 +5
                    // 需要 resolve node
                    try {
                        Node node = queries.getNode(e.getKey());
                        if (node != null) {
                            searchResults.add(new SearchResult(node, score));
                            searchIdSet.add(node.getId());
                        }
                    } catch (SQLException ignored) {}
                }
            }
        } catch (SQLException e) {
            logger.warn("FTS text search failed: {}", e.getMessage());
        }

        // 测试文件降权
        if (!isTestQuery) {
            for (SearchResult sr : searchResults) {
                if (SearchUtils.isTestFile(sr.node.getFilePath())) {
                    sr.score *= 0.3;
                }
            }
        }

        // ---- Step 5a: 多术语共现重排序 ----
        List<String> queryTermsForBoost = SearchUtils.extractSearchTerms(query);
        if (queryTermsForBoost.size() >= 2) {
            // 词项分组：子串关系的词视为同一概念
            List<List<String>> termGroups = new ArrayList<>();
            List<String> sorted = new ArrayList<>(queryTermsForBoost);
            sorted.sort((a, b) -> b.length() - a.length());
            Set<String> assigned = new HashSet<>();
            for (String term : sorted) {
                if (assigned.contains(term)) continue;
                List<String> group = new ArrayList<>();
                group.add(term);
                assigned.add(term);
                for (String other : sorted) {
                    if (assigned.contains(other)) continue;
                    if (term.contains(other) || other.contains(term)) {
                        group.add(other);
                        assigned.add(other);
                    }
                }
                termGroups.add(group);
            }

            // 收集区分性标识符的精确匹配 ID
            Set<String> distinctiveExactMatchIds = new HashSet<>();
            for (SearchResult sr : searchResults) {
                String name = sr.node.getName() != null ? sr.node.getName() : "";
                for (String sym : symbols) {
                    if (SearchUtils.isDistinctiveIdentifier(sym)
                        && name.equalsIgnoreCase(sym)) {
                        distinctiveExactMatchIds.add(sr.node.getId());
                    }
                }
            }

            // 按多词命中 boost 或 dampen
            for (SearchResult sr : searchResults) {
                String nameLower = sr.node.getName() != null ? sr.node.getName().toLowerCase() : "";
                String filePath = sr.node.getFilePath() != null ? sr.node.getFilePath().toLowerCase() : "";
                // 检查目录段
                String[] dirSegments = new String[0];
                int lastSep = filePath.lastIndexOf('/');
                if (lastSep >= 0) {
                    dirSegments = filePath.substring(0, lastSep).split("/");
                }

                int matchCount = 0;
                for (List<String> group : termGroups) {
                    boolean groupMatches = false;
                    for (String term : group) {
                        if (nameLower.contains(term)) { groupMatches = true; break; }
                        for (String seg : dirSegments) {
                            if (seg.equals(term)) { groupMatches = true; break; }
                        }
                        if (groupMatches) break;
                    }
                    if (groupMatches) matchCount++;
                }

                if (matchCount >= 2) {
                    sr.score *= (1 + matchCount * 0.5); // 2词→2x, 3词→2.5x
                } else if (distinctiveExactMatchIds.contains(sr.node.getId())) {
                    // 区分性标识符精确匹配，保持原分
                } else if (sr.score >= 70) {
                    // 普通词精确匹配，无其他词佐证 → 降权
                    sr.score *= 0.3;
                } else {
                    // 单术语匹配 → 温和降权
                    sr.score *= 0.6;
                }
            }
            searchResults.sort((a, b) -> Double.compare(b.score, a.score));
        }

        // ---- Step 5b: CamelCase 边界匹配（LIKE 子串查询） ----
        if (!symbols.isEmpty()) {
            Set<String> camelSearchedTerms = new HashSet<>();
            Map<String, double[]> camelNodeTerms = new LinkedHashMap<>(); // nodeId → [maxScore, termCount]

            for (String sym : symbols) {
                String titleCased = sym.substring(0, 1).toUpperCase() + sym.substring(1).toLowerCase();
                if (titleCased.length() < 3) continue;
                String termKey = titleCased.toLowerCase();
                if (camelSearchedTerms.contains(termKey)) continue;
                camelSearchedTerms.add(termKey);

                try {
                    List<Node> likeResults = queries.findNodesByNameSubstring(titleCased, 200,
                        DEFINITION_KINDS.toArray(new String[0]), true);
                    for (Node n : likeResults) {
                        String name = n.getName();
                        int idx = name.indexOf(titleCased);
                        if (idx <= 0) continue;
                        // CamelCase 边界检测：匹配位置前一个字符必须是字母
                        if (idx > 0 && !Character.isLetter(name.charAt(idx - 1))) continue;
                        if (searchIdSet.contains(n.getId())) continue;
                        if (SearchUtils.isTestFile(n.getFilePath()) && !isTestQuery) continue;

                        int pathScore = SearchUtils.scorePathRelevance(n.getFilePath(), query);
                        double brevityBonus = Math.max(0, 6 - (name.length() - titleCased.length()) / 4.0);
                        double score = 8 + brevityBonus + pathScore;

                        double[] entry = camelNodeTerms.get(n.getId());
                        if (entry == null) {
                            entry = new double[]{score, 1};
                            camelNodeTerms.put(n.getId(), entry);
                        } else {
                            entry[1]++; // 多词命中计数
                            entry[0] = Math.max(entry[0], score);
                        }
                    }
                } catch (SQLException ignored) {}
            }

            // 合并 CamelCase 结果（含多词 boost）
            List<SearchResult> camelResults = new ArrayList<>();
            for (Map.Entry<String, double[]> e : camelNodeTerms.entrySet()) {
                double[] v = e.getValue();
                double score = v[0] * (1 + v[1]) + (v[1] - 1) * 30;
                try {
                    Node node = queries.getNode(e.getKey());
                    if (node != null) {
                        camelResults.add(new SearchResult(node, score));
                    }
                } catch (SQLException ignored) {}
            }
            camelResults.sort((a, b) -> Double.compare(b.score, a.score));
            int maxCamelTotal = options.searchLimit;
            for (SearchResult sr : camelResults.subList(0, Math.min(maxCamelTotal, camelResults.size()))) {
                searchResults.add(sr);
                searchIdSet.add(sr.node.getId());
            }
        }

        // ---- Step 5c: 复合词匹配（≥2 query terms 在同一类名中） ----
        if (symbols.size() >= 2) {
            Map<String, double[]> compoundTermMap = new LinkedHashMap<>(); // nodeId → [_, termCount]
            for (String sym : symbols) {
                String titleCased = sym.substring(0, 1).toUpperCase() + sym.substring(1).toLowerCase();
                if (titleCased.length() < 3) continue;

                try {
                    List<Node> likeResults = queries.findNodesByNameSubstring(titleCased, 200,
                        DEFINITION_KINDS.toArray(new String[0]), false);
                    for (Node n : likeResults) {
                        if (searchIdSet.contains(n.getId())) continue;
                        if (SearchUtils.isTestFile(n.getFilePath()) && !isTestQuery) continue;

                        double[] entry = compoundTermMap.get(n.getId());
                        if (entry == null) {
                            entry = new double[]{0, 1};
                            compoundTermMap.put(n.getId(), entry);
                        } else {
                            entry[1]++;
                        }
                    }
                } catch (SQLException ignored) {}
            }

            List<SearchResult> compoundResults = new ArrayList<>();
            for (Map.Entry<String, double[]> e : compoundTermMap.entrySet()) {
                double[] v = e.getValue();
                if (v[1] < 2) continue; // 至少匹配 2 个不同词
                try {
                    Node node = queries.getNode(e.getKey());
                    if (node != null) {
                        int pathScore = SearchUtils.scorePathRelevance(node.getFilePath(), query);
                        double brevityBonus = Math.max(0, 6 - node.getName().length() / 8.0);
                        double score = 10 + (v[1] - 1) * 20 + pathScore + brevityBonus;
                        compoundResults.add(new SearchResult(node, score));
                    }
                } catch (SQLException ignored) {}
            }
            compoundResults.sort((a, b) -> Double.compare(b.score, a.score));
            int maxCompound = (int) Math.ceil(options.searchLimit / 2.0);
            for (SearchResult sr : compoundResults.subList(0, Math.min(maxCompound, compoundResults.size()))) {
                searchResults.add(sr);
                searchIdSet.add(sr.node.getId());
            }
        }

        // ---- 最终排序和截断 ----
        searchResults.sort((a, b) -> Double.compare(b.score, a.score));
        if (searchResults.size() > options.searchLimit * 3) {
            searchResults = new ArrayList<>(searchResults.subList(0, options.searchLimit * 3));
        }

        // 按最低分数过滤
        List<SearchResult> filtered = new ArrayList<>();
        for (SearchResult sr : searchResults) {
            if (sr.score >= options.minScore) filtered.add(sr);
        }

        // 入口点数量上限
        if (filtered.size() > options.searchLimit) {
            filtered = new ArrayList<>(filtered.subList(0, options.searchLimit));
        }

        // 添加入口点到子图
        for (SearchResult sr : filtered) {
            result.addNode(sr.node);
            result.addRoot(sr.node.getId());
        }

        // Step 6: BFS 图扩展
        if (result.nodeCount() > 0) {
            Set<String> allRootIds = new LinkedHashSet<>(result.roots);
            for (String rootId : new ArrayList<>(allRootIds)) {
                TraversalOptions tOpts = new TraversalOptions();
                tOpts.maxDepth = options.traversalDepth;
                tOpts.limit = options.maxNodes / Math.max(1, allRootIds.size());
                tOpts.direction = "both";
                tOpts.includeStart = true;
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
                    if (CALLABLE_KINDS.contains(n.getKind().getValue())) callables.add(n);
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

    // ========== 内部类 ==========

    /**
     * 搜索结果 — 节点 + 相关性分数。
     * <p>供分数驱动的搜索管道使用，所有搜索通道产生的结果通过 score 统一排序比较。
     */
    public static class SearchResult {
        public final Node node;
        public double score;

        public SearchResult(Node node, double score) {
            this.node = node;
            this.score = score;
        }
    }

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
