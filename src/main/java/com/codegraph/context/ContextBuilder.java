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
        // 空查询保护：避免对 null 或纯空白查询执行无效的数据库搜索
        if (query == null || query.trim().isEmpty()) {
            return emptySubgraph();
        }
        FindOptions options = opts != null ? opts : new FindOptions();

        Subgraph result = new Subgraph();

        // ==================== Step 1: 符号提取 ====================
        // 从查询文本中提取可识别的代码符号名（如类名、方法名）。
        // extractSymbols 会过滤掉常见关键字、操作符和自然语言词汇。
        // 若无法提取任何符号，退化为纯文本 FTS 搜索，将所有命中节点
        // 直接作为入口点返回（不再进行后续的图扩展）。
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

        // ==================== 分数驱动的搜索管道 ====================
        // 各搜索步骤（精确匹配、前缀匹配、FTS、CamelCase 等）产生的结果
        // 统一用 score 字段排序，保证多通道结果可比。
        // searchIdSet 用于去重：同一个节点不会从多个通道重复添加。
        List<SearchResult> searchResults = new ArrayList<>();
        Set<String> searchIdSet = new HashSet<>();
        String queryLower = query.toLowerCase();
        // 检测查询是否为测试相关搜索——若是，则不对测试文件降权
        boolean isTestQuery = queryLower.contains("test") || queryLower.contains("spec");

        // ==================== Step 2: 精确匹配（Exact Match）====================
        // 最高优先级匹配策略。依次执行：
        //   1. 完全限定名匹配（byQName）：如 "com.example.MyClass" → score=80
        //   2. 简单名匹配（byName）：如 "MyClass" → score=70
        // 同文件多符号命中时额外加权（co-location boost），因为多符号共存
        // 暗示该文件与查询意图更相关。
        try {
            for (String sym : symbols) {
                List<Node> byQName = queries.getNodesByQualifiedName(sym);
                List<Node> byName = queries.getNodesByName(sym);
                // seen 集合确保同一个 sym 的 byQName 和 byName 结果不重复添加
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

            // Co-location boost：统计每个文件中命中的不同符号数量。
            // 例如查询 "UserService UserRepository"，若 UserService.java 同时
            // 命中两个符号，则该文件的节点得分额外 +20/符号对。
            // 这反映了"同一文件含多个查询符号 → 该文件更可能是目标"的直觉。
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
                // co-location 加权后重新排序，确保被加权的节点靠前
                searchResults.sort((a, b) -> Double.compare(b.score, a.score));
            }

            // 精确匹配结果截断：保留 searchLimit * 2 条以控制后续处理开销。
            // 注意截断后需重新构建 searchIdSet，以保持去重集合与结果列表一致。
            int exactLimit = (int) Math.ceil(options.searchLimit * 2.0);
            if (searchResults.size() > exactLimit) {
                searchResults = new ArrayList<>(searchResults.subList(0, exactLimit));
                searchIdSet.clear();
                for (SearchResult sr : searchResults) searchIdSet.add(sr.node.getId());
            }
        } catch (SQLException e) {
            logger.warn("Exact match failed: {}", e.getMessage());
        }

        // ==================== Step 3: 前缀匹配（Prefix Match）====================
        // 针对精确匹配未命中的符号，通过词干变体和 Title-Case 转换进行前缀搜索。
        //
        // 处理流程：
        //   1. 生成词干变体（如 "builder" → "build"），扩展搜索面
        //   2. Title-Case 转换（首字母大写），因为 Java 类名一般为 PascalCase
        //   3. 仅匹配以 Title-Cased 词开头的定义类型节点（class/interface/enum 等）
        //
        // brevityBonus（简洁度加分）：偏爱短类名，因为短名意味着更高的特异性。
        // 例如查询 "Builder" 时，StringBuilder 比 StringBuilderFactory 更可能是目标。
        try {
            for (String sym : symbols) {
                // 为每个符号生成词干变体（如 "users" → "user"），
                // 扩大搜索覆盖范围以包容拼写变化和复数形式
                Set<String> expandedSyms = new LinkedHashSet<>();
                expandedSyms.add(sym);
                for (String variant : SearchUtils.getStemVariants(sym)) {
                    expandedSyms.add(variant);
                }
                for (String expandedSym : expandedSyms) {
                    // Title-Case 转换：将词首字母大写，匹配 Java 类名惯例
                    String titleCased = expandedSym.substring(0, 1).toUpperCase()
                        + expandedSym.substring(1).toLowerCase();
                    // 若原词已是 Title-Case 则跳过（避免重复搜索）
                    if (titleCased.equals(expandedSym) && Character.isUpperCase(expandedSym.charAt(0))) continue;

                    List<Node> prefixResults = queries.searchNodes(titleCased);
                    int count = 0;
                    for (Node n : prefixResults) {
                        // 限制每个搜索词的结果数量，防止低质量候选过多
                        if (count++ >= options.searchLimit * 3) break;
                        // 跳过已在精确匹配中出现的节点
                        if (searchIdSet.contains(n.getId())) continue;
                        // 仅保留定义类型的节点（class/interface/enum 等）
                        if (!DEFINITION_KINDS.contains(n.getKind().getValue())) continue;
                        // 名称必须以前缀开头（二次确认）
                        if (!n.getName().toLowerCase().startsWith(titleCased.toLowerCase())) continue;

                        // brevityBonus：名越短分越高。
                        // 公式：max(0, 10 - (名称长度 - 搜索词长度) / 3)
                        // 例如搜索词 "Builder"（7字符），名称 "StringBuilder"（13字符）
                        //   → bonus = max(0, 10 - (13-7)/3) = max(0, 8) = 8
                        double brevityBonus = Math.max(0, 10 - (n.getName().length() - titleCased.length()) / 3.0);
                        // 基础分 15 + 简洁度加分
                        searchResults.add(new SearchResult(n, 15 + brevityBonus));
                        searchIdSet.add(n.getId());
                    }
                }
            }
        } catch (SQLException e) {
            logger.warn("Prefix match failed: {}", e.getMessage());
        }

        // ==================== Step 4: FTS（全文搜索）====================
        // 使用 SQLite FTS5 引擎进行关键词级别的文本搜索。
        //
        // 策略：
        //   - 将查询拆分为独立搜索词（排除常见停用词）
        //   - 限制搜索的节点类型，排除 import 类型（它们会淹没搜索结果）
        //   - 逐词搜索并累积多词命中计数
        //   - 节点匹配的词越多，最终得分越高（多词 boost）
        //
        // 多词命中 boost 公式：score = baseScore(10) + (termCount - 1) * 5
        // 例如命中 3 个词的节点得 10 + 2*5 = 20 分
        try {
            List<String> searchTerms = SearchUtils.extractSearchTerms(query);
            if (!searchTerms.isEmpty()) {
                // 构建允许的节点类型集合：在定义类型基础上，
                // 扩展加入方法、属性、变量等可被搜索到的节点类型。
                // 排除 import 类型是因为它们数量庞大且信息量低。
                Set<String> searchKinds = new LinkedHashSet<>(DEFINITION_KINDS);
                searchKinds.addAll(Arrays.asList(
                    "file", "module", "function", "method", "property", "field",
                    "variable", "constant", "enum_member", "namespace", "export", "route", "component"
                ));

                // termResultsMap 结构：nodeId → [maxScore, termHits]
                // 对每个搜索词分别执行 FTS，记录每个节点命中的词数和最高分
                Map<String, double[]> termResultsMap = new LinkedHashMap<>();
                for (String term : searchTerms) {
                    List<Node> termResults = queries.searchNodes(term,
                        options.searchLimit * 3, searchKinds.toArray(new String[0]));
                    for (Node n : termResults) {
                        double[] entry = termResultsMap.get(n.getId());
                        if (entry == null) {
                            // 首次遇到此节点：初始化 score=0, 命中词数=1
                            entry = new double[]{0, 1};
                            termResultsMap.put(n.getId(), entry);
                        } else {
                            // 已有此节点：增加命中词数计数
                            entry[1]++;
                        }
                        // 保留当前词的最高基础分（FTS 可能返回同节点多次）
                        entry[0] = Math.max(entry[0], 10);
                    }
                }

                // 将多词命中结果转为 SearchResult 并加入候选列表
                for (Map.Entry<String, double[]> e : termResultsMap.entrySet()) {
                    if (searchIdSet.contains(e.getKey())) continue;
                    double[] v = e.getValue();
                    double score = v[0] + (v[1] - 1) * 5; // 基础分 + 多词 boost
                    // 需要通过 getNode 获取完整节点信息
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

        // 测试文件降权：当查询不涉及 test/spec 时，将测试文件中的结果
        // 得分乘以 0.3，避免测试代码干扰正常业务代码搜索。
        if (!isTestQuery) {
            for (SearchResult sr : searchResults) {
                if (SearchUtils.isTestFile(sr.node.getFilePath())) {
                    sr.score *= 0.3;
                }
            }
        }

        // ==================== Step 5a: 多术语共现重排序 ====================
        // 当查询包含 ≥2 个搜索词时，根据候选节点同时匹配多个词项的能力重新评分。
        //
        // 核心思想：
        //   - 将查询词按子串关系分组（"UserService" 和 "User" 视为同一概念组）
        //   - 统计候选节点命中了多少个不同的概念组
        //   - 多组命中 → 显著加分（该节点是"交汇点"，更可能是用户想要的）
        //   - 单组命中 → 区分性标识符保持原分，普通词降权
        //
        // 这解决了多词查询的核心问题："UserService repository find" 中，
        // 只匹配 "repository" 的节点不应排在同时匹配 "UserService" 的节点前面。
        List<String> queryTermsForBoost = SearchUtils.extractSearchTerms(query);
        if (queryTermsForBoost.size() >= 2) {
            // 词项分组：将子串关系的词归入同一概念组。
            // 例如 ["UserService", "User", "Repository"] → [["UserService","User"], ["Repository"]]
            // 这样 "UserService" 同时匹配两个词不算两个独立概念组。
            List<List<String>> termGroups = new ArrayList<>();
            List<String> sorted = new ArrayList<>(queryTermsForBoost);
            sorted.sort((a, b) -> b.length() - a.length()); // 按长度降序，长者优先作为组头
            Set<String> assigned = new HashSet<>();
            for (String term : sorted) {
                if (assigned.contains(term)) continue;
                List<String> group = new ArrayList<>();
                group.add(term);
                assigned.add(term);
                for (String other : sorted) {
                    if (assigned.contains(other)) continue;
                    // 若两个词有子串关系（如 "UserService" 包含 "User"），归为同组
                    if (term.contains(other) || other.contains(term)) {
                        group.add(other);
                        assigned.add(other);
                    }
                }
                termGroups.add(group);
            }

            // 收集区分性标识符（长词、无歧义的精确匹配）的节点 ID。
            // 区分性标识符如 "AuthenticationManager" — 长且独特，即使单命中
            // 也很有可能是用户的目标，不应被降权。
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

            // 根据命中的概念组数量重新计算每个候选节点的分数
            for (SearchResult sr : searchResults) {
                String nameLower = sr.node.getName() != null ? sr.node.getName().toLowerCase() : "";
                String filePath = sr.node.getFilePath() != null ? sr.node.getFilePath().toLowerCase() : "";
                // 解析文件路径的目录段，用于检查目录名是否匹配查询词
                // 例如查询 "service" → 路径 "com/example/service/UserService.java" 应该命中
                String[] dirSegments = new String[0];
                int lastSep = filePath.lastIndexOf('/');
                if (lastSep >= 0) {
                    dirSegments = filePath.substring(0, lastSep).split("/");
                }

                // 统计该节点命中了多少个不同的概念组
                int matchCount = 0;
                for (List<String> group : termGroups) {
                    boolean groupMatches = false;
                    for (String term : group) {
                        // 检查类名是否包含该词
                        if (nameLower.contains(term)) { groupMatches = true; break; }
                        // 检查目录路径段是否精确匹配该词
                        for (String seg : dirSegments) {
                            if (seg.equals(term)) { groupMatches = true; break; }
                        }
                        if (groupMatches) break;
                    }
                    if (groupMatches) matchCount++;
                }

                if (matchCount >= 2) {
                    // 多组命中 → 显著加分：2 组 ×2, 3 组 ×2.5, 4 组 ×3
                    sr.score *= (1 + matchCount * 0.5);
                } else if (distinctiveExactMatchIds.contains(sr.node.getId())) {
                    // 区分性标识符精确匹配，保持原分（如 "AuthenticationManager"）
                } else if (sr.score >= 70) {
                    // 高分但仅单组命中（普通词精确匹配无佐证）→ 大幅降权至 30%
                    sr.score *= 0.3;
                } else {
                    // 低分单组命中 → 温和降权至 60%
                    sr.score *= 0.6;
                }
            }
            searchResults.sort((a, b) -> Double.compare(b.score, a.score));
        }

        // ==================== Step 5b: CamelCase 边界匹配 ====================
        // 通过 LIKE 子串查询发现类名中包含查询词但不在开头的节点。
        //
        // 例如查询 "Manager" 时，精确匹配只能找到名为 "Manager" 的类，
        // 但 "AuthenticationManager"、"SessionManager" 等可能才是用户想要的。
        //
        // CamelCase 边界约束：匹配位置前一个字符必须是大写字母，
        // 确保 "Manager" 匹配 "AuthenticationManager" 而不是 "Managerial"。
        // 这是因为 CamelCase 命名规范中，单词边界由大写字母标记。
        if (!symbols.isEmpty()) {
            Set<String> camelSearchedTerms = new HashSet<>(); // 避免重复搜索同义词干
            Map<String, double[]> camelNodeTerms = new LinkedHashMap<>(); // nodeId → [maxScore, termCount]

            for (String sym : symbols) {
                String titleCased = sym.substring(0, 1).toUpperCase() + sym.substring(1).toLowerCase();
                if (titleCased.length() < 3) continue; // 过短词 CamelCase 匹配无意义
                String termKey = titleCased.toLowerCase();
                if (camelSearchedTerms.contains(termKey)) continue;
                camelSearchedTerms.add(termKey);

                try {
                    // CamelCase match=true：要求匹配位置在 CamelCase 边界
                    List<Node> likeResults = queries.findNodesByNameSubstring(titleCased, 200,
                        DEFINITION_KINDS.toArray(new String[0]), true);
                    for (Node n : likeResults) {
                        String name = n.getName();
                        int idx = name.indexOf(titleCased);
                        if (idx <= 0) continue; // 跳过开头匹配（已在精确/前缀匹配中处理）
                        // CamelCase 边界检测：匹配位置前的字符必须是大写字母
                        // 例如 "Manager" 在 "AuthenticationManager" 中 idx=14，
                        //   name.charAt(13) = 'n'（小写），不通过 → 拒绝
                        // 正确匹配：name.charAt(idx-1) 应为大写字母
                        if (idx > 0 && !Character.isLetter(name.charAt(idx - 1))) continue;
                        if (searchIdSet.contains(n.getId())) continue;
                        if (SearchUtils.isTestFile(n.getFilePath()) && !isTestQuery) continue;

                        // 路径相关性加分：文件路径中包含查询词 → +pathScore
                        int pathScore = SearchUtils.scorePathRelevance(n.getFilePath(), query);
                        // 简洁度加分：较短类名得更多分
                        double brevityBonus = Math.max(0, 6 - (name.length() - titleCased.length()) / 4.0);
                        double score = 8 + brevityBonus + pathScore;

                        // 多词命中追踪：同一节点被多个符号词命中 → 后续 boost
                        double[] entry = camelNodeTerms.get(n.getId());
                        if (entry == null) {
                            entry = new double[]{score, 1};
                            camelNodeTerms.put(n.getId(), entry);
                        } else {
                            entry[1]++; // 多词命中计数
                            entry[0] = Math.max(entry[0], score); // 保留最高分
                        }
                    }
                } catch (SQLException ignored) {}
            }

            // 将 CamelCase 结果合并入最终候选列表，含多词 boost
            List<SearchResult> camelResults = new ArrayList<>();
            for (Map.Entry<String, double[]> e : camelNodeTerms.entrySet()) {
                double[] v = e.getValue();
                // 多词 boost：score * (1 + termCount) + (termCount - 1) * 30
                // 被 2 个词命中的节点得分为 baseScore * 3 + 30
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

        // ==================== Step 5c: 复合词匹配 ====================
        // 当 ≥2 个查询词同时作为子串出现在同一类名中时加分。
        //
        // 与 CamelCase 不同，复合词匹配不要求 CamelCase 边界，
        // 而是检测同一类名是否包含多个查询词的子串。
        //
        // 例如查询 "cache manager"：
        //   "CacheManager"   → 匹配 "Cache" + "Manager" → 2 词命中
        //   "LruCache"       → 仅匹配 "Cache" → 不满足 ≥2 词条件
        //
        // 这比 CamelCase 更宽松但要求多词共现，因此作为补充召回通道。
        if (symbols.size() >= 2) {
            Map<String, double[]> compoundTermMap = new LinkedHashMap<>(); // nodeId → [_, termCount]
            for (String sym : symbols) {
                String titleCased = sym.substring(0, 1).toUpperCase() + sym.substring(1).toLowerCase();
                if (titleCased.length() < 3) continue;

                try {
                    // CamelCase match=false：不要求匹配位置在边界，接受任意位置子串
                    List<Node> likeResults = queries.findNodesByNameSubstring(titleCased, 200,
                        DEFINITION_KINDS.toArray(new String[0]), false);
                    for (Node n : likeResults) {
                        if (searchIdSet.contains(n.getId())) continue;
                        if (SearchUtils.isTestFile(n.getFilePath()) && !isTestQuery) continue;

                        double[] entry = compoundTermMap.get(n.getId());
                        if (entry == null) {
                            entry = new double[]{0, 1}; // score 后续统一计算
                            compoundTermMap.put(n.getId(), entry);
                        } else {
                            entry[1]++; // 增加命中词计数
                        }
                    }
                } catch (SQLException ignored) {}
            }

            List<SearchResult> compoundResults = new ArrayList<>();
            for (Map.Entry<String, double[]> e : compoundTermMap.entrySet()) {
                double[] v = e.getValue();
                if (v[1] < 2) continue; // 必须 ≥2 个不同查询词命中
                try {
                    Node node = queries.getNode(e.getKey());
                    if (node != null) {
                        int pathScore = SearchUtils.scorePathRelevance(node.getFilePath(), query);
                        double brevityBonus = Math.max(0, 6 - node.getName().length() / 8.0);
                        // 得分 = 10 + (命中词数-1) * 20 + 路径分 + 简洁度分
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

        // ==================== 最终排序、过滤与截断 ====================
        // 所有搜索通道的结果汇集后，按 score 降序统一排序。
        searchResults.sort((a, b) -> Double.compare(b.score, a.score));
        // 硬上限截断：searchLimit * 3，防止过多低质量候选进入图扩展阶段
        if (searchResults.size() > options.searchLimit * 3) {
            searchResults = new ArrayList<>(searchResults.subList(0, options.searchLimit * 3));
        }

        // 按最低分数阈值过滤：minScore 以下的候选直接丢弃。
        // 这消除了所有搜索通道中产生的低置信度噪音。
        List<SearchResult> filtered = new ArrayList<>();
        for (SearchResult sr : searchResults) {
            if (sr.score >= options.minScore) filtered.add(sr);
        }

        // 入口点数量上限：最终保留的入口点不超过 searchLimit。
        // 这是最终输出维度的控制，确保下游图扩展的复杂度和返回结果大小可控。
        if (filtered.size() > options.searchLimit) {
            filtered = new ArrayList<>(filtered.subList(0, options.searchLimit));
        }

        // 将过滤后的候选节点添加为子图入口点（roots）。
        // 入口点是图扩展的起点——BFS 将从这些节点出发向外辐射。
        for (SearchResult sr : filtered) {
            result.addNode(sr.node);
            result.addRoot(sr.node.getId());
        }

        // ==================== Step 6: BFS 图扩展 ====================
        // 从入口点出发进行广度优先遍历，补全相邻节点和边信息。
        //
        // 扩展策略：
        //   - 方向：双向（both），即同时追踪调用者和被调用者
        //   - 深度：由 traversalDepth 控制（默认值通常为 1-2 层）
        //   - 每个入口点的配额：maxNodes / 入口点数量（均分）
        //   - 边类型：仅包含语义关系边（CALLS/REFERENCES/EXTENDS 等），
        //     排除 CONTAINS（AST 父子关系），避免返回整个文件的节点树
        //
        // 图扩展是整个管道的最后一步，确保返回的子图包含：
        //   1. 直接相关的节点（入口点）
        //   2. 它们的直接调用者/被调用者
        //   3. 它们引用的类型、继承的父类、实现的接口
        if (result.nodeCount() > 0) {
            Set<String> allRootIds = new LinkedHashSet<>(result.roots);
            for (String rootId : new ArrayList<>(allRootIds)) {
                TraversalOptions tOpts = new TraversalOptions();
                tOpts.maxDepth = options.traversalDepth;
                tOpts.limit = options.maxNodes / Math.max(1, allRootIds.size());
                tOpts.direction = "both";           // 同时向上和向下遍历
                tOpts.includeStart = true;          // 包含起始节点本身
                tOpts.edgeKinds = RELEVANT_EDGE_KINDS; // 仅遍历语义关系边

                com.codegraph.graph.GraphTraverser.Subgraph sg = traverser.traverseBFS(rootId, tOpts);
                // 合并遍历结果到最终子图
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
