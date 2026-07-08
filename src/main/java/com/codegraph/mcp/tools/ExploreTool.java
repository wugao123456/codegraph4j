package com.codegraph.mcp.tools;

import com.codegraph.config.CodeGraphConfig;
import com.codegraph.context.*;
import com.codegraph.core.Edge;
import com.codegraph.core.Node;
import com.codegraph.core.types.EdgeKind;
import com.codegraph.core.types.NodeKind;
import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;
import com.codegraph.graph.GraphQueryManager;
import com.codegraph.graph.GraphTraverser;
import com.codegraph.graph.GraphTraverser.CalleeInfo;
import com.codegraph.graph.GraphTraverser.CallerInfo;
import com.codegraph.mcp.MCPTransport.ToolCallResult;
import com.codegraph.mcp.MCPTransport.ToolDefinition;
import com.codegraph.utils.MarkdownUtils;
import com.codegraph.utils.StringUtils;
import com.codegraph.utils.FileFilterUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * codegraph_explore — 主要探索工具。
 * <p>
 * 接收自然语言查询，通过 8 步流水线返回最相关的代码段、关系图和爆炸半径分析。
 * <p>
 * <b>流水线概览：</b>
 * <ol>
 *   <li>参数校验 + 自适应输出预算</li>
 *   <li>混合搜索获取子图（ContextBuilder.findRelevantContext）</li>
 *   <li>图感知粘合（补全同文件内的调用者/被调用者）</li>
 *   <li>命名符号播种（按符号名称精确匹配补充节点）</li>
 *   <li>文件分组评分（按文件聚合并打分）</li>
 *   <li>RWR 图相关性计算（随机游走排序 + 门控过滤）</li>
 *   <li>多准则文件排序</li>
 *   <li>构建输出段落（爆炸半径 + 关系图 + 源代码渲染）</li>
 * </ol>
 *
 * <p><b>使用示例：</b>
 * <pre>
 * // 查询："ContextBuilder findRelevantContext 如何工作"
 * // → 返回 ContextBuilder.java 中 findRelevantContext 方法
 * //   及其调用的 SearchUtils、queries 等相关符号的源代码
 * //   以及它们之间的 CALLS/REFERENCES 关系图
 * </pre>
 */
public class ExploreTool extends BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(ExploreTool.class);

    private static final int EXPLORE_LOG_MAX_FILES;
    private static final DateTimeFormatter EXPLORE_LOG_DATE_FORMATTER =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    static {
        int maxFiles = 10;
        String env = System.getenv("CODEGRAPH_EXPLORE_LOG_MAX_FILES");
        if (env != null && !env.isEmpty()) {
            try {
                maxFiles = Integer.parseInt(env.trim());
                if (maxFiles < 1) maxFiles = 1;
            } catch (NumberFormatException ignored) {}
        }
        EXPLORE_LOG_MAX_FILES = maxFiles;
    }

    public ExploreTool(DatabaseConnection db, QueryBuilder queries,
                       GraphTraverser traverser, GraphQueryManager graphQueryMgr,
                       CodeGraphConfig config) {
        super(db, queries, traverser, graphQueryMgr, config);
    }

    @Override
    public String getName() {
        return "codegraph_explore";
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> queryProp = new LinkedHashMap<>();
        queryProp.put("type", "string");
        queryProp.put("description", "Symbol names, file names, or short code terms to explore (e.g., \"AuthService" 
       +" loginUser session-manager\", \"GraphTraverser BFS impact traversal.java\"). "
       +"For a flow question, name the symbols spanning the flow (e.g. \"mutateElement renderScene\"). "+
       "A natural-language question works too — no prior codegraph_search needed.");
        props.put("query", queryProp);
        
        Map<String, Object> maxFilesProp = new LinkedHashMap<>();
        maxFilesProp.put("type", "number");
        maxFilesProp.put("description", "Maximum number of files to include source code from (default: 12)");
        maxFilesProp.put("default", 12);
        props.put("maxFiles", maxFilesProp);
        
        Map<String, Object> projectPathProp = new LinkedHashMap<>();
        projectPathProp.put("type", "string");
        projectPathProp.put("description", "Absolute path to the project to query"
        +" (or any directory inside it) — codegraph4j uses the nearest .codegraph/ "
        +"index at or above that path. Omit to use this session's default project. "
        +"Pass it to query a second codebase, or when the server root has no index of its "
        +"own (e.g. a monorepo where only sub-projects are indexed, so there is no default project).");
        props.put("projectPath", projectPathProp);
        
        schema.put("properties", props);
        schema.put("required", Arrays.asList("query"));
        return new ToolDefinition("codegraph_explore",
            "PRIMARY TOOL — call FIRST for almost any question OR before an edit:"+
            "how does X work, architecture, a bug, where/what is X, surveying an area,"+
            " or the symbols you are about to change. Returns the verbatim source of the "+
            "relevant symbols grouped by file in ONE capped call (Read-equivalent — treat the "+
            "shown source as already Read; do NOT re-open those files), plus the call path among them."+
            "Query can be a natural-language question OR a bag of symbol/file names. Usually the ONLY call "+
            "you need — more accurate context, in far fewer tokens and round-trips than a search/Read/Grep loop.",
            schema);
    }

    @Override
    public ToolCallResult execute(Map<String, Object> args) {
        try {
            return handleExplore(args);
        } catch (Exception e) {
            logger.error("Explore failed", e);
            return error("Explore failed: " + e.getMessage());
        }
    }

    // ================================================================================================
    // 探索流水线主方法
    // ================================================================================================

    /**
     * codegraph_explore 的 8 步探索流水线。
     *
     * <p><b>流水线步骤：</b>
     * <ol>
     *   <li><b>参数校验与预算计算</b>：验证输入参数，根据项目文件数自动调整输出预算。
     *       例如小项目（&lt;100文件）允许更详细的输出，大项目（&gt;10000文件）则需要更严格的截断。</li>
     *   <li><b>混合搜索</b>：调用 ContextBuilder 执行精确匹配 → 前缀匹配 → FTS → CamelCase 等多通道搜索。</li>
     *   <li><b>图感知粘合</b>：补全搜索结果的调用者/被调用者，使返回的子图更完整。</li>
     *   <li><b>命名符号播种</b>：从查询中提取的符号名精确查找节点，补充子图。</li>
     *   <li><b>文件分组评分</b>：按文件将节点分组，根据与入口点的距离打分。</li>
     *   <li><b>RWR 图相关性</b>：用随机游走算法计算每个文件的相关性，门控过滤低相关文件。</li>
     *   <li><b>多准则排序</b>：综合图相关性、术语命中、文件类型等维度排序。</li>
     *   <li><b>输出渲染</b>：生成包含爆炸半径、关系图、源代码的 Markdown 输出。</li>
     * </ol>
     */
    private ToolCallResult handleExplore(Map<String, Object> args) throws SQLException {
        long startTime = System.currentTimeMillis();

        // Step 1: 参数校验与预算计算
        ExploreInput input;
        try {
            input = parseExploreInput(args);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
        logger.info("[codegraph_explore] 开始处理查询: query=\"{}\"", input.query);

        // Step 2: 混合搜索获取子图
        Subgraph subgraph = input.ctxBuilder.findRelevantContext(input.query, input.opts);
        if (subgraph.nodes.isEmpty()) {
            logger.info("[codegraph_explore] 未找到相关代码: query=\"{}\"", input.query);
            return text("No relevant code found for \"" + input.query + "\"");
        }
        logger.info("[codegraph_explore] Step 2 - 混合搜索完成: nodes={}, edges={}, roots={}",
            subgraph.nodes.size(), subgraph.edges.size(), subgraph.roots.size());

        // Step 3: 图感知粘合——补全同文件内的调用关系
        Set<String> glueNodeIds = applyGraphAwareGlue(subgraph);

        // Step 4: 命名符号播种——按符号名称精确匹配补充节点
        Set<String> namedSeedIds = seedNamedSymbols(input, subgraph);

        // Steps 5-7: 文件分组评分 + RWR 相关性 + 排序
        FileRanking ranking = scoreGroupAndRankFiles(subgraph, namedSeedIds, input);

        // Step 8: 构建 Markdown 输出
        ToolCallResult result = buildExplorationOutput(input, subgraph, glueNodeIds, namedSeedIds, ranking);
        long elapsed = System.currentTimeMillis() - startTime;
        logger.info("[codegraph_explore] 处理完成: query=\"{}\", 耗时={}ms",
            input.query, elapsed);
        return result;
    }

    // ================================================================================================
    // Step 1: 参数校验与预算计算
    // ================================================================================================

    /**
     * 校验输入参数并计算自适应输出预算。
     *
     * <p><b>自适应预算算法：</b>
     * 根据索引的文件总数决定输出上限。原理：项目越大，候选符号越多，
     * 需要更严格的截断来保证结果质量和响应时间。
     *
     * <p><b>示例：</b>
     * <pre>
     * 小项目（100 文件）→ maxOutputChars=40000, maxFiles=12  → 可返回完整文件内容
     * 大项目（10000 文件）→ maxOutputChars=20000, maxFiles=8  → 优先返回骨架/聚类视图
     * </pre>
     *
     * @param args MCP 工具参数（query, maxFiles, projectPath）
     * @return 封装了校验后参数的 ExploreInput
     */
    private ExploreInput parseExploreInput(Map<String, Object> args) throws SQLException {
        // 提取并校验 query 参数
        String query = requireArg(args, "query");
        ToolCallResult validationError = validateInputArg(args, "query");
        if (validationError != null) throw new IllegalArgumentException(validationError.content.get(0).text);
        validationError = validatePathArg(args, "projectPath");
        if (validationError != null) throw new IllegalArgumentException(validationError.content.get(0).text);

        // 根据索引文件数计算输出预算
        int fileCount = countIndexedFiles();
        ExploreOutputBudget budget = ExploreOutputBudget.getForFileCount(fileCount);
        int maxFiles = clamp(intArg(args, "maxFiles", budget.defaultMaxFiles), 1, 20);

        // 创建 ContextBuilder 并配置搜索选项
        ContextBuilder ctxBuilder = new ContextBuilder(queries);
        ContextBuilder.FindOptions opts = config.buildExploreFindOptions();

        logger.info("[codegraph_explore] Step 1 - 自适应预算: fileCount={}, maxFiles={}, maxOutputChars={}",
            fileCount, maxFiles, budget.maxOutputChars);

        return new ExploreInput(query, budget, maxFiles, ctxBuilder, opts);
    }

    // ================================================================================================
    // Step 3: 图感知粘合
    // ================================================================================================

    /**
     * 图感知粘合（Graph-Aware Gluing）：为搜索结果补全同文件内的调用者和被调用者。
     *
     * <p><b>动机：</b>
     * 搜索结果中 root 节点通常是查询直接命中的符号，但与它们同文件的其他函数/方法
     * 也是理解上下文所需的关键信息。此步骤从每个 root 出发，查找其上调用者和下被调用者，
     * 若被调用者也位于同一文件中，则加入子图。
     *
     * <p><b>示例：</b>
     * <pre>
     * 查询命中 "handleExplore" 方法（root 节点），它的调用者 "execute" 也在同一个文件
     * ExploreTool.java 中 → 粘合步骤将 "execute" 也加入子图
     * </pre>
     *
     * <p><b>限制：</b>最多粘合 60 个节点，防止在大项目中无限扩展。
     *
     * @param subgraph 搜索结果子图（会被原地修改，添加粘合节点）
     * @return 被粘合加入的节点 ID 集合
     */
    private Set<String> applyGraphAwareGlue(Subgraph subgraph) {
        // 收集子图中已有的所有文件路径
        Set<String> subgraphFiles = new LinkedHashSet<>();
        for (Node n : subgraph.nodes.values()) {
            if (n.getFilePath() != null) subgraphFiles.add(n.getFilePath());
        }

        Set<String> glueNodeIds = new LinkedHashSet<>();
        final int GLUE_NODE_CAP = 60;  // 粘合节点数量上限

        // 对每个 root 节点，获取其直接调用者和被调用者
        for (String rootId : subgraph.roots) {
            if (glueNodeIds.size() >= GLUE_NODE_CAP) break;

            List<CallerInfo> callers = traverser.getCallers(rootId, 1);
            List<CalleeInfo> callees = traverser.getCallees(rootId, 1);

            // 加入同文件的调用者
            for (CallerInfo ci : callers) {
                if (ci.node == null) continue;
                if (glueNodeIds.size() >= GLUE_NODE_CAP) break;
                if (subgraph.nodes.containsKey(ci.node.getId())) continue;
                // 只粘合同文件内的节点（跨文件的调用关系由 BFS 图扩展覆盖）
                if (!subgraphFiles.contains(ci.node.getFilePath())) continue;
                subgraph.addNode(ci.node);
                glueNodeIds.add(ci.node.getId());
            }

            // 加入同文件的被调用者
            for (CalleeInfo ci : callees) {
                if (ci.node == null) continue;
                if (glueNodeIds.size() >= GLUE_NODE_CAP) break;
                if (subgraph.nodes.containsKey(ci.node.getId())) continue;
                if (!subgraphFiles.contains(ci.node.getFilePath())) continue;
                subgraph.addNode(ci.node);
                glueNodeIds.add(ci.node.getId());
            }
        }

        logger.info("[codegraph_explore] Step 3 - 图感知粘合完成: glueNodes={}, subgraphFiles={}",
            glueNodeIds.size(), subgraphFiles.size());
        return glueNodeIds;
    }

    // ================================================================================================
    // Step 4: 命名符号播种
    // ================================================================================================

    /**
     * 命名符号播种（Named Symbol Seeding）：从查询文本中提取符号名，通过精确匹配查找节点。
     *
     * <p><b>动机：</b>
     * 混合搜索（Step 2）使用基于分数的模糊搜索，可能漏掉一些与查询词精确匹配但
     * 分数不高的节点。此步骤用三级精确匹配（完全限定名 → 简单名 → FTS）兜底，
     * 确保命名符号不被遗漏。
     *
     * <p><b>匹配级联（fallback chain）：</b>
     * <ol>
     *   <li>{@code getNodesByQualifiedName("com.example.MyClass")} — 完全限定名匹配</li>
     *   <li>{@code getNodesByName("MyClass")} — 简单名匹配</li>
     *   <li>{@code searchNodes("MyClass")} — 全文搜索兜底</li>
     * </ol>
     * 只要任一级别找到了结果，就不再尝试后续级别。
     *
     * <p><b>示例：</b>
     * <pre>
     * 查询："ExploreTool handleExplore"
     * → extractSymbols 提取出 ["ExploreTool", "handleExplore"]
     * → 对 "ExploreTool" 执行完全限定名查找 → 找到 ExploreTool.java 中的类定义
     * → 对 "handleExplore" 执行简单名查找 → 找到 handleExplore 方法定义
     * → 两者都被标记为 namedSeedId，在后续评分中获得最高权重（+50 分）
     * </pre>
     *
     * @param input 解析后的输入参数（含 ctxBuilder 和 query）
     * @param subgraph 搜索结果子图（会被原地修改，添加匹配到的节点）
     * @return 被播种加入的节点 ID 集合（最多 40 个）
     */
    private Set<String> seedNamedSymbols(ExploreInput input, Subgraph subgraph) {
        Set<String> namedSeedIds = new LinkedHashSet<>();
        List<String> tokens = input.ctxBuilder.extractSymbols(input.query);

        for (String token : tokens) {
            if (namedSeedIds.size() >= 40) break;  // 防止播种过多节点

            try {
                // 三级匹配级联：完全限定名 → 简单名 → FTS
                List<Node> results = queries.getNodesByQualifiedName(token);
                if (results.isEmpty()) results = queries.getNodesByName(token);
                if (results.isEmpty()) results = queries.searchNodes(token);

                for (Node n : results) {
                    if (namedSeedIds.size() >= 40) break;
                    if (!subgraph.nodes.containsKey(n.getId())) {
                        subgraph.addNode(n);
                    }
                    namedSeedIds.add(n.getId());
                }
            } catch (SQLException ignored) {}
        }

        logger.info("[codegraph_explore] Step 4 - 命名符号播种完成: tokens={}, namedSeeds={}",
            tokens.size(), namedSeedIds.size());
        return namedSeedIds;
    }

    // ================================================================================================
    // Steps 5-7: 文件分组评分 + RWR 相关性 + 排序
    // ================================================================================================

    /**
     * 文件分组、评分与排序（Steps 5-7 合并）。
     *
     * <p><b>Step 5 — 文件分组评分：</b>
     * 将子图中所有节点按所在文件分组，根据节点类型打分：
     * <pre>
     * namedSeedId（精确播种命中的节点）                → +50
     * entryNodeId（搜索入口点 + namedSeedId）           → +10
     * connectedToEntry（与入口点直接相连的节点）         → +3
     * 其他节点                                           → +1
     * </pre>
     * 过滤掉分数 &lt; 3 的文件和低价值文件（测试/I18N/图标等）。
     *
     * <p><b>Step 6 — RWR 图相关性：</b>
     * 用随机游走（Random Walk with Restart）算法计算每个节点相对于入口点的相关性，
     * 聚合为文件级分数。同时计算文件路径/名称中的查询词命中数。
     * 然后通过门控（gate）过滤：只有图相关性 ≥ max * 6% 或中央文件或入口文件
     * 或多词命中的文件才保留。这确保最终结果中每个文件都与查询有图结构上的关联。
     *
     * <p><b>Step 7 — 多准则排序：</b>
     * 排序优先级（从高到低）：
     * <ol>
     *   <li>是否包含 namedSeed 节点</li>
     *   <li>是否中央/入口文件 且 ≥2 词命中</li>
     *   <li>图相关性分数（差距 &gt; max*1% 时生效）</li>
     *   <li>术语命中数</li>
     *   <li>是否低价值文件（测试等排在最后）</li>
     *   <li>是否生成文件（自动生成的代码排后）</li>
     *   <li>文件分组分数</li>
     *   <li>节点数量（节点多的文件排前）</li>
     * </ol>
     *
     * @param subgraph 搜索结果子图
     * @param namedSeedIds 命名播种的节点 ID
     * @param input 输入参数（含 query、budget、ctxBuilder）
     * @return FileRanking 包含排序后的文件列表及所有中间计算结果
     */
    private FileRanking scoreGroupAndRankFiles(Subgraph subgraph, Set<String> namedSeedIds, ExploreInput input) {
        String query = input.query;
        ExploreOutputBudget budget = input.budget;

        // Step 5: 节点按文件分组 + 评分
        FileGrouping grouping = groupAndScoreFiles(subgraph, namedSeedIds, query, budget);

        // Step 6: RWR 图相关性 + 门控过滤
        GraphRelevance relevance = computeGraphRelevance(subgraph, grouping.entryNodeIds,
            grouping.relevantFiles, query);

        // Step 7: 多准则文件排序
        sortFilesByMultipleCriteria(grouping.relevantFiles, namedSeedIds, subgraph, relevance);

        return new FileRanking(grouping.fileGroups, grouping.entryNodeIds,
            relevance.entryFiles, relevance.centralFiles,
            relevance.fileGraphScore, relevance.fileTermHits,
            relevance.maxGraph, grouping.relevantFiles);
    }

    /**
     * Step 5 — 节点按文件分组并计算初始分数。
     *
     * <p><b>算法逻辑：</b>
     * <ol>
     *   <li>构建入口节点集合：子图 roots + namedSeedIds</li>
     *   <li>找出与入口点直接相连的节点（connectedToEntry）</li>
     *   <li>遍历所有节点，按文件路径分组，根据节点类型打分：
     *       <ul>
     *         <li>namedSeedId（精确播种命中）→ +50</li>
     *         <li>entryNodeId（搜索入口）→ +10</li>
     *         <li>connectedToEntry（相邻节点）→ +3</li>
     *         <li>其他节点 → +1</li>
     *       </ul>
     *   </li>
     *   <li>过滤掉分数 &lt; 3 的文件（相关性太低）</li>
     *   <li>过滤低价值文件（测试/I18N/图标），但测试相关查询时不过滤</li>
     * </ol>
     *
     * <p><b>示例：</b>
     * 查询 "user service"，假设找到 5 个节点：
     * <ul>
     *   <li>UserService 类定义（namedSeed）→ UserService.java +50</li>
     *   <li>getUser() 方法（root）→ UserService.java +10</li>
     *   <li>User 类（与 getUser 相连）→ User.java +3</li>
     *   <li>UserDao 接口（与 UserService 相连）→ UserDao.java +3</li>
     *   <li>toString() 方法（其他节点）→ User.java +1</li>
     * </ul>
     * 结果：UserService.java=60, User.java=4, UserDao.java=3（都保留）
     *
     * @param subgraph 搜索结果子图
     * @param namedSeedIds 命名播种的节点 ID
     * @param query 用户查询字符串
     * @param budget 输出预算配置
     * @return FileGrouping 包含文件分组、入口节点集合和相关文件列表
     */
    private FileGrouping groupAndScoreFiles(Subgraph subgraph, Set<String> namedSeedIds,
            String query, ExploreOutputBudget budget) {
        Map<String, FileGroup> fileGroups = new LinkedHashMap<>();

        Set<String> entryNodeIds = new LinkedHashSet<>();
        for (String id : subgraph.roots) entryNodeIds.add(id);
        entryNodeIds.addAll(namedSeedIds);

        Set<String> connectedToEntry = connectedToEntry(subgraph, entryNodeIds);

        for (Node n : subgraph.nodes.values()) {
            if (n.getKind() == NodeKind.IMPORT || n.getKind() == NodeKind.EXPORT) continue;
            if (isConfigLeafNode(n)) continue;

            String filePath = n.getFilePath() != null ? n.getFilePath() : "";
            FileGroup group = fileGroups.computeIfAbsent(filePath, k -> new FileGroup());
            group.nodes.add(n);

            if (namedSeedIds.contains(n.getId())) {
                group.score += 50;
            } else if (entryNodeIds.contains(n.getId())) {
                group.score += 10;
            } else if (connectedToEntry.contains(n.getId())) {
                group.score += 3;
            } else {
                group.score += 1;
            }
        }

        List<Map.Entry<String, FileGroup>> relevantFiles = new ArrayList<>();
        for (Map.Entry<String, FileGroup> e : fileGroups.entrySet()) {
            if (e.getValue().score >= 3) relevantFiles.add(e);
        }

        int beforeFilterSize = relevantFiles.size();
        if (budget.excludeLowValueFiles && !mentionsTests(query)) {
            List<Map.Entry<String, FileGroup>> nonLow = new ArrayList<>();
            for (Map.Entry<String, FileGroup> e : relevantFiles) {
                if (!isLowValue(e.getKey())) nonLow.add(e);
            }
            if (nonLow.size() >= 2) relevantFiles = nonLow;
        }
        logger.info("[codegraph_explore] Step 5 - 文件分组评分完成: fileGroups={}, relevantFiles={}, 过滤掉={}",
            fileGroups.size(), relevantFiles.size(), beforeFilterSize - relevantFiles.size());

        return new FileGrouping(fileGroups, entryNodeIds, relevantFiles);
    }

    /**
     * Step 6 — 计算 RWR（随机游走重启）图相关性并进行门控过滤。
     *
     * <p><b>RWR 算法原理：</b>
     * 从入口节点出发进行随机游走，每一步有概率跳到相邻节点，有概率回到起点（重启）。
     * 节点被访问的频率即为相关性分数。距离入口点越近、连接越紧密的节点分数越高。
     *
     * <p><b>处理流程：</b>
     * <ol>
     *   <li>调用 GraphRelevanceComputer 计算节点级 RWR 分数</li>
     *   <li>聚合为文件级图相关性分数（fileGraphScore）</li>
     *   <li>提取查询中的唯一术语，计算文件路径/名称中的命中数（fileTermHits）</li>
     *   <li>选出中央文件（图相关性前 2 名 且 ≥1 术语命中）</li>
     *   <li>收集入口文件（包含入口节点的文件）</li>
     *   <li>门控过滤：保留 gs ≥ max*6% 或中央文件或入口文件或术语命中 ≥2 的文件</li>
     * </ol>
     *
     * <p><b>门控的作用：</b>
     * 确保最终结果中的每个文件都与查询有图结构上的关联，避免返回完全无关的文件。
     * 例如查询 "cache manager"，一个只在文件名包含 "manager" 但与缓存系统毫无关联的文件
     * 会被过滤掉，除非它是中央文件或入口文件。
     *
     * @param subgraph 搜索结果子图
     * @param entryNodeIds 入口节点集合
     * @param relevantFiles 待评分的文件列表
     * @param query 用户查询字符串
     * @return GraphRelevance 包含图相关性分数、术语命中数、中央文件、入口文件等
     */
    private GraphRelevance computeGraphRelevance(Subgraph subgraph, Set<String> entryNodeIds,
            List<Map.Entry<String, FileGroup>> relevantFiles, String query) {
        Map<String, Double> nodeRwr = new GraphRelevanceComputer().compute(
            subgraph.nodes.keySet(), subgraph.edges, entryNodeIds);

        Map<String, Double> fileGraphScore = new HashMap<>();
        double maxGraph = 0;
        for (Node n : subgraph.nodes.values()) {
            double val = fileGraphScore.getOrDefault(n.getFilePath(), 0.0)
                + nodeRwr.getOrDefault(n.getId(), 0.0);
            fileGraphScore.put(n.getFilePath(), val);
            if (val > maxGraph) maxGraph = val;
        }

        Set<String> uniqueTerms = new HashSet<>();
        for (String t : query.toLowerCase().split("\\s+")) {
            if (t.length() >= 3) uniqueTerms.add(t);
        }

        Map<String, Integer> fileTermHits = new HashMap<>();
        for (Map.Entry<String, FileGroup> e : relevantFiles) {
            String fp = e.getKey();
            String hay = fp.toLowerCase() + " " + joinNodeNames(e.getValue().nodes);
            int hits = 0;
            for (String t : uniqueTerms) if (hay.contains(t)) hits++;
            fileTermHits.put(fp, hits);
        }

        Set<String> centralFiles = new LinkedHashSet<>();
        List<Map.Entry<String, Double>> scored = new ArrayList<>(fileGraphScore.entrySet());
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        int centralCount = 0;
        for (Map.Entry<String, Double> e : scored) {
            if (centralCount >= 2) break;
            if (e.getValue() > 0 && fileTermHits.getOrDefault(e.getKey(), 0) >= 1) {
                centralFiles.add(e.getKey());
                centralCount++;
            }
        }

        Set<String> entryFiles = new LinkedHashSet<>();
        for (String id : entryNodeIds) {
            Node n = subgraph.nodes.get(id);
            if (n != null && n.getFilePath() != null) entryFiles.add(n.getFilePath());
        }

        if (maxGraph > 0) {
            List<Map.Entry<String, FileGroup>> gated = new ArrayList<>();
            for (Map.Entry<String, FileGroup> e : relevantFiles) {
                String fp = e.getKey();
                double gs = fileGraphScore.getOrDefault(fp, 0.0);
                if (gs >= maxGraph * 0.06 || centralFiles.contains(fp)
                    || entryFiles.contains(fp) || fileTermHits.getOrDefault(fp, 0) >= 2) {
                    gated.add(e);
                }
            }
            if (gated.size() >= 2) relevantFiles.clear();
            relevantFiles.addAll(gated);
        }
        logger.info("[codegraph_explore] Step 6 - RWR图相关性完成: centralFiles={}, entryFiles={}, 门控后文件数={}, maxGraph={}",
            centralFiles.size(), entryFiles.size(), relevantFiles.size(), String.format("%.4f", maxGraph));

        return new GraphRelevance(fileGraphScore, fileTermHits, maxGraph, centralFiles, entryFiles);
    }

    /**
     * Step 7 — 多准则文件排序。
     *
     * <p><b>排序优先级（从高到低）：</b>
     * <ol>
     *   <li><b>namedSeed 节点</b>：包含精确播种命中节点的文件优先</li>
     *   <li><b>中央/入口 + 术语命中</b>：是中央/入口文件且术语命中 ≥2 的优先</li>
     *   <li><b>图相关性</b>：差距 &gt; max*1% 时生效，分数高的优先</li>
     *   <li><b>术语命中数</b>：命中查询词多的优先</li>
     *   <li><b>低价值文件</b>：测试/I18N/图标文件排后</li>
     *   <li><b>生成文件</b>：自动生成的代码排后</li>
     *   <li><b>文件分组分数</b>：Step 5 计算的分数，高的优先</li>
     *   <li><b>节点数量</b>：包含节点多的文件优先</li>
     * </ol>
     *
     * <p><b>设计意图：</b>
     * 排序算法综合考虑了图结构相关性（RWR）、文本匹配（术语命中）和文件类型（低价值过滤），
     * 确保最相关的文件排在最前面。例如查询 "user service"：
     * <ul>
     *   <li>UserService.java（含 namedSeed + 高图相关性）→ 第 1</li>
     *   <li>UserServiceImpl.java（中央文件 + 2 词命中）→ 第 2</li>
     *   <li>User.java（高图相关性）→ 第 3</li>
     *   <li>UserServiceTest.java（低价值文件）→ 最后</li>
     * </ul>
     *
     * @param relevantFiles 待排序的文件列表（会被原地修改）
     * @param namedSeedIds 命名播种的节点 ID
     * @param subgraph 搜索结果子图
     * @param relevance RWR 相关性计算结果
     */
    private void sortFilesByMultipleCriteria(List<Map.Entry<String, FileGroup>> relevantFiles,
            Set<String> namedSeedIds, Subgraph subgraph, GraphRelevance relevance) {
        final double maxGraph = relevance.maxGraph;
        Map<String, Double> fileGraphScore = relevance.fileGraphScore;
        Map<String, Integer> fileTermHits = relevance.fileTermHits;
        Set<String> entryFiles = relevance.entryFiles;
        Set<String> centralFiles = relevance.centralFiles;

        relevantFiles.sort((a, b) -> {
            String ap = a.getKey(), bp = b.getKey();
            double ags = fileGraphScore.getOrDefault(ap, 0.0);
            double bgs = fileGraphScore.getOrDefault(bp, 0.0);
            int ah = fileTermHits.getOrDefault(ap, 0);
            int bh = fileTermHits.getOrDefault(bp, 0);

            boolean aa = namedSeedIds.stream().anyMatch(id -> {
                Node n = subgraph.nodes.get(id);
                return n != null && ap.equals(n.getFilePath());
            });
            boolean ba = namedSeedIds.stream().anyMatch(id -> {
                Node n = subgraph.nodes.get(id);
                return n != null && bp.equals(n.getFilePath());
            });
            if (aa != ba) return ba ? 1 : -1;

            boolean ac = (entryFiles.contains(ap) || centralFiles.contains(ap)) && ah >= 2;
            boolean bc = (entryFiles.contains(bp) || centralFiles.contains(bp)) && bh >= 2;
            if (ac != bc) return bc ? 1 : -1;

            if (Math.abs(ags - bgs) > maxGraph * 0.01) return Double.compare(bgs, ags);

            if (ah != bh) return bh - ah;

            boolean al = isLowValue(ap), bl = isLowValue(bp);
            if (al != bl) return al ? 1 : -1;

            boolean ag = FileFilterUtils.isGeneratedFile(ap);
            boolean bg = FileFilterUtils.isGeneratedFile(bp);
            if (ag != bg) return ag ? 1 : -1;

            if (a.getValue().score != b.getValue().score) return b.getValue().score - a.getValue().score;

            return b.getValue().nodes.size() - a.getValue().nodes.size();
        });
        logger.info("[codegraph_explore] Step 7 - 文件排序完成: 待输出文件数={}", relevantFiles.size());
    }



    // ==================== 内部数据类 ====================

    /**
     * 文件分组结果（Step 5 输出）。
     * 包含文件分组映射、入口节点集合和过滤后的相关文件列表。
     */
    private static class FileGrouping {
        final Map<String, FileGroup> fileGroups;
        final Set<String> entryNodeIds;
        final List<Map.Entry<String, FileGroup>> relevantFiles;

        FileGrouping(Map<String, FileGroup> fileGroups, Set<String> entryNodeIds,
                List<Map.Entry<String, FileGroup>> relevantFiles) {
            this.fileGroups = fileGroups;
            this.entryNodeIds = entryNodeIds;
            this.relevantFiles = relevantFiles;
        }
    }

    /**
     * RWR 图相关性计算结果（Step 6 输出）。
     * 包含文件级图相关性分数、术语命中数、最大相关性值、中央文件和入口文件集合。
     */
    private static class GraphRelevance {
        final Map<String, Double> fileGraphScore;
        final Map<String, Integer> fileTermHits;
        final double maxGraph;
        final Set<String> centralFiles;
        final Set<String> entryFiles;

        GraphRelevance(Map<String, Double> fileGraphScore, Map<String, Integer> fileTermHits,
                double maxGraph, Set<String> centralFiles, Set<String> entryFiles) {
            this.fileGraphScore = fileGraphScore;
            this.fileTermHits = fileTermHits;
            this.maxGraph = maxGraph;
            this.centralFiles = centralFiles;
            this.entryFiles = entryFiles;
        }
    }

    // ================================================================================================
    // Step 8: 构建输出
    // ================================================================================================

    /**
     * 构建最终的 Markdown 输出（Step 8）。
     *
     * <p><b>输出结构：</b>
     * <pre>
     * **Exploration: {query}**
     *
     * Found {N} symbols across {M} files.
     *
     * [爆炸半径分析 — Blast Radius]
     *
     * **Relationships**
     * **CALLS:**
     * - foo → bar
     *
     * **Source Code**
     * **FileA.java** — meth1, meth2 — `path/to/FileA.java`
     *
     * ```java
     * 1`\tpublic void meth1() { ... }
     * ```
     * </pre>
     *
     * <p><b>源代码渲染策略（3 种模式）：</b>
     * <ol>
     *   <li><b>骨架（Skeleton）</b>：大文件且多态兄弟时，只渲染命名方法的完整体和其余方法的签名行。
     *       例如一个有 50 个方法的 interface 实现类，只展开用户查询命中的 2 个方法。</li>
     *   <li><b>完整文件（Whole File）</b>：小文件（≤220 行）直接返回完整源代码。</li>
     *   <li><b>聚类（Clustered）</b>：大文件但非多态时，返回与入口点最相关的代码块段落。</li>
     * </ol>
     */
    private ToolCallResult buildExplorationOutput(ExploreInput input, Subgraph subgraph,
            Set<String> glueNodeIds, Set<String> namedSeedIds, FileRanking ranking) {
        List<String> lines = new ArrayList<>();

        renderPreamble(lines, input, subgraph, ranking);

        boolean anyTrimmed = renderSourceCodeSection(lines, input, subgraph, glueNodeIds, namedSeedIds, ranking);

        renderCompletenessAndBudgetNotes(lines, input.budget, input.maxFiles, ranking, anyTrimmed);

        String resultText = joinStrings(lines);
        return text(resultText);
    }

    /**
     * 渲染源代码部分（包含所有文件的代码片段）。
     *
     * <p><b>处理流程：</b>
     * <ol>
     *   <li>获取流程分析信息（pathNodeIds, namedNodeIds）</li>
     *   <li>遍历排序后的文件列表</li>
     *   <li>对每个文件判断是否必要（包含入口点/流程节点/命名节点）</li>
     *   <li>根据文件大小和类型选择渲染模式（骨架/完整/聚类）</li>
     *   <li>输出渲染结果</li>
     * </ol>
     *
     * @param lines 输出行列表（会被追加）
     * @param input 输入参数
     * @param subgraph 搜索结果子图
     * @param glueNodeIds 图感知粘合的节点 ID
     * @param namedSeedIds 命名播种的节点 ID
     * @param ranking 文件排序结果
     * @return 是否有文件因预算限制被裁剪
     */
    private boolean renderSourceCodeSection(List<String> lines, ExploreInput input, Subgraph subgraph,
            Set<String> glueNodeIds, Set<String> namedSeedIds, FileRanking ranking) {
        String query = input.query;
        ExploreOutputBudget budget = input.budget;
        int maxFiles = input.maxFiles;

        lines.add("**Source Code**");
        lines.add("");
        lines.add("> The code below is the **verbatim, current on-disk source** of these files \u2014");
        lines.add("> re-read from disk on this call and line-numbered,");
        lines.add("> byte-for-byte identical to what the Read tool returns.");
        lines.add("");

        ContextBuilder.FlowInfo flow = input.ctxBuilder.buildFlowFromNamedSymbols(query);
        Set<String> pathNodeIds = flow.pathNodeIds;
        Set<String> namedNodeIds = flow.namedNodeIds;
        Set<String> uniqueNamedIds = flow.uniqueNamedNodeIds;

        int totalChars = joinStrings(lines).length();
        int filesIncluded = 0;
        boolean anyTrimmed = false;

        Map<String, Boolean> siblingSuperCache = new HashMap<>();
        Map<String, Boolean> superManyCache = new HashMap<>();
        final int MIN_SIBLINGS = 3;

        for (Map.Entry<String, FileGroup> entry : ranking.relevantFiles) {
            if (filesIncluded >= maxFiles) break;

            String filePath = entry.getKey();
            FileGroup group = entry.getValue();

            boolean fileNecessary = isFileNecessary(group.nodes, ranking.entryNodeIds, pathNodeIds, namedNodeIds);
            if (!fileNecessary && totalChars > budget.maxOutputChars * 0.9) continue;

            List<String> fileLines = readFileLines(filePath);
            if (fileLines == null) continue;

            String lang = detectLanguage(filePath);

            RenderMode mode = determineRenderMode(group.nodes, fileLines, pathNodeIds,
                namedNodeIds, uniqueNamedIds, ranking.centralFiles.contains(filePath),
                siblingSuperCache, superManyCache, MIN_SIBLINGS, budget);

            int[] result = renderFileSection(lines, filePath, group.nodes, fileLines, lang,
                mode, pathNodeIds, namedNodeIds, uniqueNamedIds, subgraph, ranking.entryNodeIds,
                glueNodeIds, budget, fileNecessary, totalChars);

            if (result[0] == 1) {
                totalChars += result[1];
                filesIncluded++;
            } else if (result[0] == -1) {
                anyTrimmed = true;
            }
        }

        return anyTrimmed;
    }

    /**
     * 判断文件是否"必要"：是否包含入口点、流程节点或命名节点。
     *
     * <p>必要文件在预算紧张时也会被保留，非必要文件可能被跳过。
     *
     * @param nodes 文件中的节点列表
     * @param entryNodeIds 入口节点集合
     * @param pathNodeIds 流程节点集合（调用链上的节点）
     * @param namedNodeIds 命名节点集合（用户在查询中指定的节点）
     * @return true 如果文件必要，false 否则
     */
    private boolean isFileNecessary(List<Node> nodes, Set<String> entryNodeIds,
            Set<String> pathNodeIds, Set<String> namedNodeIds) {
        return nodes.stream().anyMatch(n ->
            entryNodeIds.contains(n.getId()) || pathNodeIds.contains(n.getId()) || namedNodeIds.contains(n.getId()));
    }

    /**
     * 读取文件内容为行列表。
     *
     * <p>处理逻辑：
     * <ol>
     *   <li>解析文件路径，如果是相对路径则拼接项目根目录</li>
     *   <li>检查文件是否存在</li>
     *   <li>读取文件内容</li>
     * </ol>
     *
     * @param filePath 文件路径（相对或绝对）
     * @return 文件行列表，如果读取失败返回 null
     */
    private List<String> readFileLines(String filePath) {
        Path absPath = Paths.get(filePath);
        if (!absPath.isAbsolute()) absPath = Paths.get(config.getProjectPath(), filePath);
        if (!Files.exists(absPath)) return null;

        try {
            return Files.readAllLines(absPath);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 确定文件的渲染模式。
     *
     * <p><b>渲染模式决策树：</b>
     * <pre>
     * 1. 检查是否是 spine 大文件（onSpineGodFile）→ Skeleton
     * 2. 检查是否是非 spine 多态兄弟（且未被豁免）→ Skeleton
     * 3. 检查是否是小文件（≤220/280 行）→ WholeFile
     * 4. 默认 → Clustered
     * </pre>
     *
     * @param nodes 文件中的节点列表
     * @param fileLines 文件内容行列表
     * @param pathNodeIds 流程节点集合
     * @param namedNodeIds 命名节点集合
     * @param uniqueNamedIds 去重后的命名节点集合
     * @param isCentral 是否中央文件
     * @param siblingSuperCache 多态兄弟缓存
     * @param superManyCache 多态超类型缓存
     * @param minSiblings 最小兄弟数量阈值
     * @param budget 输出预算配置
     * @return 渲染模式（SKELETON / WHOLE_FILE / CLUSTERED）
     */
    private RenderMode determineRenderMode(List<Node> nodes, List<String> fileLines,
            Set<String> pathNodeIds, Set<String> namedNodeIds, Set<String> uniqueNamedIds,
            boolean isCentral, Map<String, Boolean> siblingSuperCache,
            Map<String, Boolean> superManyCache, int minSiblings, ExploreOutputBudget budget) {

        boolean hasSpineNode = nodes.stream().anyMatch(n -> pathNodeIds.contains(n.getId()));
        boolean isPolySib = !hasSpineNode
            && isPolymorphicSibling(nodes, siblingSuperCache, minSiblings);
        boolean spareNamed = nodes.stream().anyMatch(n -> uniqueNamedIds.contains(n.getId()));
        boolean definesPolySuper = definesPolymorphicSupertype(nodes, superManyCache, minSiblings);
        boolean spared = spareNamed && !definesPolySuper;

        int namedBodyChars = 0;
        for (Node n : nodes) {
            if (isCallable(n.getKind().getValue())
                && (pathNodeIds.contains(n.getId()) || namedNodeIds.contains(n.getId()))) {
                if (n.getStartLine() > 0 && n.getEndLine() > n.getStartLine()) {
                    namedBodyChars += String.join("\n", fileLines.subList(
                        Math.max(0, n.getStartLine() - 1),
                        Math.min(fileLines.size(), n.getEndLine()))).length();
                }
            }
        }

        boolean onSpineGodFile = hasSpineNode
            && namedBodyChars > budget.maxCharsPerFile
            && nodes.stream().anyMatch(n ->
                isCallable(n.getKind().getValue())
                    && uniqueNamedIds.contains(n.getId())
                    && !pathNodeIds.contains(n.getId()));

        if (onSpineGodFile || (!hasSpineNode && isPolySib && !spared)) {
            return RenderMode.SKELETON;
        }

        int wholeFileMaxLines = isCentral ? 280 : 220;
        int wholeFileMaxChars = isCentral ? budget.maxCharsPerFile * 3 : budget.maxCharsPerFile * 3;
        if (fileLines.size() <= wholeFileMaxLines && fileLines.size() * 80 <= wholeFileMaxChars) {
            return RenderMode.WHOLE_FILE;
        }

        return RenderMode.CLUSTERED;
    }

    /**
     * 渲染单个文件的输出部分。
     *
     * @param lines 输出行列表（会被追加）
     * @param filePath 文件路径
     * @param nodes 文件中的节点列表
     * @param fileLines 文件内容行列表
     * @param lang 代码语言
     * @param mode 渲染模式
     * @param pathNodeIds 流程节点集合
     * @param namedNodeIds 命名节点集合
     * @param uniqueNamedIds 去重后的命名节点集合
     * @param subgraph 搜索结果子图
     * @param entryNodeIds 入口节点集合
     * @param glueNodeIds 图感知粘合的节点 ID
     * @param budget 输出预算配置
     * @param fileNecessary 文件是否必要
     * @param currentChars 当前已用字符数
     * @return int[2]，第一个元素：1=成功渲染，0=跳过，-1=因预算跳过；第二个元素：增加的字符数
     */
    private int[] renderFileSection(List<String> lines, String filePath, List<Node> nodes,
            List<String> fileLines, String lang, RenderMode mode,
            Set<String> pathNodeIds, Set<String> namedNodeIds, Set<String> uniqueNamedIds,
            Subgraph subgraph, Set<String> entryNodeIds, Set<String> glueNodeIds,
            ExploreOutputBudget budget, boolean fileNecessary, int currentChars) {

        switch (mode) {
            case SKELETON:
                return renderSkeletonMode(lines, filePath, nodes, fileLines, lang,
                    pathNodeIds, namedNodeIds, uniqueNamedIds, budget);

            case WHOLE_FILE:
                return renderWholeFileMode(lines, filePath, nodes, fileLines, lang,
                    budget, fileNecessary, currentChars);

            case CLUSTERED:
                return renderClusteredMode(lines, filePath, nodes, fileLines, lang,
                    subgraph, entryNodeIds, glueNodeIds, pathNodeIds, namedNodeIds, budget);

            default:
                return new int[]{0, 0};
        }
    }

    /**
     * 渲染骨架模式：只显示命名方法的完整体和其余方法的签名行。
     *
     * @param lines 输出行列表
     * @param filePath 文件路径
     * @param nodes 文件中的节点列表
     * @param fileLines 文件内容行列表
     * @param lang 代码语言
     * @param pathNodeIds 流程节点集合
     * @param namedNodeIds 命名节点集合
     * @param uniqueNamedIds 去重后的命名节点集合
     * @param budget 输出预算配置
     * @return int[2]，格式同 renderFileSection
     */
    private int[] renderSkeletonMode(List<String> lines, String filePath, List<Node> nodes,
            List<String> fileLines, String lang, Set<String> pathNodeIds,
            Set<String> namedNodeIds, Set<String> uniqueNamedIds, ExploreOutputBudget budget) {

        String skeleton = renderSkeleton(nodes, fileLines, pathNodeIds, namedNodeIds,
            uniqueNamedIds, budget, lang);
        if (skeleton.isEmpty()) return new int[]{0, 0};

        String tag = !pathNodeIds.isEmpty() && !namedNodeIds.isEmpty()
            ? "focused (the methods you named in full, the rest as signatures "
              + "\u2014 codegraph_explore a signature for its body; do NOT Read)"
            : "skeleton (signatures only "
              + "\u2014 codegraph_explore a name for its full body; do NOT Read)";
        lines.add(fileSectionHeader(filePath, tag));
        lines.add("");
        lines.add("```" + lang);
        lines.add(skeleton);
        lines.add("```");
        lines.add("");
        return new int[]{1, skeleton.length() + 120};
    }

    /**
     * 渲染完整文件模式：直接输出完整源代码。
     *
     * @param lines 输出行列表
     * @param filePath 文件路径
     * @param nodes 文件中的节点列表
     * @param fileLines 文件内容行列表
     * @param lang 代码语言
     * @param budget 输出预算配置
     * @param fileNecessary 文件是否必要
     * @param currentChars 当前已用字符数
     * @return int[2]，格式同 renderFileSection
     */
    private int[] renderWholeFileMode(List<String> lines, String filePath, List<Node> nodes,
            List<String> fileLines, String lang, ExploreOutputBudget budget,
            boolean fileNecessary, int currentChars) {

        if (!fileNecessary && currentChars + fileLines.size() * 80 + 200 > budget.maxOutputChars) {
            return new int[]{-1, 0};
        }

        String body = String.join("\n", fileLines);
        String numbered = numberSourceLines(body, 1);
        String names = extractSymbolNames(nodes, budget.maxSymbolsInFileHeader);
        lines.add(fileSectionHeader(filePath, names));
        lines.add("");
        lines.add("```" + lang);
        lines.add(numbered);
        lines.add("```");
        lines.add("");
        return new int[]{1, numbered.length() + 200};
    }

    /**
     * 渲染聚类模式：按相关性聚类成代码块输出。
     *
     * @param lines 输出行列表
     * @param filePath 文件路径
     * @param nodes 文件中的节点列表
     * @param fileLines 文件内容行列表
     * @param lang 代码语言
     * @param subgraph 搜索结果子图
     * @param entryNodeIds 入口节点集合
     * @param glueNodeIds 图感知粘合的节点 ID
     * @param pathNodeIds 流程节点集合
     * @param namedNodeIds 命名节点集合
     * @param budget 输出预算配置
     * @return int[2]，格式同 renderFileSection
     */
    private int[] renderClusteredMode(List<String> lines, String filePath, List<Node> nodes,
            List<String> fileLines, String lang, Subgraph subgraph, Set<String> entryNodeIds,
            Set<String> glueNodeIds, Set<String> pathNodeIds, Set<String> namedNodeIds,
            ExploreOutputBudget budget) {

        String clusters = renderClusters(nodes, fileLines, subgraph, entryNodeIds, glueNodeIds,
            connectedToEntry(subgraph, entryNodeIds), pathNodeIds, namedNodeIds, budget, lang, filePath);
        if (clusters.isEmpty()) return new int[]{0, 0};

        lines.add(clusters);
        return new int[]{1, clusters.length()};
    }

    /**
     * 渲染输出完整性提示和预算使用情况。
     *
     * @param lines 输出行列表（会被追加）
     * @param budget 输出预算配置
     * @param maxFiles 最大文件数限制
     * @param ranking 文件排序结果
     * @param anyTrimmed 是否有文件因预算限制被裁剪
     */
    private void renderCompletenessAndBudgetNotes(List<String> lines, ExploreOutputBudget budget,
            int maxFiles, FileRanking ranking, boolean anyTrimmed) {
        int totalChars = joinStrings(lines).length();
        int filesIncluded = 0;
        for (Map.Entry<String, FileGroup> entry : ranking.relevantFiles) {
            if (filesIncluded >= maxFiles) break;
            filesIncluded++;
        }

        if (budget.includeCompletenessSignal) {
            if (anyTrimmed || filesIncluded < ranking.relevantFiles.size()) {
                lines.add("*Some files were trimmed or omitted due to output budget limits.*");
                lines.add("");
            }
        }
        if (budget.includeBudgetNote) {
            lines.add("*Explore output budget: " + totalChars + "/"
                + budget.maxOutputChars + " chars, " + filesIncluded + "/" + maxFiles + " files.*");
        }
    }

    /**
     * 渲染模式枚举：定义文件内容的输出方式。
     */
    private enum RenderMode {
        SKELETON,   // 骨架模式：只显示命名方法完整内容，其余只显示签名
        WHOLE_FILE, // 完整文件模式：输出完整源代码
        CLUSTERED   // 聚类模式：按相关性聚类输出代码块
    }

    /**
     * 渲染输出前言部分：标题、统计信息、爆炸半径分析和关系图。
     *
     * <p><b>输出示例：</b>
     * <pre>
     * **Exploration: ContextBuilder findRelevantContext**
     *
     * Found 45 symbols across 8 files.
     *
     * [爆炸半径文本...]
     *
     * **Relationships**
     * **CALLS:**
     * - findRelevantContext → extractSymbols
     * - findRelevantContext → searchNodes
     * **REFERENCES:**
     * - ContextBuilder → FindOptions
     * </pre>
     */
    private void renderPreamble(List<String> lines, ExploreInput input,
            Subgraph subgraph, FileRanking ranking) {
        String query = input.query;
        ExploreOutputBudget budget = input.budget;

        // 标题和统计
        lines.add("**Exploration: " + StringUtils.escapeMarkdown(query) + "**");
        lines.add("");
        lines.add("Found " + subgraph.nodes.size() + " symbols across "
            + ranking.fileGroups.size() + " files.");
        lines.add("");

        // 爆炸半径分析
        String blastRadius = new BlastRadiusBuilder().build(subgraph, queries, traverser);
        if (!blastRadius.isEmpty()) lines.add(blastRadius);

        // 关系图（CALLS, REFERENCES, EXTENDS 等）
        if (budget.includeRelationships) {
            List<Edge> sigEdges = new ArrayList<>();
            for (Edge e : subgraph.edges) {
                // 排除 CONTAINS（AST 父子关系，信息量低）
                if (e.getKind() != EdgeKind.CONTAINS) sigEdges.add(e);
            }
            if (!sigEdges.isEmpty()) {
                lines.add("**Relationships**");
                lines.add("");

                // 按边类型分组
                Map<String, List<String[]>> byKind = new LinkedHashMap<>();
                for (Edge e : sigEdges) {
                    Node src = subgraph.nodes.get(e.getSource());
                    Node tgt = subgraph.nodes.get(e.getTarget());
                    if (src == null || tgt == null) continue;
                    byKind.computeIfAbsent(e.getKind().getValue(), k -> new ArrayList<>())
                          .add(new String[]{src.getName(), tgt.getName()});
                }

                // 渲染每种类型的关系列表（带截断）
                for (Map.Entry<String, List<String[]>> ke : byKind.entrySet()) {
                    int cap = budget.maxEdgesPerRelationshipKind;
                    List<String[]> shown = ke.getValue().subList(
                        0, Math.min(cap, ke.getValue().size()));
                    lines.add("**" + ke.getKey() + ":**");
                    for (String[] e : shown) lines.add("- " + e[0] + " \u2192 " + e[1]);
                    if (ke.getValue().size() > cap) {
                        lines.add("- ... and " + (ke.getValue().size() - cap) + " more");
                    }
                    lines.add("");
                }
            }
        }
    }

    /**
     * 计算与入口点直接通过边相连的节点集合。
     * <p>这是一个纯辅助方法，从 buildExplorationOutput 中提取出来以避免重复计算。
     */
    private Set<String> connectedToEntry(Subgraph subgraph, Set<String> entryNodeIds) {
        Set<String> connected = new HashSet<>();
        for (Edge e : subgraph.edges) {
            if (entryNodeIds.contains(e.getSource())) connected.add(e.getTarget());
            if (entryNodeIds.contains(e.getTarget())) connected.add(e.getSource());
        }
        return connected;
    }

    // ================================================================================================
    // 渲染辅助方法
    // ================================================================================================

    private static int clamp(int val, int lo, int hi) {
        return Math.max(lo, Math.min(hi, val));
    }

    private static String joinStrings(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append("\n");
        return sb.toString();
    }

    private static String joinNodeNames(Collection<Node> nodes) {
        StringBuilder sb = new StringBuilder();
        for (Node n : nodes) sb.append(n.getName().toLowerCase()).append(" ");
        return sb.toString();
    }

    private static String detectLanguage(String filePath) {
        if (filePath == null) return "";
        if (filePath.endsWith(".java")) return "java";
        if (filePath.endsWith(".kt") || filePath.endsWith(".kts")) return "kotlin";
        if (filePath.endsWith(".ts") || filePath.endsWith(".tsx")) return "typescript";
        if (filePath.endsWith(".js") || filePath.endsWith(".jsx") || filePath.endsWith(".mjs")) return "javascript";
        if (filePath.endsWith(".py")) return "python";
        if (filePath.endsWith(".go")) return "go";
        if (filePath.endsWith(".rs")) return "rust";
        if (filePath.endsWith(".rb")) return "ruby";
        if (filePath.endsWith(".php")) return "php";
        if (filePath.endsWith(".swift")) return "swift";
        if (filePath.endsWith(".cs")) return "csharp";
        if (filePath.endsWith(".cpp") || filePath.endsWith(".cc") || filePath.endsWith(".cxx") || filePath.endsWith(".c")) return "cpp";
        if (filePath.endsWith(".h") || filePath.endsWith(".hpp")) return "cpp";
        if (filePath.endsWith(".scala")) return "scala";
        if (filePath.endsWith(".lua")) return "lua";
        if (filePath.endsWith(".dart")) return "dart";
        if (filePath.endsWith(".vue")) return "vue";
        if (filePath.endsWith(".svelte")) return "svelte";
        if (filePath.endsWith(".astro")) return "astro";
        return "";
    }

    /**
     * 判断节点类型是否为可调用体（方法/函数/组件/构造函数）。
     * <p>用于骨架渲染时决定是否展开完整方法体。
     */
    private static boolean isCallable(String kind) {
        return "method".equalsIgnoreCase(kind) || "function".equalsIgnoreCase(kind)
            || "component".equalsIgnoreCase(kind) || "constructor".equalsIgnoreCase(kind);
    }

    /**
     * 生成文件章节标题。
     * <p><b>示例输出：</b>
     * <pre>**ExploreTool.java** — handleExplore, execute — `com/codegraph/.../ExploreTool.java`</pre>
     */
    private static String fileSectionHeader(String filePath, String suffix) {
        String fileName = filePath.contains("/") ? filePath.substring(filePath.lastIndexOf('/') + 1) : filePath;
        return "**" + fileName + "** \u2014 " + suffix + " \u2014 `" + filePath + "`";
    }

    /**
     * 为源代码添加行号，格式与 {@code cat -n} 一致。
     * <p><b>示例输出：</b>
     * <pre>
     * 1`\tpackage com.example;
     * 2`\t
     * 3`\tpublic class Foo {
     * </pre>
     */
    private static String numberSourceLines(String body, int startLine) {
        String[] rawLines = body.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rawLines.length; i++) {
            sb.append(startLine + i).append("`\t").append(rawLines[i]).append("\n");
        }
        return sb.toString();
    }

    /**
     * 提取节点中的符号名（排除 import/export），限制数量。
     * <p>用于文件标题中展示文件包含的符号列表。
     */
    private static String extractSymbolNames(Collection<Node> nodes, int max) {
        Set<String> names = new LinkedHashSet<>();
        for (Node n : nodes) {
            if (n.getKind() != NodeKind.IMPORT && n.getKind() != NodeKind.EXPORT) {
                names.add(n.getName());
            }
            if (names.size() >= max) break;
        }
        List<String> list = new ArrayList<>(names);
        String joined = String.join(", ", list);
        int omitted = names.size() - list.size();
        if (omitted > 0) joined += ", +" + omitted + " more";
        return joined;
    }

    /**
     * 判断查询是否涉及测试相关内容。
     * <p>包含 "test"/"testing"/"spec"/"verify" 关键词时被视为测试查询，
     * 此时不会对测试文件降权或过滤。
     */
    private static boolean mentionsTests(String query) {
        if (query == null) return false;
        String lc = query.toLowerCase();
        return lc.contains("test") || lc.contains("testing")
            || lc.contains("spec") || lc.contains("verify");
    }

    /**
     * 判断是否为配置叶子节点（配置文件中的常量/字段）。
     * <p>这类节点信息量低，在文件分组时会被过滤掉。
     */
    private static boolean isConfigLeafNode(Node n) {
        if (n.getKind() == NodeKind.CONSTANT || n.getKind() == NodeKind.FIELD) {
            String name = n.getName() != null ? n.getName().toLowerCase() : "";
            String qname = n.getQualifiedName() != null ? n.getQualifiedName().toLowerCase() : "";
            return name.startsWith("${") || qname.contains(".properties")
                || qname.contains(".yml") || qname.contains(".yaml");
        }
        return false;
    }

    /**
     * 判断路径是否为低价值文件（测试/I18N/图标等）。
     * <p>低价值文件在非测试查询中会被过滤，在排序中会排在最后。
     */
    private static boolean isLowValue(String path) {
        if (path == null) return false;
        String lp = path.toLowerCase();
        return lp.contains("/tests/") || lp.contains("/spec/") || lp.contains("/__tests__/")
            || lp.contains("/test/") || lp.contains("/specs/")
            || path.endsWith("_test.go") || path.endsWith("_test.py")
            || path.endsWith("_spec.rb") || path.endsWith("_test.rb")
            || path.endsWith(".test.ts") || path.endsWith(".spec.ts")
            || path.endsWith(".test.tsx") || path.endsWith(".spec.tsx")
            || path.endsWith(".test.js") || path.endsWith(".spec.js")
            || path.endsWith(".test.java") || path.endsWith(".spec.java")
            || path.endsWith("_test.java") || path.endsWith("_spec.java")
            || path.endsWith("_tests.java") || path.endsWith("_test.kt")
            || path.endsWith("_spec.kt") || lp.contains("/icons/") || lp.contains("/i18n/");
    }

    /**
     * 判断节点组是否为多态兄弟（Polymorphic Siblings）。
     *
     * <p><b>定义：</b>
     * 如果节点组中任一节点 extends/implements 的父类型有 ≥ minSiblings 个子类/实现，
     * 则该组节点是多态兄弟。多态兄弟文件在输出中会触发骨架渲染而非完整文件渲染。
     *
     * <p><b>示例：</b>
     * <pre>
     * 查询命中 "LruCache" 类，它 extends "AbstractCache"。
     * AbstractCache 有 5 个子类（LruCache, FifoCache, TtlCache, ...）。
     * → isPolymorphicSibling 返回 true → 渲染骨架而非完整文件
     * </pre>
     *
     * <p><b>缓存策略：</b>用 {@code cache} 缓存已计算的父类型的多态性，
     * 避免重复查询数据库。
     */
    private boolean isPolymorphicSibling(List<Node> nodes, Map<String, Boolean> cache, int minSiblings) {
        for (Node n : nodes) {
            try {
                List<Edge> outgoing = queries.getOutgoingEdges(n.getId());
                for (Edge e : outgoing) {
                    if (e.getKind() != EdgeKind.EXTENDS && e.getKind() != EdgeKind.IMPLEMENTS) continue;
                    String target = e.getTarget();

                    // 检查缓存
                    Boolean cached = cache.get(target);
                    if (cached != null) { if (cached) return true; continue; }

                    try {
                        List<Edge> incoming = queries.getIncomingEdges(target);
                        int count = 0;
                        for (Edge ie : incoming) {
                            if (ie.getKind() == EdgeKind.EXTENDS || ie.getKind() == EdgeKind.IMPLEMENTS) count++;
                        }
                        boolean many = count >= minSiblings;
                        cache.put(target, many);
                        if (many) return true;
                    } catch (SQLException ignored) {}
                }
            } catch (SQLException ignored) {}
        }
        return false;
    }

    /**
     * 判断节点组是否定义了多态超类型（即被多个子类 extends/implements）。
     *
     * <p>例如 {@code AbstractController} 被 10 个类继承 → 这是一个多态超类型。
     * 定义了多态超类型的文件在渲染时不会被豁免（即仍可能触发骨架渲染），
     * 因为这类文件通常很大且包含大量抽象方法。
     */
    private boolean definesPolymorphicSupertype(List<Node> nodes, Map<String, Boolean> cache, int minSiblings) {
        for (Node n : nodes) {
            String kn = n.getKind().getValue();
            if (!kn.equals("class") && !kn.equals("interface") && !kn.equals("struct")
                && !kn.equals("trait") && !kn.equals("protocol") && !kn.equals("type_alias")
                && !kn.equals("enum")) continue;
            Boolean cached = cache.get(n.getId());
            if (cached != null) return cached;
            try {
                List<Edge> incoming = queries.getIncomingEdges(n.getId());
                int count = 0;
                for (Edge e : incoming) {
                    if (e.getKind() == EdgeKind.EXTENDS || e.getKind() == EdgeKind.IMPLEMENTS) count++;
                }
                boolean many = count >= minSiblings;
                cache.put(n.getId(), many);
                if (many) return true;
            } catch (SQLException ignored) {}
        }
        return false;
    }

    // ================================================================================================
    // 源代码渲染
    // ================================================================================================

    /**
     * 渲染骨架视图：展开高优先级节点的方法体，其余节点只显示签名行。
     *
     * <p><b>优先级：</b>
     * <ol>
     *   <li>{@code pathNodeIds} — 调用链上的节点（最高优先级，完整展开）</li>
     *   <li>{@code uniqueNamedIds} — 用户命名的节点（次优先级）</li>
     *   <li>其余可调用节点 — 仅显示签名</li>
     * </ol>
     *
     * <p><b>示例输出：</b>
     * <pre>
     * 30`\tpublic void handleExplore(Map<String, Object> args) {
     * 31`\t    String query = requireArg(args, "query");
     * ...完整方法体...
     * 50`\t}
     * 51\tprivate void parseInput() { ... }
     * 52\tprivate void searchCode() { ... }
     * </pre>
     */
    private String renderSkeleton(Collection<Node> nodes, List<String> fileLines,
            Set<String> pathNodeIds, Set<String> namedNodeIds, Set<String> uniqueNamedIds,
            ExploreOutputBudget budget, String lang) {

        List<Node> syms = new ArrayList<>();
        for (Node n : nodes) {
            if ((n.getKind() == NodeKind.IMPORT || n.getKind() == NodeKind.EXPORT) && n.getStartLine() <= 0) continue;
            syms.add(n);
        }
        syms.sort(Comparator.comparingInt(n -> n.getStartLine() > 0 ? n.getStartLine() : Integer.MAX_VALUE));

        // 收集需要完整展开的节点（bodyIds）
        Set<String> bodyIds = new LinkedHashSet<>();
        int bodyChars = 0;
        int bodyCap = (int)(budget.maxCharsPerFile * 1.5);

        final Set<String> CALLABLE_KINDS = new HashSet<>(Arrays.asList(
            "method", "function", "component", "constructor", "property"));

        for (Node n : syms) {
            String kn = n.getKind().getValue();
            int priority = !CALLABLE_KINDS.contains(kn) ? 99
                : pathNodeIds.contains(n.getId()) ? 0      // 调用链节点 → 最高优先级
                : uniqueNamedIds.contains(n.getId()) ? 1   // 用户命名节点 → 次高优先级
                : 99;
            if (priority >= 99) continue;
            if (n.getEndLine() < n.getStartLine()) continue;

            int sz = 0;
            try {
                sz = String.join("\n", fileLines.subList(
                    Math.max(0, n.getStartLine() - 1), Math.min(fileLines.size(), n.getEndLine()))).length();
            } catch (Exception e) { continue; }
            if (bodyChars + sz > bodyCap && !bodyIds.isEmpty()) continue;
            bodyIds.add(n.getId());
            bodyChars += sz;
        }

        // 按源顺序渲染：完整展开 bodyIds 中的节点，其余只渲染签名
        List<String> skel = new ArrayList<>();
        int coveredUntil = 0;     // 已覆盖到的行号（避免重叠渲染）
        int sigCount = 0;
        int sigDropped = 0;
        int SIG_MAX = Math.max(12, budget.maxSymbolsInFileHeader * 2);

        for (Node n : syms) {
            // 跳过已被前面节点覆盖的区域
            if (n.getStartLine() > 0 && coveredUntil > 0 && n.getStartLine() <= coveredUntil) continue;

            if (bodyIds.contains(n.getId())) {
                // 模式 A：完整展开方法体（带行号）
                int start = Math.max(0, n.getStartLine() - 1);
                int end = Math.min(fileLines.size(), n.getEndLine());
                String body = String.join("\n", fileLines.subList(start, end));
                skel.add(numberSourceLines(body, n.getStartLine()));
                coveredUntil = n.getEndLine();
            } else {
                // 模式 B：仅渲染签名行
                // 在声明的上下 4 行范围内查找包含节点名的行作为签名
                int lineNo = n.getStartLine();
                for (int k = 0; k < 4 && lineNo + k < fileLines.size(); k++) {
                    String line = fileLines.get(lineNo - 1 + k);
                    if (line != null && line.contains(n.getName())) { lineNo = lineNo + k; break; }
                }
                if (lineNo <= coveredUntil) continue;
                if (sigCount >= SIG_MAX) { sigDropped++; continue; }
                String sig = fileLines.get(Math.min(lineNo - 1, fileLines.size() - 1));
                if (sig != null && !sig.trim().isEmpty()) {
                    skel.add(lineNo + "\t" + sig.trim());
                    sigCount++;
                }
            }
        }
        if (sigDropped > 0) skel.add("\u2026 +" + sigDropped + " more (signatures elided)");
        return String.join("\n", skel);
    }

    /**
     * 渲染聚类视图：将大文件中高重要性的相关代码块分组为段落。
     *
     * <p><b>工作原理：</b>
     * <ol>
     *   <li>为每个节点计算重要性分数（入口点=10, 命名节点=9, 粘合节点=6, 相邻节点=3）</li>
     *   <li>合并行间距 ≤ gapThreshold 的相邻节点范围</li>
     *   <li>只输出重要性 ≥ 3 的聚类块</li>
     *   <li>在预算限制内截断</li>
     * </ol>
     *
     * <p><b>示例输出：</b>
     * <pre>
     * **LargeFile.java** — clustered — `path/to/LargeFile.java`
     *
     * ```java
     * 120`\tpublic String process(String input) {
     * ...相关代码块...
     * 135`\t}
     * ```
     *
     * ```java
     * 280`\tprivate void validate(Request req) {
     * ...相关代码块...
     * 295`\t}
     * ```
     * </pre>
     */
    private String renderClusters(Collection<Node> nodes, List<String> fileLines,
            Subgraph subgraph, Set<String> entryNodeIds, Set<String> glueNodeIds,
            Set<String> connectedToEntry, Set<String> pathNodeIds, Set<String> namedNodeIds,
            ExploreOutputBudget budget, String lang, String filePath) {

        final Set<String> ENVELOPE_KINDS = new HashSet<>(Arrays.asList(
            "file", "module", "class", "struct", "interface", "enum",
            "namespace", "protocol", "trait", "component"));

        // 为每个节点创建带重要性分数的范围
        List<NodeRange> ranges = new ArrayList<>();
        for (Node n : nodes) {
            if (n.getStartLine() <= 0 || n.getEndLine() <= n.getStartLine()) continue;
            // 跳过覆盖文件超过一半的容器节点（如整个 class 的定义）
            if (ENVELOPE_KINDS.contains(n.getKind().getValue())
                && (n.getEndLine() - n.getStartLine() + 1) > fileLines.size() * 0.5) continue;

            int importance = 1;
            if (entryNodeIds.contains(n.getId())) importance = 10;
            else if (namedNodeIds.contains(n.getId())) importance = 9;
            else if (glueNodeIds.contains(n.getId())) importance = 6;
            else if (connectedToEntry.contains(n.getId())) importance = 3;

            ranges.add(new NodeRange(n, importance, pathNodeIds.contains(n.getId())));
        }

        if (ranges.isEmpty()) return "";

        ranges.sort(Comparator.comparingInt(r -> r.startLine));

        // 合并相邻范围（间距 ≤ gapThreshold）
        List<NodeRange> merged = new ArrayList<>();
        for (NodeRange r : ranges) {
            if (!merged.isEmpty()
                && r.startLine - merged.get(merged.size() - 1).endLine <= budget.gapThreshold) {
                NodeRange last = merged.get(merged.size() - 1);
                last.endLine = Math.max(last.endLine, r.endLine);
                last.importance = Math.max(last.importance, r.importance);
                if (r.isSpine) last.isSpine = true;
            } else {
                merged.add(r);
            }
        }

        // 按重要性选择要输出的范围（在字符预算内）
        List<NodeRange> selected = new ArrayList<>();
        int bodyChars = 0;
        int bodyCap = budget.maxCharsPerFile * 3;
        for (NodeRange r : merged) {
            if (r.importance >= 3) {
                int sz = 0;
                try {
                    sz = String.join("\n", fileLines.subList(
                        Math.max(0, r.startLine - 1), Math.min(fileLines.size(), r.endLine))).length();
                } catch (Exception e) {}
                if (bodyChars + sz > bodyCap && !selected.isEmpty()) continue;
                selected.add(r);
                bodyChars += sz;
            }
        }
        if (selected.isEmpty()) return "";

        // 渲染输出
        List<String> lines = new ArrayList<>();
        List<Node> allRangeNodes = new ArrayList<>();
        for (NodeRange r : merged) allRangeNodes.add(r.node);
        String names = extractSymbolNames(allRangeNodes, budget.maxSymbolsInFileHeader);
        lines.add(fileSectionHeader(filePath, "clustered"));
        lines.add("");

        int shown = 0;
        int maxClusters = budget.maxCharsPerFile / 40;
        for (NodeRange r : selected) {
            if (shown++ >= maxClusters) break;
            int start = Math.max(0, r.startLine - 1);
            int end = Math.min(fileLines.size(), r.endLine);
            String chunk = String.join("\n", fileLines.subList(start, end));
            lines.add("```" + lang);
            lines.add(numberSourceLines(chunk, r.startLine));
            lines.add("```");
            lines.add("");
        }

        return String.join("\n", lines);
    }

    /**
     * 获取索引中所有文件的数量。
     * <p>用于自适应预算计算——文件越多，输出预算越紧。
     */
    private int countIndexedFiles() {
        try { return queries.getAllFiles().size(); }
        catch (SQLException e) { return 0; }
    }

    // ================================================================================================
    // 内部类
    // ================================================================================================

    /**
     * 解析后的探索输入参数。
     * <p>封装了校验后的 query、自适应预算、ContextBuilder 和搜索选项。
     */
    private static class ExploreInput {
        final String query;
        final ExploreOutputBudget budget;
        final int maxFiles;
        final ContextBuilder ctxBuilder;
        final ContextBuilder.FindOptions opts;

        ExploreInput(String query, ExploreOutputBudget budget, int maxFiles,
                     ContextBuilder ctxBuilder, ContextBuilder.FindOptions opts) {
            this.query = query;
            this.budget = budget;
            this.maxFiles = maxFiles;
            this.ctxBuilder = ctxBuilder;
            this.opts = opts;
        }
    }

    /**
     * 文件排序结果，封装了 Steps 5-7 的所有中间计算产物。
     *
     * <p><b>字段说明：</b>
     * <ul>
     *   <li>{@code fileGroups} — 所有文件的节点分组（含分数）</li>
     *   <li>{@code entryNodeIds} — 入口节点 ID 集合（root + namedSeed）</li>
     *   <li>{@code entryFiles} — 包含入口节点的文件路径集合</li>
     *   <li>{@code centralFiles} — 中央文件（图相关性 Top-2 + 术语命中）</li>
     *   <li>{@code fileGraphScore} — 每个文件的 RWR 图相关性总分</li>
     *   <li>{@code fileTermHits} — 每个文件的查询词命中数</li>
     *   <li>{@code maxGraph} — 最高图相关性分数（用于门控阈值计算）</li>
     *   <li>{@code relevantFiles} — 排序后的相关文件列表</li>
     * </ul>
     */
    private static class FileRanking {
        final Map<String, FileGroup> fileGroups;
        final Set<String> entryNodeIds;
        final Set<String> entryFiles;
        final Set<String> centralFiles;
        final Map<String, Double> fileGraphScore;
        final Map<String, Integer> fileTermHits;
        final double maxGraph;
        List<Map.Entry<String, FileGroup>> relevantFiles;

        FileRanking(Map<String, FileGroup> fileGroups, Set<String> entryNodeIds,
                    Set<String> entryFiles, Set<String> centralFiles,
                    Map<String, Double> fileGraphScore, Map<String, Integer> fileTermHits,
                    double maxGraph, List<Map.Entry<String, FileGroup>> relevantFiles) {
            this.fileGroups = fileGroups;
            this.entryNodeIds = entryNodeIds;
            this.entryFiles = entryFiles;
            this.centralFiles = centralFiles;
            this.fileGraphScore = fileGraphScore;
            this.fileTermHits = fileTermHits;
            this.maxGraph = maxGraph;
            this.relevantFiles = relevantFiles;
        }
    }

    /**
     * 文件分组：同一文件中的所有节点及其累计分数。
     */
    private static class FileGroup {
        List<Node> nodes = new ArrayList<>();
        int score = 0;
    }

    /**
     * 节点行范围：用于聚类渲染时表示代码块的位置和重要性。
     */
    private static class NodeRange {
        Node node;
        int startLine;
        int endLine;
        int importance;
        boolean isSpine;

        NodeRange(Node n, int importance, boolean isSpine) {
            this.node = n;
            this.startLine = n.getStartLine();
            this.endLine = n.getEndLine();
            this.importance = importance;
            this.isSpine = isSpine;
        }
    }
}