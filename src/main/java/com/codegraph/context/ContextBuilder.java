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
        if (query == null || query.trim().isEmpty()) {
            return emptySubgraph();
        }
        FindOptions options = opts != null ? opts : new FindOptions();

        Subgraph result = new Subgraph();

        List<String> symbols = extractSymbols(query);
        if (symbols.isEmpty()) {
            return handleNoSymbolsQuery(query);
        }

        List<SearchResult> searchResults = new ArrayList<>();
        Set<String> searchIdSet = new HashSet<>();
        String queryLower = query.toLowerCase();
        boolean isTestQuery = queryLower.contains("test") || queryLower.contains("spec");

        executeExactMatchSearch(symbols, searchResults, searchIdSet, options);
        executePrefixMatchSearch(symbols, searchResults, searchIdSet, options);
        executeFtsSearch(query, searchResults, searchIdSet, options);

        applyTestFilePenalty(searchResults, isTestQuery);

        applyMultiTermCooccurrenceBoost(query, symbols, searchResults);
        executeCamelCaseBoundaryMatch(symbols, query, isTestQuery, searchResults, searchIdSet, options);
        executeCompoundWordMatch(symbols, query, isTestQuery, searchResults, searchIdSet, options);

        return finalizeAndExpandGraph(searchResults, options, result);
    }

    /**
     * 处理无法提取符号的查询——退化为纯文本 FTS 搜索。
     *
     * <p><b>触发场景：</b>当查询文本中没有可识别的代码符号名时（如自然语言查询），
     * 提取阶段无法得到任何符号，此时不再执行复杂的图扩展，直接返回 FTS 搜索结果。
     *
     * <p><b>示例：</b>
     * <pre>
     * 查询: "如何创建用户"
     *   → extractSymbols() 返回空列表 []
     *   → 调用此方法，执行 FTS 搜索
     *   → 返回所有包含 "如何", "创建", "用户" 的节点
     * </pre>
     *
     * <p><b>设计理由：</b>
     * <ul>
     *   <li>自然语言查询无法进行精确匹配和图遍历，直接做 FTS 更高效</li>
     *   <li>所有命中节点都作为入口点（roots），用户可以直接看到所有匹配结果</li>
     *   <li>避免在无意义的图扩展上浪费时间</li>
     * </ul>
     *
     * @param query 用户输入的查询文本
     * @return 包含 FTS 搜索结果的子图，所有节点都是入口点
     */
    private Subgraph handleNoSymbolsQuery(String query) {
        Subgraph result = new Subgraph();
        try {
            List<Node> textResults = queries.searchNodes(query);
            for (Node n : textResults) result.addNode(n);
            for (String id : result.nodes.keySet()) result.addRoot(id);
        } catch (SQLException e) {
            logger.warn("Text search failed: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 执行精确匹配搜索——最高优先级的搜索策略。
     *
     * <p><b>搜索步骤：</b>
     * <ol>
     *   <li><b>完全限定名匹配（byQName）：</b>如 "com.example.UserService"，得分 80</li>
     *   <li><b>简单名匹配（byName）：</b>如 "UserService"，得分 70</li>
     * </ol>
     *
     * <p><b>去重机制：</b>
     * <ul>
     *   <li>同符号的 byQName 和 byName 结果通过 `seen` 集合去重</li>
     *   <li>不同符号的结果通过 `searchIdSet` 去重（跨通道去重）</li>
     * </ul>
     *
     * <p><b>示例：</b>
     * <pre>
     * 查询: "UserService findById"
     *   → symbols = ["UserService", "findById"]
     *   → byQName("UserService") → [Node{name="com.example.UserService"}] score=80
     *   → byName("UserService") → [Node{name="UserService"}, Node{name="UserServiceTest"}] score=70
     *   → byQName("findById") → [Node{name="com.example.UserService.findById"}] score=80
     * </pre>
     *
     * <p><b>后续处理：</b>
     * <ul>
     *   <li>若结果数 > 1，调用 {@link #applyCoLocationBoost(List)} 应用同文件多符号命中加分</li>
     *   <li>截断到 searchLimit * 2，避免过多结果进入后续处理</li>
     * </ul>
     *
     * @param symbols 提取出的符号列表
     * @param searchResults 搜索结果收集列表
     * @param searchIdSet 全局去重集合
     * @param options 搜索选项
     */
    private void executeExactMatchSearch(List<String> symbols, List<SearchResult> searchResults,
            Set<String> searchIdSet, FindOptions options) {
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

            if (searchResults.size() > 1) {
                applyCoLocationBoost(searchResults);
            }

            int exactLimit = (int) Math.ceil(options.searchLimit * 2.0);
            if (searchResults.size() > exactLimit) {
                searchResults.clear();
                searchResults.addAll(new ArrayList<>(searchResults.subList(0, exactLimit)));
                searchIdSet.clear();
                for (SearchResult sr : searchResults) searchIdSet.add(sr.node.getId());
            }
        } catch (SQLException e) {
            logger.warn("Exact match failed: {}", e.getMessage());
        }
    }

    /**
     * 应用 co-location boost（同文件共现加分）——当同一文件中同时命中多个查询符号时额外加权。
     *
     * <p><b>核心思想：</b>如果一个文件同时包含多个查询符号，说明该文件更可能是用户想要的目标。
     * 例如查询 "UserService UserRepository"，若 UserService.java 文件中同时出现这两个符号，
     * 则该文件的节点得分应该高于只包含单个符号的文件。
     *
     * <p><b>加分公式：</b>
     * <pre>
     * score += (symbolCount - 1) * 20
     * </pre>
     * 其中 symbolCount 是该文件命中的不同符号数量。
     *
     * <p><b>示例：</b>
     * <pre>
     * 查询: "UserService UserRepository"
     *
     * 文件 UserService.java:
     *   - 包含 UserService（符号1）
     *   - 包含 UserRepository（符号2）
     *   → symbolCount = 2 → score += 20
     *
     * 文件 UserRepository.java:
     *   - 只包含 UserRepository（符号2）
     *   → symbolCount = 1 → 不加权
     *
     * 文件 OrderService.java:
     *   - 包含 UserService（符号1）
     *   → symbolCount = 1 → 不加权
     * </pre>
     *
     * <p><b>结果：</b>
     * <ul>
     *   <li>UserService.java 中的 UserService 节点：80 + 20 = 100 分</li>
     *   <li>UserService.java 中的 UserRepository 引用：70 + 20 = 90 分</li>
     *   <li>其他文件中的节点：保持原分</li>
     * </ul>
     *
     * @param searchResults 搜索结果列表，分数会被就地修改
     */
    private void applyCoLocationBoost(List<SearchResult> searchResults) {
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

    /**
     * 执行前缀匹配搜索——通过词干变体和 Title-Case 转换扩展搜索面。
     *
     * <p><b>触发场景：</b>当精确匹配未命中时，尝试匹配以查询词开头的类名/接口名。
     * 这适用于用户只记得类名的一部分的场景。
     *
     * <p><b>处理流程：</b>
     * <ol>
     *   <li><b>词干扩展：</b>生成词干变体，如 "builders" → "builder"</li>
     *   <li><b>Title-Case 转换：</b>将词首字母大写（匹配 Java 类名惯例）</li>
     *   <li><b>前缀搜索：</b>查找名称以前缀开头的定义类型节点</li>
     *   <li><b>简洁度加分：</b>短名称优先（如 "Builder" 比 "StringBuilderFactory" 更可能是目标）</li>
     * </ol>
     *
     * <p><b>简洁度加分公式：</b>
     * <pre>
     * brevityBonus = max(0, 10 - (nameLength - termLength) / 3)
     * </pre>
     *
     * <p><b>示例：</b>
     * <pre>
     * 查询: "builder"
     *   → symbols = ["builder"]
     *   → 词干变体: ["builder"]
     *   → Title-Case: "Builder"
     *   → 前缀搜索结果:
     *     - StringBuilder (nameLength=13, termLength=7)
     *       → brevityBonus = max(0, 10 - (13-7)/3) = max(0, 8) = 8
     *       → score = 15 + 8 = 23
     *     - StringBuilderFactory (nameLength=20)
     *       → brevityBonus = max(0, 10 - (20-7)/3) = max(0, 5.67) = 5.67
     *       → score = 15 + 5.67 = 20.67
     *     - Builder (nameLength=7)
     *       → brevityBonus = max(0, 10 - 0) = 10
     *       → score = 15 + 10 = 25 (最高)
     * </pre>
     *
     * <p><b>过滤条件：</b>
     * <ul>
     *   <li>排除已在精确匹配中出现的节点</li>
     *   <li>仅保留定义类型节点（class/interface/enum 等）</li>
     *   <li>名称必须以前缀开头（二次确认）</li>
     * </ul>
     *
     * @param symbols 提取出的符号列表
     * @param searchResults 搜索结果收集列表
     * @param searchIdSet 全局去重集合
     * @param options 搜索选项
     */
    private void executePrefixMatchSearch(List<String> symbols, List<SearchResult> searchResults,
            Set<String> searchIdSet, FindOptions options) {
        try {
            for (String sym : symbols) {
                Set<String> expandedSyms = new LinkedHashSet<>();
                expandedSyms.add(sym);
                for (String variant : SearchUtils.getStemVariants(sym)) {
                    expandedSyms.add(variant);
                }
                for (String expandedSym : expandedSyms) {
                    String titleCased = toTitleCase(expandedSym);
                    if (titleCased.equals(expandedSym) && isTitleCased(expandedSym)) continue;

                    List<Node> prefixResults = queries.searchNodes(titleCased);
                    int count = 0;
                    for (Node n : prefixResults) {
                        if (count++ >= options.searchLimit * 3) break;
                        if (searchIdSet.contains(n.getId())) continue;
                        if (!DEFINITION_KINDS.contains(n.getKind().getValue())) continue;
                        if (!n.getName().toLowerCase().startsWith(titleCased.toLowerCase())) continue;

                        double brevityBonus = computeBrevityBonus(n.getName(), titleCased, 10, 3.0);
                        searchResults.add(new SearchResult(n, 15 + brevityBonus));
                        searchIdSet.add(n.getId());
                    }
                }
            }
        } catch (SQLException e) {
            logger.warn("Prefix match failed: {}", e.getMessage());
        }
    }

    /**
     * 执行 FTS（全文搜索）——使用 SQLite FTS5 引擎进行关键词级别的文本搜索。
     *
     * <p><b>触发场景：</b>当精确匹配和前缀匹配都无法找到足够结果时，FTS 作为兜底搜索。
     * 它可以匹配节点名称、描述、注释等文本内容。
     *
     * <p><b>处理流程：</b>
     * <ol>
     *   <li><b>提取搜索词：</b>将查询拆分为独立搜索词（排除停用词）</li>
     *   <li><b>构建搜索类型集合：</b>包含定义类型 + 方法/字段/变量等可搜索类型</li>
     *   <li><b>逐词搜索：</b>对每个搜索词执行 FTS，记录每个节点命中的词数</li>
     *   <li><b>多词命中加分：</b>匹配的词越多，得分越高</li>
     * </ol>
     *
     * <p><b>多词命中加分公式：</b>
     * <pre>
     * score = baseScore(10) + (termCount - 1) * 5
     * </pre>
     * 例如命中 3 个词的节点得分为 10 + 2*5 = 20 分。
     *
     * <p><b>数据结构：</b>
     * <pre>
     * termResultsMap: Map<String, double[]>
     *   key: nodeId
     *   value: [maxScore, termHits]
     *     maxScore: 该节点的最高基础分（FTS 可能返回同节点多次）
     *     termHits: 该节点命中的搜索词数量
     * </pre>
     *
     * <p><b>示例：</b>
     * <pre>
     * 查询: "user service repository"
     *   → searchTerms = ["user", "service", "repository"]
     *
     * 搜索结果:
     *   - UserService.java:
     *     → 命中 "user" 和 "service" → termHits = 2
     *     → score = 10 + (2-1)*5 = 15
     *
     *   - UserRepository.java:
     *     → 命中 "user" 和 "repository" → termHits = 2
     *     → score = 10 + (2-1)*5 = 15
     *
     *   - OrderService.java:
     *     → 仅命中 "service" → termHits = 1
     *     → score = 10 + 0 = 10
     * </pre>
     *
     * <p><b>过滤条件：</b>
     * <ul>
     *   <li>排除 import 类型（数量庞大且信息量低）</li>
     *   <li>排除已在精确/前缀匹配中出现的节点</li>
     * </ul>
     *
     * @param query 用户输入的查询文本
     * @param searchResults 搜索结果收集列表
     * @param searchIdSet 全局去重集合
     * @param options 搜索选项
     */
    private void executeFtsSearch(String query, List<SearchResult> searchResults,
            Set<String> searchIdSet, FindOptions options) {
        try {
            List<String> searchTerms = SearchUtils.extractSearchTerms(query);
            if (!searchTerms.isEmpty()) {
                Set<String> searchKinds = new LinkedHashSet<>(DEFINITION_KINDS);
                searchKinds.addAll(Arrays.asList(
                    "file", "module", "function", "method", "property", "field",
                    "variable", "constant", "enum_member", "namespace", "export", "route", "component"
                ));

                Map<String, double[]> termResultsMap = new LinkedHashMap<>();
                for (String term : searchTerms) {
                    List<Node> termResults = queries.searchNodes(term,
                        options.searchLimit * 3, searchKinds.toArray(new String[0]));
                    for (Node n : termResults) {
                        double[] entry = termResultsMap.get(n.getId());
                        if (entry == null) {
                            entry = new double[]{0, 1};
                            termResultsMap.put(n.getId(), entry);
                        } else {
                            entry[1]++;
                        }
                        entry[0] = Math.max(entry[0], 10);
                    }
                }

                for (Map.Entry<String, double[]> e : termResultsMap.entrySet()) {
                    if (searchIdSet.contains(e.getKey())) continue;
                    double[] v = e.getValue();
                    double score = v[0] + (v[1] - 1) * 5;
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
    }

    /**
     * 对测试文件中的结果降权——当查询不涉及 test/spec 时，将测试文件结果得分乘以 0.3。
     *
     * <p><b>核心思想：</b>用户通常更关注业务代码而非测试代码。如果查询词不包含 "test" 或 "spec"，
     * 说明用户在寻找业务逻辑，此时测试文件的结果应该被降权，避免干扰正常搜索结果。
     *
     * <p><b>测试文件判断：</b>通过 {@link SearchUtils#isTestFile(String)} 判断，通常基于文件名：
     * <ul>
     *   <li>文件名以 "Test" 结尾：UserServiceTest.java</li>
     *   <li>文件名以 "Spec" 结尾：UserServiceSpec.java</li>
     *   <li>路径包含 "test"：src/test/java/...</li>
     * </ul>
     *
     * <p><b>示例：</b>
     * <pre>
     * 查询: "UserService"（不涉及 test）
     *   → UserService.java: score = 80 → 保持 80
     *   → UserServiceTest.java: score = 70 → 70 * 0.3 = 21
     *
     * 查询: "UserService test"（涉及 test）
     *   → UserService.java: score = 80 → 保持 80
     *   → UserServiceTest.java: score = 70 → 保持 70（不降权）
     * </pre>
     *
     * <p><b>降权效果：</b>测试文件的结果会被推到搜索结果的后面，除非没有其他业务代码结果。
     *
     * @param searchResults 搜索结果列表，分数会被就地修改
     * @param isTestQuery 是否是测试相关查询（包含 "test" 或 "spec"）
     */
    private void applyTestFilePenalty(List<SearchResult> searchResults, boolean isTestQuery) {
        if (!isTestQuery) {
            for (SearchResult sr : searchResults) {
                if (SearchUtils.isTestFile(sr.node.getFilePath())) {
                    sr.score *= 0.3;
                }
            }
        }
    }

    /**
     * 应用多术语共现重排序——根据候选节点同时匹配多个概念组的能力重新评分。
     *
     * <p><b>核心问题：</b>当用户查询包含多个词时（如 "UserService repository find"），
     * 简单的分数排序无法区分"同时匹配多个词"和"只匹配单个词"的节点。只匹配"repository"的节点
     * 可能排在同时匹配"UserService"和"find"的节点前面，这不是用户想要的。
     *
     * <p><b>解决方案：</b>
     * <ol>
     *   <li><b>词项分组：</b>将有子串关系的词归入同一概念组（如 "UserService" 和 "User" 归为同组）</li>
     *   <li><b>统计概念组命中数：</b>每个候选节点命中了多少个不同的概念组</li>
     *   <li><b>重新评分：</b>
     *     <ul>
     *       <li>多组命中（≥2）→ 显著加分：score *= (1 + matchCount * 0.5)</li>
     *       <li>区分性标识符精确匹配 → 保持原分（不受降权影响）</li>
     *       <li>单组命中 + 高分（≥70）→ 大幅降权至 30%</li>
     *       <li>单组命中 + 低分 → 温和降权至 60%</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <p><b>示例：</b>
     * <pre>
     * 查询: "UserService repository find"
     *   → queryTerms = ["user", "service", "repository", "find"]
     *   → termGroups = [["service", "user"], ["repository"], ["find"]]
     *     （"UserService" 包含 "User" 和 "Service"，归入同一组）
     *
     * 候选节点:
     *   - UserService.java: 匹配 ["service","user"] + ["find"] → matchCount = 2
     *     → score *= (1 + 2*0.5) = score * 2
     *
     *   - Repository.java: 只匹配 ["repository"] → matchCount = 1
     *     → 若是高分节点（如精确匹配）: score *= 0.3
     *     → 若是低分节点（如 FTS）: score *= 0.6
     *
     *   - UserRepository.java: 匹配 ["service","user"] + ["repository"] → matchCount = 2
     *     → score *= 2
     * </pre>
     *
     * <p><b>区分性标识符：</b>长且独特的词（如 "AuthenticationManager"），即使单命中
     * 也很可能是用户的目标，不应被降权。
     *
     * @param query 用户输入的查询文本
     * @param symbols 提取出的符号列表
     * @param searchResults 搜索结果列表，分数会被就地修改
     */
    private void applyMultiTermCooccurrenceBoost(String query, List<String> symbols,
            List<SearchResult> searchResults) {
        List<String> queryTermsForBoost = SearchUtils.extractSearchTerms(query);
        if (queryTermsForBoost.size() < 2) return;

        List<List<String>> termGroups = groupTermsBySubstring(queryTermsForBoost);
        Set<String> distinctiveExactMatchIds = collectDistinctiveIds(symbols, searchResults);

        for (SearchResult sr : searchResults) {
            String nameLower = sr.node.getName() != null ? sr.node.getName().toLowerCase() : "";
            String filePath = sr.node.getFilePath() != null ? sr.node.getFilePath().toLowerCase() : "";
            String[] dirSegments = extractDirSegments(filePath);

            int matchCount = countGroupMatches(sr, termGroups, nameLower, dirSegments);

            if (matchCount >= 2) {
                sr.score *= (1 + matchCount * 0.5);
            } else if (!distinctiveExactMatchIds.contains(sr.node.getId())) {
                sr.score *= sr.score >= 70 ? 0.3 : 0.6;
            }
        }
        searchResults.sort((a, b) -> Double.compare(b.score, a.score));
    }

    /**
     * 将查询词按子串关系分组——有子串关系的词归入同一概念组。
     *
     * <p><b>核心思想：</b>"UserService" 和 "User" 本质上是同一个概念（用户服务），
     * 如果一个节点同时匹配这两个词，不应该算作匹配了两个独立的概念组。
     * 因此需要将有子串关系的词归入同一组。
     *
     * <p><b>分组算法：</b>
     * <ol>
     *   <li>将查询词按长度降序排序（长词优先作为组头）</li>
     *   <li>遍历每个词，如果未被分配，创建新组并将其作为组头</li>
     *   <li>检查其他未分配的词，若与当前词有子串关系（互相包含），归入同组</li>
     *   <li>重复直到所有词都被分配</li>
     * </ol>
     *
     * <p><b>示例：</b>
     * <pre>
     * 输入: ["user", "service", "repository", "find", "userservice"]
     * 排序: ["userservice", "repository", "service", "user", "find"]
     *
     * 分组过程:
     *   1. "userservice" → 创建组 ["userservice"]
     *      → "service" 被 "userservice" 包含 → 加入组 → ["userservice", "service"]
     *      → "user" 被 "userservice" 包含 → 加入组 → ["userservice", "service", "user"]
     *
     *   2. "repository" → 创建组 ["repository"]
     *
     *   3. "service" → 已分配，跳过
     *
     *   4. "user" → 已分配，跳过
     *
     *   5. "find" → 创建组 ["find"]
     *
     * 输出: [["userservice", "service", "user"], ["repository"], ["find"]]
     * </pre>
     *
     * <p><b>设计理由：</b>
     * <ul>
     *   <li>长词优先：避免 "user" 先被选为组头，导致 "userservice" 无法加入</li>
     *   <li>双向包含检查：处理 "user" 和 "users" 这种互包含关系</li>
     *   <li>去重保证：每个词只被分配到一个组</li>
     * </ul>
     *
     * @param terms 提取出的搜索词列表
     * @return 分组后的词列表，每个子列表代表一个概念组
     */
    private List<List<String>> groupTermsBySubstring(List<String> terms) {
        List<List<String>> termGroups = new ArrayList<>();
        List<String> sorted = new ArrayList<>(terms);
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
        return termGroups;
    }

    /**
     * 收集区分性标识符的节点 ID——长且独特的词，即使单命中也很可能是用户的目标。
     *
     * <p><b>区分性标识符：</b>满足以下条件之一的标识符：
     * <ul>
     *   <li>长度足够长（如 ≥12 个字符）</li>
     *   <li>包含特定模式（如包含 "Manager"、"Handler"、"Repository" 等）</li>
     * </ul>
     *
     * <p><b>设计理由：</b>在多术语共现重排序中，单组命中的节点会被降权。
     * 但对于像 "AuthenticationManager"、"OrderProcessingService" 这样的长词，
     * 即使只匹配一个词，也很可能是用户想要的精确结果，不应该被降权。
     *
     * <p><b>示例：</b>
     * <pre>
     * 查询: "AuthenticationManager login"
     *   → symbols = ["AuthenticationManager", "login"]
     *   → termGroups = [["authentication", "manager"], ["login"]]
     *
     * 候选节点:
     *   - AuthenticationManager.java: 只匹配 ["authentication", "manager"] → matchCount = 1
     *     → 但 "AuthenticationManager" 是区分性标识符
     *     → 保持原分（不降权）
     *
     *   - LoginService.java: 只匹配 ["login"] → matchCount = 1
     *     → "Login" 不是区分性标识符
     *     → 降权至 30% 或 60%
     * </pre>
     *
     * @param symbols 提取出的符号列表
     * @param searchResults 搜索结果列表
     * @return 区分性标识符的节点 ID 集合
     */
    private Set<String> collectDistinctiveIds(List<String> symbols, List<SearchResult> searchResults) {
        Set<String> distinctiveIds = new HashSet<>();
        for (SearchResult sr : searchResults) {
            String name = sr.node.getName() != null ? sr.node.getName() : "";
            for (String sym : symbols) {
                if (SearchUtils.isDistinctiveIdentifier(sym) && name.equalsIgnoreCase(sym)) {
                    distinctiveIds.add(sr.node.getId());
                }
            }
        }
        return distinctiveIds;
    }

    /**
     * 提取文件路径的目录段数组——用于检查目录名是否匹配查询词。
     *
     * <p><b>用途：</b>在多术语共现重排序中，除了检查类名是否包含查询词，
     * 还需要检查文件所在的目录路径是否匹配查询词。
     *
     * <p><b>示例：</b>
     * <pre>
     * 输入: "com/example/service/UserService.java"
     * 输出: ["com", "example", "service"]
     *
     * 查询: "service"
     *   → 目录段 "service" 精确匹配查询词
     *   → UserService.java 命中概念组
     * </pre>
     *
     * <p><b>处理逻辑：</b>
     * <ol>
     *   <li>找到最后一个 "/" 的位置（文件名开始处）</li>
     *   <li>截取路径部分（不含文件名）</li>
     *   <li>按 "/" 分割得到目录段数组</li>
     * </ol>
     *
     * @param filePath 文件路径
     * @return 目录段数组，如果路径为空或无效则返回空数组
     */
    private String[] extractDirSegments(String filePath) {
        if (filePath == null || filePath.isEmpty()) return new String[0];
        int lastSep = filePath.lastIndexOf('/');
        return lastSep >= 0 ? filePath.substring(0, lastSep).split("/") : new String[0];
    }

    /**
     * 统计候选节点命中了多少个不同的概念组。
     *
     * <p><b>匹配规则：</b>一个概念组只要有任意一个词被匹配，整个组就算命中。
     * 匹配方式有两种：
     * <ul>
     *   <li><b>类名匹配：</b>类名（小写）包含查询词</li>
     *   <li><b>目录匹配：</b>文件所在的目录段精确等于查询词</li>
     * </ul>
     *
     * <p><b>示例：</b>
     * <pre>
     * termGroups = [["user", "service"], ["repository"], ["find"]]
     *
     * 候选节点: UserService.java（路径: com/example/service/UserService.java）
     *   - nameLower = "userservice"
     *   - dirSegments = ["com", "example", "service"]
     *
     * 匹配过程:
     *   1. 组 ["user", "service"]:
     *      → "userservice" 包含 "user" → groupMatches = true
     *      → matchCount = 1
     *
     *   2. 组 ["repository"]:
     *      → "userservice" 不包含 "repository"
     *      → 目录段也没有 "repository"
     *      → groupMatches = false
     *
     *   3. 组 ["find"]:
     *      → "userservice" 不包含 "find"
     *      → 目录段也没有 "find"
     *      → groupMatches = false
     *
     * 结果: matchCount = 1
     * </pre>
     *
     * <p><b>另一个示例：</b>
     * <pre>
     * 候选节点: UserRepository.java（路径: com/example/repository/UserRepository.java）
     *   - nameLower = "userrepository"
     *   - dirSegments = ["com", "example", "repository"]
     *
     * 匹配过程:
     *   1. 组 ["user", "service"]:
     *      → "userrepository" 包含 "user" → matchCount = 1
     *
     *   2. 组 ["repository"]:
     *      → 目录段 "repository" 精确等于 "repository" → matchCount = 2
     *
     * 结果: matchCount = 2 → 多组命中，获得加分
     * </pre>
     *
     * @param sr 候选搜索结果
     * @param termGroups 概念组列表
     * @param nameLower 节点名称（小写）
     * @param dirSegments 目录段数组
     * @return 命中的概念组数量
     */
    private int countGroupMatches(SearchResult sr, List<List<String>> termGroups,
            String nameLower, String[] dirSegments) {
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
        return matchCount;
    }

    /**
     * 执行 CamelCase 边界匹配——发现类名中包含查询词但不在开头的节点。
     *
     * <p><b>核心问题：</b>精确匹配和前缀匹配只能找到以查询词开头的类名。
     * 但用户可能想找 "AuthenticationManager"、"SessionManager" 这样的类，
     * 而查询词只是 "Manager"。这时需要在类名的中间或末尾查找匹配。
     *
     * <p><b>CamelCase 边界约束：</b>匹配位置前一个字符必须是大写字母。
     * 这确保 "Manager" 匹配 "AuthenticationManager" 而不是 "Managerial"。
     * 因为 CamelCase 命名规范中，单词边界由大写字母标记。
     *
     * <p><b>处理流程：</b>
     * <ol>
     *   <li><b>Title-Case 转换：</b>将查询词首字母大写</li>
     *   <li><b>子串搜索：</b>查找名称中包含该词的定义类型节点</li>
     *   <li><b>CamelCase 边界检测：</b>确保匹配位置前是大写字母</li>
     *   <li><b>跳过开头匹配：</b>开头匹配已在精确/前缀匹配中处理</li>
     *   <li><b>多词命中追踪：</b>同一节点被多个符号词命中时额外加分</li>
     * </ol>
     *
     * <p><b>多词命中加分公式：</b>
     * <pre>
     * score = baseScore * (1 + termCount) + (termCount - 1) * 30
     * </pre>
     * 被 2 个词命中的节点得分为 baseScore * 3 + 30。
     *
     * <p><b>示例：</b>
     * <pre>
     * 查询: "manager"
     *   → titleCased = "Manager"
     *
     * 搜索结果:
     *   - AuthenticationManager:
     *     → "Manager" 在位置 14，前面字符 'n' 是小写 → 不满足边界约束？
     *     → 不对！应该检查的是匹配位置前一个字符是否是字母
     *     → idx=14, name.charAt(13)='n' → 是字母 → 通过
     *     → brevityBonus = max(0, 6 - (21-7)/4) = max(0, 2) = 2
     *     → score = 8 + 2 + pathScore
     *
     *   - Managerial:
     *     → "Manager" 在位置 0 → idx=0 → 跳过（开头匹配已处理）
     *
     *   - SessionManager:
     *     → "Manager" 在位置 7，前面字符 'n' 是小写字母 → 通过
     *     → score = 8 + brevityBonus + pathScore
     * </pre>
     *
     * <p><b>CamelCase 边界检测详解：</b>
     * <pre>
     * "AuthenticationManager" 中查找 "Manager":
     *   idx = 14
     *   name.charAt(13) = 'n'（小写字母）→ 满足条件 → 匹配
     *
     * "SessionManager" 中查找 "Manager":
     *   idx = 7
     *   name.charAt(6) = 'n'（小写字母）→ 满足条件 → 匹配
     *
     * "ManagerFactory" 中查找 "Manager":
     *   idx = 0 → 跳过（开头匹配）
     * </pre>
     *
     * @param symbols 提取出的符号列表
     * @param query 用户输入的查询文本
     * @param isTestQuery 是否是测试相关查询
     * @param searchResults 搜索结果收集列表
     * @param searchIdSet 全局去重集合
     * @param options 搜索选项
     */
    private void executeCamelCaseBoundaryMatch(List<String> symbols, String query, boolean isTestQuery,
            List<SearchResult> searchResults, Set<String> searchIdSet, FindOptions options) {
        if (symbols.isEmpty()) return;

        Set<String> camelSearchedTerms = new HashSet<>();
        Map<String, double[]> camelNodeTerms = new LinkedHashMap<>();

        for (String sym : symbols) {
            String titleCased = toTitleCase(sym);
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
                    if (idx > 0 && !Character.isLetter(name.charAt(idx - 1))) continue;
                    if (searchIdSet.contains(n.getId())) continue;
                    if (SearchUtils.isTestFile(n.getFilePath()) && !isTestQuery) continue;

                    int pathScore = SearchUtils.scorePathRelevance(n.getFilePath(), query);
                    double brevityBonus = computeBrevityBonus(name, titleCased, 6, 4.0);
                    double score = 8 + brevityBonus + pathScore;

                    double[] entry = camelNodeTerms.get(n.getId());
                    if (entry == null) {
                        entry = new double[]{score, 1};
                        camelNodeTerms.put(n.getId(), entry);
                    } else {
                        entry[1]++;
                        entry[0] = Math.max(entry[0], score);
                    }
                }
            } catch (SQLException ignored) {}
        }

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

    /**
     * 执行复合词匹配——当 ≥2 个查询词同时作为子串出现在同一类名中时加分。
     *
     * <p><b>与 CamelCase 匹配的区别：</b>
     * <ul>
     *   <li><b>CamelCase 匹配：</b>要求匹配位置在 CamelCase 边界（前面是大写字母）</li>
     *   <li><b>复合词匹配：</b>不要求边界，接受任意位置子串匹配</li>
     * </ul>
     *
     * <p><b>触发条件：</b>必须有 ≥2 个不同的查询词同时命中同一个类名。
     * 这比 CamelCase 更宽松但要求多词共现，因此作为补充召回通道。
     *
     * <p><b>得分公式：</b>
     * <pre>
     * score = 10 + (termCount - 1) * 20 + pathScore + brevityBonus
     * </pre>
     * 其中 termCount 是命中的查询词数量。
     *
     * <p><b>示例：</b>
     * <pre>
     * 查询: "cache manager"
     *   → symbols = ["cache", "manager"]
     *
     * 候选节点:
     *   - CacheManager:
     *     → 类名包含 "Cache"（titleCased）→ 命中词1
     *     → 类名包含 "Manager"（titleCased）→ 命中词2
     *     → termCount = 2 → 满足条件
     *     → score = 10 + 20 + pathScore + brevityBonus
     *
     *   - LruCache:
     *     → 类名包含 "Cache" → 命中词1
     *     → 类名不包含 "Manager" → termCount = 1 → 不满足条件
     *     → 被过滤掉
     *
     *   - SessionManager:
     *     → 类名包含 "Manager" → 命中词2
     *     → 类名不包含 "Cache" → termCount = 1 → 不满足条件
     *     → 被过滤掉
     * </pre>
     *
     * <p><b>另一个示例：</b>
     * <pre>
     * 查询: "user service repository"
     *   → symbols = ["user", "service", "repository"]
     *
     * 候选节点: UserServiceRepository
     *   → 类名包含 "User" → 命中词1
     *   → 类名包含 "Service" → 命中词2
     *   → 类名包含 "Repository" → 命中词3
     *   → termCount = 3 → 满足条件
     *   → score = 10 + 2*20 + pathScore + brevityBonus = 50 + ...
     * </pre>
     *
     * <p><b>设计理由：</b>当用户输入多个词时，很可能是在寻找一个包含这些概念的综合类。
     * 复合词匹配可以发现这类聚合命名的类，补充其他搜索通道的不足。
     *
     * @param symbols 提取出的符号列表
     * @param query 用户输入的查询文本
     * @param isTestQuery 是否是测试相关查询
     * @param searchResults 搜索结果收集列表
     * @param searchIdSet 全局去重集合
     * @param options 搜索选项
     */
    private void executeCompoundWordMatch(List<String> symbols, String query, boolean isTestQuery,
            List<SearchResult> searchResults, Set<String> searchIdSet, FindOptions options) {
        if (symbols.size() < 2) return;

        Map<String, double[]> compoundTermMap = new LinkedHashMap<>();
        for (String sym : symbols) {
            String titleCased = toTitleCase(sym);
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
            if (v[1] < 2) continue;
            try {
                Node node = queries.getNode(e.getKey());
                if (node != null) {
                    int pathScore = SearchUtils.scorePathRelevance(node.getFilePath(), query);
                    double brevityBonus = computeBrevityBonus(node.getName(), "", 6, 8.0);
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

    /**
     * 最终排序、过滤，并执行 BFS 图扩展——搜索管道的最后一步。
     *
     * <p><b>处理流程：</b>
     * <ol>
     *   <li><b>统一排序：</b>按 score 降序排序所有搜索通道的结果</li>
     *   <li><b>硬上限截断：</b>保留 searchLimit * 3 条，防止过多低质量候选</li>
     *   <li><b>分数阈值过滤：</b>丢弃 minScore 以下的候选</li>
     *   <li><b>入口点数量限制：</b>最终保留的入口点不超过 searchLimit</li>
     *   <li><b>添加入口点：</b>将过滤后的候选节点添加为子图入口点（roots）</li>
     *   <li><b>BFS 图扩展：</b>从入口点出发扩展相邻节点和边</li>
     * </ol>
     *
     * <p><b>示例：</b>
     * <pre>
     * options.searchLimit = 10, options.minScore = 0.2
     *
     * 步骤1: searchResults = 50 条 → 排序
     * 步骤2: 截断到 30 条
     * 步骤3: 过滤掉 score < 0.2 的 → 剩余 25 条
     * 步骤4: 截断到 10 条（入口点上限）
     * 步骤5: 这 10 个节点成为子图的 roots
     * 步骤6: BFS 扩展每个 root 的相邻节点
     * </pre>
     *
     * <p><b>设计理由：</b>
     * <ul>
     *   <li>多级过滤确保只有高质量的候选进入图扩展阶段</li>
     *   <li>入口点数量限制控制下游图扩展的复杂度</li>
     *   <li>入口点是图扩展的起点——BFS 将从这些节点出发向外辐射</li>
     * </ul>
     *
     * @param searchResults 所有搜索通道的结果列表
     * @param options 搜索选项
     * @param result 结果子图
     * @return 包含入口点和图扩展结果的完整子图
     */
    private Subgraph finalizeAndExpandGraph(List<SearchResult> searchResults,
            FindOptions options, Subgraph result) {
        searchResults.sort((a, b) -> Double.compare(b.score, a.score));
        if (searchResults.size() > options.searchLimit * 3) {
            searchResults = new ArrayList<>(searchResults.subList(0, options.searchLimit * 3));
        }

        List<SearchResult> filtered = new ArrayList<>();
        for (SearchResult sr : searchResults) {
            if (sr.score >= options.minScore) filtered.add(sr);
        }

        if (filtered.size() > options.searchLimit) {
            filtered = new ArrayList<>(filtered.subList(0, options.searchLimit));
        }

        for (SearchResult sr : filtered) {
            result.addNode(sr.node);
            result.addRoot(sr.node.getId());
        }

        executeBfsGraphExpansion(result, options);
        return result;
    }

    /**
     * 从入口点出发进行 BFS 图扩展——补全相邻节点和边信息，使子图更完整。
     *
     * <p><b>核心思想：</b>搜索阶段只找到直接相关的节点（入口点），但用户可能还想看到
     * 这些节点的调用者、被调用者、父类、接口等相关代码。BFS 图扩展负责从入口点出发，
     * 向外辐射一定深度，补全这些关联信息。
     *
     * <p><b>扩展策略：</b>
     * <ul>
     *   <li><b>方向：</b>双向（both）——同时追踪调用者和被调用者</li>
     *   <li><b>深度：</b>由 traversalDepth 控制（默认值通常为 1-2 层）</li>
     *   <li><b>配额：</b>每个入口点分配 maxNodes / 入口点数量 的节点配额</li>
     *   <li><b>边类型：</b>仅包含语义关系边（CALLS/REFERENCES/EXTENDS/IMPLEMENTS/IMPORTS 等），
     *       排除 CONTAINS（AST 父子关系），避免返回整个文件的节点树</li>
     * </ul>
     *
     * <p><b>示例：</b>
     * <pre>
     * 入口点: UserService.java
     *
     * BFS 扩展结果:
     *   → UserService 的父类 BaseService（EXTENDS）
     *   → UserService 实现的接口 UserServiceInterface（IMPLEMENTS）
     *   → UserService 调用的 UserRepository（CALLS）
     *   → 调用 UserService 的 UserController（CALLS）
     *   → UserService 引用的 UserDTO（REFERENCES）
     * </pre>
     *
     * <p><b>配额分配：</b>
     * <pre>
     * options.maxNodes = 100
     * 入口点数量 = 5
     * 每个入口点配额 = 100 / 5 = 20
     *
     * 这确保不会有单个入口点占用过多节点，导致其他入口点无法充分扩展。
     * </pre>
     *
     * <p><b>为什么排除 CONTAINS 边？</b>
     * <ul>
     *   <li>CONTAINS 边代表 AST 父子关系（类包含方法、方法包含语句等）</li>
     *   <li>如果包含 CONTAINS 边，BFS 会遍历整个文件的 AST 树</li>
     *   <li>这会导致返回大量低价值的内部节点，淹没真正相关的代码</li>
     *   <li>用户更关心的是跨文件的调用关系，而非同一文件内的 AST 结构</li>
     * </ul>
     *
     * <p><b>返回的子图包含：</b>
     * <ol>
     *   <li>直接相关的节点（入口点）</li>
     *   <li>它们的直接调用者/被调用者</li>
     *   <li>它们引用的类型、继承的父类、实现的接口</li>
     *   <li>所有相关的边信息</li>
     * </ol>
     *
     * @param result 结果子图，包含入口点
     * @param options 搜索选项，包含扩展参数
     */
    private void executeBfsGraphExpansion(Subgraph result, FindOptions options) {
        if (result.nodeCount() == 0) return;

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

    /**
     * 将字符串转换为 Title-Case（首字母大写，其余小写）。
     * <p>例如："userService" → "UserService", "builder" → "Builder"</p>
     */
    private String toTitleCase(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }

    /**
     * 判断字符串是否已经是 Title-Case（首字母大写）。
     */
    private boolean isTitleCased(String str) {
        return str != null && !str.isEmpty() && Character.isUpperCase(str.charAt(0));
    }

    /**
     * 计算简洁度加分（brevity bonus）：名称越短，加分越高。
     * <p>公式：max(0, base - (名称长度 - 搜索词长度) / divisor)</p>
     */
    private double computeBrevityBonus(String name, String searchTerm, double base, double divisor) {
        return Math.max(0, base - (name.length() - searchTerm.length()) / divisor);
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
