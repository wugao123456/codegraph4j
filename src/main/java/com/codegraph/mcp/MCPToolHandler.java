package com.codegraph.mcp;

import com.codegraph.context.*;
import com.codegraph.core.Edge;
import com.codegraph.core.Node;
import com.codegraph.core.types.EdgeKind;
import com.codegraph.core.types.NodeKind;
import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;
import com.codegraph.graph.GraphQueryManager;
import com.codegraph.graph.GraphTraverser;
import com.codegraph.graph.GraphTraverser.*;
import com.codegraph.mcp.MCPTransport.*;
import com.codegraph.resolution.frameworks.FrameworkRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * MCP 工具处理器 — 注册和执行所有 MCP 工具。
 * 对标 codegraph 的 tools.ts。
 *
 * 对外暴露：
 * - codegraph_explore（默认启用 — 最主要工具）
 * - codegraph_search
 * - codegraph_callers / codegraph_callees
 * - codegraph_impact
 * - codegraph_node
 * - codegraph_status
 * - codegraph_files
 */
public class MCPToolHandler {

    private static final Logger logger = LoggerFactory.getLogger(MCPToolHandler.class);

    // 工具白名单（默认只暴露 explore）
    private static final Set<String> DEFAULT_MCP_TOOLS = new HashSet<>(
        Arrays.asList("explore", "search", "callers", "callees", "impact", "node", "status", "files"));

    static {
        // 可通过环境变量覆盖
        String env = System.getenv("CODEGRAPH_MCP_TOOLS");
        if (env != null && !env.isEmpty()) {
            DEFAULT_MCP_TOOLS.clear();
            DEFAULT_MCP_TOOLS.addAll(Arrays.asList(env.split(",")));
        }
    }

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

    private final String projectPath;
    private final DatabaseConnection db;
    
    // 使用 db 获取数据库连接状态，用于 status 工具
    public DatabaseConnection getDb() {
        return db;
    }
    private final QueryBuilder queries;
    private final GraphTraverser traverser;
    private final GraphQueryManager graphManager;

    public MCPToolHandler(String projectPath, DatabaseConnection db, QueryBuilder queries) {
        this.projectPath = projectPath;
        this.db = db;
        this.queries = queries;
        this.traverser = new GraphTraverser(queries);
        this.graphManager = new GraphQueryManager(queries);
    }

    // ---- 工具列表 ----

    public List<ToolDefinition> getTools() {
        List<ToolDefinition> tools = new ArrayList<>();

        if (isEnabled("explore")) tools.add(exploreTool());
        if (isEnabled("search")) tools.add(searchTool());
        if (isEnabled("callers")) tools.add(callersTool());
        if (isEnabled("callees")) tools.add(calleesTool());
        if (isEnabled("impact")) tools.add(impactTool());
        if (isEnabled("node")) tools.add(nodeTool());
        if (isEnabled("status")) tools.add(statusTool());
        if (isEnabled("files")) tools.add(filesTool());
        logger.info("Enabled tools: {}", tools);
        return tools;
    }

    // ---- 工具执行入口 ----

    public ToolCallResult execute(String toolName, Map<String, Object> args) {
        try {
            switch (toolName) {
                case "codegraph_search":  return handleSearch(args);
                case "codegraph_callers": return handleCallers(args);
                case "codegraph_callees": return handleCallees(args);
                case "codegraph_impact":  return handleImpact(args);
                case "codegraph_node":    return handleNode(args);
                case "codegraph_explore": return handleExplore(args);
                case "codegraph_status":  return handleStatus(args);
                case "codegraph_files":   return handleFiles(args);
                default: return error("Unknown tool: " + toolName);
            }
        } catch (Exception e) {
            logger.error("Tool {} failed", toolName, e);
            return error("Tool execution failed: " + e.getMessage());
        }
    }

    // ============ Tool 1: codegraph_search ============

    private ToolDefinition searchTool() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", prop("string", "Symbol name to search for"));
        props.put("kind", propWithEnum("string", "Node kind filter",
            "CLASS", "METHOD", "FIELD", "INTERFACE", "ENUM",
            "FUNCTION", "VARIABLE", "ROUTE", "COMPONENT", "CONSTANT",
            "MODULE", "IMPORT", "FILE", "TYPE_ALIAS"));
        props.put("limit", propWithDefault("integer", "Max results", 10));
        props.put("projectPath", prop("string", "Project root directory (optional)"));
        schema.put("properties", props);
        schema.put("required", Arrays.asList("query"));
        return new ToolDefinition("codegraph_search",
            "Symbol search by name. Returns locations (no source code).",
            schema);
    }
    private ToolCallResult handleSearch(Map<String, Object> args) throws SQLException {
        String query = requireArg(args, "query");
        String kind = strArg(args, "kind", null);
        int limit = intArg(args, "limit", 10);

        List<Node> searchResults = queries.searchNodes(query);

        // 按 kind 过滤（如果指定）
        List<Node> results;
        if (kind != null && !kind.isEmpty()) {
            try {
                com.codegraph.core.types.NodeKind nodeKind =
                    com.codegraph.core.types.NodeKind.valueOf(kind.toUpperCase());
                results = new ArrayList<>();
                for (Node n : searchResults) {
                    if (n.getKind() == nodeKind) results.add(n);
                }
            } catch (IllegalArgumentException e) {
                results = searchResults; // 无效 kind，不过滤
            }
        } else {
            results = searchResults;
        }

        if (results.size() > limit) results = results.subList(0, limit);

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(results.size()).append(" results for \"").append(query).append("\":\n\n");
        for (int i = 0; i < results.size(); i++) {
            Node n = results.get(i);
            sb.append(String.format("%d. %s %s [%s] %s:%d\n",
                i + 1, n.getKind().name(), n.getName(),
                truncate(n.getId(), 12), n.getFilePath(), n.getStartLine()));
        }
        return text(sb.toString());
    }

    // ============ Tool 2: codegraph_callers ============

    private ToolDefinition callersTool() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("symbol", prop("string", "Symbol name to find callers for"));
        props.put("file", prop("string", "File path filter (optional)"));
        props.put("limit", propWithDefault("integer", "Max results", 20));
        schema.put("properties", props);
        schema.put("required", Arrays.asList("symbol"));
        return new ToolDefinition("codegraph_callers",
            "List functions that call a given symbol.",
            schema);
    }

    private ToolCallResult handleCallers(Map<String, Object> args) throws SQLException {
        String symbol = requireArg(args, "symbol");
        String file = strArg(args, "file", null);
        int limit = intArg(args, "limit", 20);

        // 查找符号
        Node node = findSymbol(symbol, file);
        if (node == null) return text("Symbol not found: " + symbol);

        List<CallerInfo> callers = traverser.getCallers(node.getId(), 1);
        if (callers.size() > limit) callers = callers.subList(0, limit);

        StringBuilder sb = new StringBuilder();
        sb.append("Callers of ").append(node.getName())
            .append(" (").append(node.getKind().name()).append("):\n\n");
        if (callers.isEmpty()) {
            sb.append("No callers found.\n");
        } else {
            for (CallerInfo c : callers) {
                sb.append(String.format("- %s %s [%s] %s:%d\n",
                    c.node.getKind().name(), c.node.getName(),
                    truncate(c.node.getId(), 12),
                    c.node.getFilePath(), c.node.getStartLine()));
            }
        }
        return text(sb.toString());
    }

    // ============ Tool 3: codegraph_callees ============

    private ToolDefinition calleesTool() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("symbol", prop("string", "Symbol name to find callees for"));
        props.put("file", prop("string", "File path filter (optional)"));
        props.put("limit", propWithDefault("integer", "Max results", 20));
        schema.put("properties", props);
        schema.put("required", Arrays.asList("symbol"));
        return new ToolDefinition("codegraph_callees",
            "List functions that a given symbol calls.",
            schema);
    }

    private ToolCallResult handleCallees(Map<String, Object> args) throws SQLException {
        String symbol = requireArg(args, "symbol");
        String file = strArg(args, "file", null);
        int limit = intArg(args, "limit", 20);

        Node node = findSymbol(symbol, file);
        if (node == null) return text("Symbol not found: " + symbol);

        List<CalleeInfo> callees = traverser.getCallees(node.getId(), 1);
        if (callees.size() > limit) callees = callees.subList(0, limit);

        StringBuilder sb = new StringBuilder();
        sb.append("Callees of ").append(node.getName())
            .append(" (").append(node.getKind().name()).append("):\n\n");
        if (callees.isEmpty()) {
            sb.append("No callees found.\n");
        } else {
            for (CalleeInfo c : callees) {
                sb.append(String.format("- %s %s [%s] %s:%d\n",
                    c.node.getKind().name(), c.node.getName(),
                    truncate(c.node.getId(), 12),
                    c.node.getFilePath(), c.node.getStartLine()));
            }
        }
        return text(sb.toString());
    }

    // ============ Tool 4: codegraph_impact ============

    private ToolDefinition impactTool() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("symbol", prop("string", "Symbol name to analyze impact for"));
        props.put("file", prop("string", "File path filter (optional)"));
        props.put("depth", propWithDefault("integer", "Traversal depth", 2));
        schema.put("properties", props);
        schema.put("required", Arrays.asList("symbol"));
        return new ToolDefinition("codegraph_impact",
            "Impact radius analysis. Finds what would be affected by changes to a symbol.",
            schema);
    }

    private ToolCallResult handleImpact(Map<String, Object> args) throws SQLException {
        String symbol = requireArg(args, "symbol");
        String file = strArg(args, "file", null);
        int depth = intArg(args, "depth", 2);

        Node node = findSymbol(symbol, file);
        if (node == null) return text("Symbol not found: " + symbol);

        com.codegraph.graph.GraphTraverser.Subgraph subgraph = traverser.getImpactRadius(node.getId(), depth);

        StringBuilder sb = new StringBuilder();
        sb.append("Impact radius of ").append(node.getName())
            .append(" (depth ").append(depth).append("):\n\n");
        sb.append(subgraph.nodes.size()).append(" nodes potentially affected:\n");

        // 按文件分组
        Map<String, List<Node>> byFile = new LinkedHashMap<>();
        for (Node n : subgraph.nodes.values()) {
            byFile.computeIfAbsent(n.getFilePath(), k -> new ArrayList<>()).add(n);
        }
        for (Map.Entry<String, List<Node>> entry : byFile.entrySet()) {
            sb.append("\n  ").append(entry.getKey()).append("\n");
            for (Node n : entry.getValue()) {
                sb.append(String.format("    %s %s (line %d)\n",
                    n.getKind().name(), n.getName(), n.getStartLine()));
            }
        }
        return text(sb.toString());
    }

    // ============ Tool 5: codegraph_node ============

    private ToolDefinition nodeTool() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("symbol", prop("string", "Symbol name to look up"));
        props.put("file", prop("string", "File path or suffix (e.g. Controller.java)"));
        props.put("includeCode", propWithDefault("boolean", "Include source code", false));
        schema.put("properties", props);
        return new ToolDefinition("codegraph_node",
            "Get symbol details: location, signature, source, callers/callees trace.",
            schema);
    }

    private ToolCallResult handleNode(Map<String, Object> args) throws SQLException {
        String symbol = strArg(args, "symbol", null);
        String file = strArg(args, "file", null);
        boolean includeCode = boolArg(args, "includeCode", false);

        if (symbol == null && file == null) return error("Must provide 'symbol' or 'file'");

        if (symbol == null) {
            // 文件模式：列出文件中所有节点
            List<Node> fileNodes = queries.getNodesInFile(file);
            StringBuilder sb = new StringBuilder();
            sb.append("Symbols in ").append(file).append(":\n\n");
            for (Node n : fileNodes) {
                sb.append(String.format("  %s %s (line %d)\n",
                    n.getKind().name(), n.getName(), n.getStartLine()));
            }
            sb.append("\nTotal: ").append(fileNodes.size()).append(" symbols\n");
            return text(sb.toString());
        }

        // 符号模式
        Node node = findSymbol(symbol, file);
        if (node == null) return text("Symbol not found: " + symbol);

        GraphQueryManager.NodeContext ctx = graphManager.getContext(node.getId());

        StringBuilder sb = new StringBuilder();
        sb.append("=== Symbol Details ===\n\n");
        sb.append("Name:        ").append(node.getName()).append("\n");
        sb.append("Kind:        ").append(node.getKind().name()).append("\n");
        sb.append("QName:       ").append(node.getQualifiedName()).append("\n");
        sb.append("File:        ").append(node.getFilePath()).append("\n");
        sb.append("Location:    line ").append(node.getStartLine())
            .append(":").append(node.getStartColumn())
            .append(" - ").append(node.getEndLine())
            .append(":").append(node.getEndColumn()).append("\n");

        if (node.getSignature() != null) {
            sb.append("Signature:   ").append(node.getSignature()).append("\n");
        }
        if (node.getReturnType() != null) {
            sb.append("Returns:     ").append(node.getReturnType()).append("\n");
        }

        sb.append("\nAncestors:   ")
            .append(ctx.ancestors.size() > 0 ? ctx.ancestors.get(0).getName() : "(root)")
            .append("\n");
        sb.append("Children:    ").append(ctx.children.size()).append(" direct children\n");
        sb.append("Incoming:    ").append(ctx.incomingRefs.size()).append(" refs\n");
        sb.append("Outgoing:    ").append(ctx.outgoingRefs.size()).append(" refs\n");

        if (includeCode && node.getDocstring() != null) {
            sb.append("\n--- Docstring ---\n").append(node.getDocstring()).append("\n");
        }

        return text(sb.toString());
    }

    // ============ Tool 6: codegraph_explore（主要工具） ============

    private ToolDefinition exploreTool() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", prop("string", "Natural language question, symbol name, or file path to explore"));
        props.put("maxFiles", propWithDefault("integer", "Max files to include", 12));
        props.put("projectPath", prop("string", "Project root directory (optional)"));
        schema.put("properties", props);
        schema.put("required", Arrays.asList("query"));
        return new ToolDefinition("codegraph_explore",
            "Primary tool. Ask natural language questions or explore symbols/files. " +
            "Returns grouped source code with line numbers, call paths, and impact radius.",
            schema);
    }

    private ToolCallResult handleExplore(Map<String, Object> args) throws SQLException {
        String query = requireArg(args, "query");
        long startTime = System.currentTimeMillis();
        
        logger.info("[codegraph_explore] 开始处理查询: query=\"{}\"", query);

        // Step 1: 自适应输出预算
        int fileCount = countIndexedFiles();
        ExploreOutputBudget budget = ExploreOutputBudget.getForFileCount(fileCount);
        int maxFiles = clamp(intArg(args, "maxFiles", budget.defaultMaxFiles), 1, 20);
        logger.info("[codegraph_explore] Step 1 - 自适应预算: fileCount={}, maxFiles={}, maxOutputChars={}", 
            fileCount, maxFiles, budget.maxOutputChars);

     
        ContextBuilder ctxBuilder = new ContextBuilder(queries);
        ContextBuilder.FindOptions opts = new ContextBuilder.FindOptions();
        opts.searchLimit = 8;
        opts.traversalDepth = 3;
        opts.maxNodes = 200;
        //Step 2: 混合搜索获取子图 建立初始子图先分拆关键字 再按照QualifiedName和name进行搜索后按照相关性排序  再按照前缀排序 最后深度和广度搜索关系
        com.codegraph.context.Subgraph subgraph = ctxBuilder.findRelevantContext(query, opts);
        if (subgraph.nodes.isEmpty()) {
            logger.info("[codegraph_explore] Step 2 - 未找到相关代码: query=\"{}\"", query);
            return text("No relevant code found for \"" + query + "\"");
        }
        logger.info("[codegraph_explore] Step 2 - 混合搜索完成: nodes={}, edges={}, roots={}", 
            subgraph.nodes.size(), subgraph.edges.size(), subgraph.roots.size());

        // Step 3: 图感知粘合 — 注入 entry 节点的 callers/callees（同文件内）填补同文件内的"空隙"（细粒度补全）
        Set<String> glueNodeIds = new LinkedHashSet<>();
        Set<String> subgraphFiles = new LinkedHashSet<>();
        for (Node n : subgraph.nodes.values()) {
            if (n.getFilePath() != null) subgraphFiles.add(n.getFilePath());
        }
        final int GLUE_NODE_CAP = 60;
        for (String rootId : subgraph.roots) {
            if (glueNodeIds.size() >= GLUE_NODE_CAP) break;
            List<CallerInfo> callers = traverser.getCallers(rootId, 1);
            List<CalleeInfo> callees = traverser.getCallees(rootId, 1);
            for (CallerInfo ci : callers) {
                if (ci.node == null) continue;
                if (glueNodeIds.size() >= GLUE_NODE_CAP) break;
                if (subgraph.nodes.containsKey(ci.node.getId())) continue;
                if (!subgraphFiles.contains(ci.node.getFilePath())) continue;
                subgraph.addNode(ci.node);
                glueNodeIds.add(ci.node.getId());
            }
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

        // Step 4: 命名符号播种 — 从 query 提取 token，解析并注入子图 - 充用户命名的其他符号（跨文件补全）
        Set<String> namedSeedIds = new LinkedHashSet<>();
        List<String> tokens = ctxBuilder.extractSymbols(query);
        for (String token : tokens) {
            if (namedSeedIds.size() >= 40) break;
            try {
                List<Node> byQName = queries.getNodesByQualifiedName(token);
                if (byQName.isEmpty()) byQName = queries.getNodesByName(token);
                if (byQName.isEmpty()) byQName = queries.searchNodes(token);

                for (Node n : byQName) {
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

        // Step 5: 节点按文件分组 + 评分
        Map<String, FileGroup> fileGroups = new LinkedHashMap<>();
        Set<String> entryNodeIds = new LinkedHashSet<>();
        for (String id : subgraph.roots) entryNodeIds.add(id);
        entryNodeIds.addAll(namedSeedIds);

        // 构建"直接连接到 entry"的节点集合
        Set<String> connectedToEntry = new HashSet<>();
        for (Edge e : subgraph.edges) {
            if (entryNodeIds.contains(e.getSource())) connectedToEntry.add(e.getTarget());
            if (entryNodeIds.contains(e.getTarget())) connectedToEntry.add(e.getSource());
        }

        for (Node n : subgraph.nodes.values()) {
            if (n.getKind() == NodeKind.IMPORT || n.getKind() == NodeKind.EXPORT) continue;
            if (isConfigLeafNode(n)) continue;

            FileGroup group = fileGroups.computeIfAbsent(
                n.getFilePath() != null ? n.getFilePath() : "", k -> new FileGroup());
            group.nodes.add(n);

            // 评分
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

        // 过滤低分文件（只保留 score >= 3，即有 entry 或直接连接 entry）
        List<Map.Entry<String, FileGroup>> relevantFiles = new ArrayList<>();
        for (Map.Entry<String, FileGroup> e : fileGroups.entrySet()) {
            if (e.getValue().score >= 3) relevantFiles.add(e);
        }

        // 测试文件过滤
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

        // Step 6: RWR 图相关性
        Map<String, Double> nodeRwr = new GraphRelevanceComputer().compute(
            subgraph.nodes.keySet(), subgraph.edges, entryNodeIds);
        Map<String, Double> fileGraphScore = new HashMap<>();
        double maxGraph = 0;
        for (Node n : subgraph.nodes.values()) {
            double val = fileGraphScore.getOrDefault(n.getFilePath(), 0.0) + nodeRwr.getOrDefault(n.getId(), 0.0);
            fileGraphScore.put(n.getFilePath(), val);
            if (val > maxGraph) maxGraph = val;
        }

        // 查询词命中统计
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

        // Central files：图分数 > 0 且文本命中 >= 1 的文件，取前 2 个
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

        // Entry files：定义了 entry 节点的文件
        Set<String> entryFiles = new LinkedHashSet<>();
        for (String id : entryNodeIds) {
            Node n = subgraph.nodes.get(id);
            if (n != null && n.getFilePath() != null) entryFiles.add(n.getFilePath());
        }

        // 相关性门控
        int beforeGateSize = relevantFiles.size();
        if (maxGraph > 0) {
            List<Map.Entry<String, FileGroup>> gated = new ArrayList<>();
            for (Map.Entry<String, FileGroup> e : relevantFiles) {
                String fp = e.getKey();
                double gs = fileGraphScore.getOrDefault(fp, 0.0);
                if (gs >= maxGraph * 0.06 || centralFiles.contains(fp) ||
                    entryFiles.contains(fp) || fileTermHits.getOrDefault(fp, 0) >= 2) {
                    gated.add(e);
                }
            }
            if (gated.size() >= 2) relevantFiles = gated;
        }
        logger.info("[codegraph_explore] Step 6 - RWR图相关性完成: centralFiles={}, entryFiles={}, 门控后文件数={}, maxGraph={}", 
            centralFiles.size(), entryFiles.size(), relevantFiles.size(), String.format("%.4f", maxGraph));

        // Step 7: 文件排序
        // Tier 1: agent 命名的文件
        // Tier 2: corroborate（entry/central + >= 2 terms）
        // Tier 3: 图相关性
        // Tier 4: term 命中数
        // Tier 5: 低价值文件降权
        // Tier 6: 生成文件降权
        final double maxGraphFinal = maxGraph;
        relevantFiles.sort((a, b) -> {
            String ap = a.getKey(), bp = b.getKey();
            double ags = fileGraphScore.getOrDefault(ap, 0.0), bgs = fileGraphScore.getOrDefault(bp, 0.0);
            int ah = fileTermHits.getOrDefault(ap, 0), bh = fileTermHits.getOrDefault(bp, 0);
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
            if (Math.abs(ags - bgs) > maxGraphFinal * 0.01) return Double.compare(bgs, ags);
            if (ah != bh) return bh - ah;
            boolean al = isLowValue(ap), bl = isLowValue(bp);
            if (al != bl) return al ? 1 : -1;
            boolean ag = isGeneratedFile(ap), bg = isGeneratedFile(bp);
            if (ag != bg) return ag ? 1 : -1;
            if (a.getValue().score != b.getValue().score) return b.getValue().score - a.getValue().score;
            return b.getValue().nodes.size() - a.getValue().nodes.size();
        });
        logger.info("[codegraph_explore] Step 7 - 文件排序完成: 待输出文件数={}", relevantFiles.size());

        // Step 8: 构建输出段落
        List<String> lines = new ArrayList<>();
        lines.add("**Exploration: " + escape(query) + "**");
        lines.add("");
        lines.add("Found " + subgraph.nodes.size() + " symbols across " + fileGroups.size() + " files.");
        lines.add("");

        // 爆炸半径段落
        String blastRadius = new BlastRadiusBuilder().build(subgraph, queries, traverser);
        if (!blastRadius.isEmpty()) lines.add(blastRadius);

        // 关系图段落
        if (budget.includeRelationships) {
            List<Edge> sigEdges = new ArrayList<>();
            for (Edge e : subgraph.edges) {
                if (e.getKind() != EdgeKind.CONTAINS) sigEdges.add(e);
            }
            if (!sigEdges.isEmpty()) {
                lines.add("**Relationships**");
                lines.add("");
                Map<String, List<String[]>> byKind = new LinkedHashMap<>();
                for (Edge e : sigEdges) {
                    Node src = subgraph.nodes.get(e.getSource());
                    Node tgt = subgraph.nodes.get(e.getTarget());
                    if (src == null || tgt == null) continue;
                    byKind.computeIfAbsent(e.getKind().getValue(), k -> new ArrayList<>())
                          .add(new String[]{src.getName(), tgt.getName()});
                }
                for (Map.Entry<String, List<String[]>> ke : byKind.entrySet()) {
                    int cap = budget.maxEdgesPerRelationshipKind;
                    List<String[]> shown = ke.getValue().subList(0, Math.min(cap, ke.getValue().size()));
                    lines.add("**" + ke.getKey() + ":**");
                    for (String[] e : shown) lines.add("- " + e[0] + " \u2192 " + e[1]);
                    if (ke.getValue().size() > cap) lines.add("- ... and " + (ke.getValue().size() - cap) + " more");
                    lines.add("");
                }
            }
        }

        // 源码段落
        lines.add("**Source Code**");
        lines.add("");
        lines.add("> The code below is the **verbatim, current on-disk source** of these files \u2014 re-read from disk on this call and line-numbered, byte-for-byte identical to what the Read tool returns.");
        lines.add("");

        // Flow spine
        ContextBuilder.FlowInfo flow = ctxBuilder.buildFlowFromNamedSymbols(query);
        Set<String> pathNodeIds = flow.pathNodeIds;
        Set<String> namedNodeIds = flow.namedNodeIds;
        Set<String> uniqueNamedIds = flow.uniqueNamedNodeIds;

        int totalChars = joinStrings(lines).length();
        int filesIncluded = 0;
        boolean anyTrimmed = false;

        // 多态同构缓存
        Map<String, Boolean> siblingSuperCache = new HashMap<>();
        Map<String, Boolean> superManyCache = new HashMap<>();
        final int MIN_SIBLINGS = 3;

        for (Map.Entry<String, FileGroup> entry : relevantFiles) {
            if (filesIncluded >= maxFiles) break;

            String filePath = entry.getKey();
            FileGroup group = entry.getValue();

            // 检查文件是否必要（past 90% budget 停止非必要文件）
            boolean fileNecessary = group.nodes.stream().anyMatch(n ->
                entryNodeIds.contains(n.getId()) || pathNodeIds.contains(n.getId()) || namedNodeIds.contains(n.getId()));
            if (!fileNecessary && totalChars > budget.maxOutputChars * 0.9) continue;

            // 检查文件是否存在
            Path absPath = Paths.get(filePath);
            if (!absPath.isAbsolute()) absPath = Paths.get(projectPath, filePath);
            if (!Files.exists(absPath)) continue;

            List<String> fileLines;
            try {
                fileLines = Files.readAllLines(absPath);
            } catch (IOException e) {
                continue;
            }

            String lang = detectLanguage(filePath);

            // ===== 渲染策略 =====
            // 策略 A: 多态同构骨架化
            boolean hasSpineNode = group.nodes.stream().anyMatch(n -> pathNodeIds.contains(n.getId()));
            boolean isPolySib = !hasSpineNode && isPolymorphicSibling(group.nodes, queries, siblingSuperCache, MIN_SIBLINGS);
            boolean spareNamed = group.nodes.stream().anyMatch(n -> uniqueNamedIds.contains(n.getId()));
            boolean definesPolySuper = definesPolymorphicSupertype(group.nodes, queries, superManyCache, MIN_SIBLINGS);
            boolean spared = spareNamed && !definesPolySuper;

            // 检测神主文件（spine + 内容过大）
            int namedBodyChars = 0;
            for (Node n : group.nodes) {
                if (isCallable(n.getKind().name()) && (pathNodeIds.contains(n.getId()) || namedNodeIds.contains(n.getId()))) {
                    if (n.getStartLine() > 0 && n.getEndLine() > n.getStartLine()) {
                        namedBodyChars += String.join("\n", fileLines.subList(
                            Math.max(0, n.getStartLine() - 1), Math.min(fileLines.size(), n.getEndLine()))).length();
                    }
                }
            }
            boolean onSpineGodFile = hasSpineNode && namedBodyChars > budget.maxCharsPerFile
                && group.nodes.stream().anyMatch(n -> isCallable(n.getKind().name()) && uniqueNamedIds.contains(n.getId()) && !pathNodeIds.contains(n.getId()));

            if ((onSpineGodFile || (!hasSpineNode && isPolySib && !spared))) {
                // 骨架化渲染
                String skeleton = renderSkeleton(group.nodes, fileLines, pathNodeIds, namedNodeIds, uniqueNamedIds, budget, lang);
                if (!skeleton.isEmpty()) {
                    String tag = !pathNodeIds.isEmpty() && !namedNodeIds.isEmpty()
                        ? "focused (the methods you named in full, the rest as signatures \u2014 codegraph_explore a signature for its body; do NOT Read)"
                        : "skeleton (signatures only \u2014 codegraph_explore a name for its full body; do NOT Read)";
                    lines.add(fileSectionHeader(filePath, tag));
                    lines.add("");
                    lines.add("```" + lang);
                    lines.add(skeleton);
                    lines.add("```");
                    lines.add("");
                    totalChars += skeleton.length() + 120;
                    filesIncluded++;
                    continue;
                }
            }

            // 策略 B: 小文件整文件输出
            boolean isCentral = centralFiles.contains(filePath);
            int WHOLE_FILE_MAX_LINES = isCentral ? 280 : 220;
            int WHOLE_FILE_MAX_CHARS = isCentral
                ? Math.min(Math.max(0, budget.maxOutputChars - totalChars - 200), (int)(budget.maxCharsPerFile * 1.5))
                : budget.maxCharsPerFile * 3;

            if (fileLines.size() <= WHOLE_FILE_MAX_LINES && fileLines.size() * 80 <= WHOLE_FILE_MAX_CHARS) {
                if (!fileNecessary && totalChars + fileLines.size() * 80 + 200 > budget.maxOutputChars) {
                    anyTrimmed = true;
                    continue;
                }
                String body = String.join("\n", fileLines);
                String numbered = numberSourceLines(body, 1);
                String names = extractSymbolNames(group.nodes, budget.maxSymbolsInFileHeader);
                lines.add(fileSectionHeader(filePath, names));
                lines.add("");
                lines.add("```" + lang);
                lines.add(numbered);
                lines.add("```");
                lines.add("");
                totalChars += numbered.length() + 200;
                filesIncluded++;
                continue;
            }

            // 策略 C: 聚类分组
            String clusters = renderClusters(group.nodes, fileLines, subgraph, entryNodeIds, glueNodeIds, connectedToEntry, pathNodeIds, namedNodeIds, budget, lang, filePath);
            if (!clusters.isEmpty()) {
                lines.add(clusters);
                totalChars += clusters.length();
                filesIncluded++;
            }
        }

        // 完整性信号
        if (budget.includeCompletenessSignal) {
            if (anyTrimmed || filesIncluded < relevantFiles.size()) {
                lines.add("*Some files were trimmed or omitted due to output budget limits.*");
                lines.add("");
            }
        }

        // 预算说明
        if (budget.includeBudgetNote) {
            lines.add("*Explore output budget: " + totalChars + "/" + budget.maxOutputChars + " chars, " + filesIncluded + "/" + maxFiles + " files.*");
        }

        String resultText = joinStrings(lines);
        writeExploreLog(query, resultText);
        
        long elapsed = System.currentTimeMillis() - startTime;
        logger.info("[codegraph_explore] 处理完成: query=\"{}\", 输出字符数={}, 文件数={}, 耗时={}ms", 
            query, resultText.length(), filesIncluded, elapsed);
        
        return text(resultText);
    }

    // ============ handleExplore 辅助方法 ============

    private void writeExploreLog(String query, String content) {
        try {
            Path logsDir = Paths.get(projectPath, ".codegraph", "logs");
            if (!Files.exists(logsDir)) {
                Files.createDirectories(logsDir);
            }

            String timestamp = LocalDateTime.now().format(EXPLORE_LOG_DATE_FORMATTER);
            String safeQuery = query.replaceAll("[^a-zA-Z0-9_\\-]", "_")
                .substring(0, Math.min(query.length(), 50));
            String fileName = "explore_" + timestamp + "_" + safeQuery + ".md";
            Path logFile = logsDir.resolve(fileName);

            StringBuilder sb = new StringBuilder();
            sb.append("# CodeGraph Explore Result\n\n");
            sb.append("## Metadata\n\n");
            sb.append("| Field | Value |\n");
            sb.append("|-------|-------|\n");
            sb.append("| **Tool** | codegraph_explore |\n");
            sb.append("| **Query** | ").append(escape(query)).append(" |\n");
            sb.append("| **Timestamp** | ").append(LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append(" |\n");
            sb.append("\n");
            sb.append("## Content\n\n");
            sb.append(content);

            Files.write(logFile, sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));

            rotateExploreLogs(logsDir);
        } catch (IOException e) {
            logger.warn("Failed to write explore log: {}", e.getMessage());
        }
    }

    private static void rotateExploreLogs(Path logsDir) {
        try (Stream<Path> stream = Files.list(logsDir)) {
            List<Path> logFiles = stream
                .filter(p -> p.getFileName().toString().startsWith("explore_"))
                .filter(Files::isRegularFile)
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .collect(Collectors.toList());

            while (logFiles.size() > EXPLORE_LOG_MAX_FILES) {
                Path oldest = logFiles.remove(0);
                try {
                    Files.delete(oldest);
                } catch (IOException e) {
                    logger.warn("Failed to delete old explore log: {}", oldest);
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to rotate explore logs: {}", e.getMessage());
        }
    }

    private static int clamp(int val, int lo, int hi) {
        return Math.max(lo, Math.min(hi, val));
    }

    /**
     * 转义 Markdown 特殊字符，防止用户输入的查询字符串被误解析为 Markdown 格式。
     *
     * <p>转义规则：
     * <ul>
     *   <li>反引号 `` ` `` → `` \` ``</li>
     *   <li>星号 `*` → `\*`</li>
     * </ul>
     *
     * @param s 待转义的字符串
     * @return 转义后的字符串，null 输入返回空字符串
     */
    private static String escape(String s) {
        return s != null ? s.replace("`", "\\`").replace("*", "\\*") : "";
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

    private static boolean isCallable(String kind) {
        return "method".equalsIgnoreCase(kind) || "function".equalsIgnoreCase(kind)
            || "component".equalsIgnoreCase(kind) || "constructor".equalsIgnoreCase(kind);
    }

    private static String fileSectionHeader(String filePath, String suffix) {
        String fileName = filePath.contains("/") ? filePath.substring(filePath.lastIndexOf('/') + 1) : filePath;
        return "**" + fileName + "** — " + suffix + " — `" + filePath + "`";
    }

    private static String numberSourceLines(String body, int startLine) {
        String[] rawLines = body.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rawLines.length; i++) {
            sb.append(startLine + i).append("\t").append(rawLines[i]).append("\n");
        }
        return sb.toString();
    }

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

    private static boolean mentionsTests(String query) {
        if (query == null) return false;
        String lc = query.toLowerCase();
        return lc.contains("test") || lc.contains("testing")
            || lc.contains("spec") || lc.contains("verify");
    }

    /**
     * 判断节点是否为配置叶子节点。配置叶子节点通常是 Spring/MicroProfile 中通过
     * @Value 注入的配置常量，这类节点在代码探索时应被过滤，不参与评分。
     *
     * <p>判断条件：
     * <ul>
     *   <li>节点类型为 CONSTANT 或 FIELD</li>
     *   <li>名称以 "${" 开头（如 Spring 占位符）</li>
     *   <li>限定名包含配置文件后缀（.properties, .yml, .yaml）</li>
     * </ul>
     *
     * @param n 待检测的节点
     * @return true 表示是配置叶子节点，应在探索时过滤
     */
    private static boolean isConfigLeafNode(Node n) {
        // Spring/MicroProfile 配置节点（@Value 绑定的常量）
        if (n.getKind() == NodeKind.CONSTANT || n.getKind() == NodeKind.FIELD) {
            String name = n.getName() != null ? n.getName().toLowerCase() : "";
            String qname = n.getQualifiedName() != null ? n.getQualifiedName().toLowerCase() : "";
            // 检查是否为配置相关的节点
            return name.startsWith("${") || qname.contains(".properties")
                || qname.contains(".yml") || qname.contains(".yaml");
        }
        return false;
    }

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

    private static boolean isGeneratedFile(String path) {
        if (path == null) return false;
        String lp = path.toLowerCase();
        return lp.contains(".pb.") || lp.endsWith(".pulsar.go")
            || lp.endsWith("_mocks.go") || lp.endsWith("_mock.go")
            || lp.contains("/generated/") || lp.contains("/generated_src/")
            || lp.endsWith("_generated.java") || lp.endsWith("_generated.kt")
            || lp.contains("generated_") && (lp.endsWith(".java") || lp.endsWith(".kt"));
    }

    private boolean isPolymorphicSibling(List<Node> nodes, QueryBuilder q, Map<String, Boolean> cache, int minSiblings) {
        for (Node n : nodes) {
            try {
                List<Edge> outgoing = q.getOutgoingEdges(n.getId());
                for (Edge e : outgoing) {
                    if (e.getKind() != EdgeKind.EXTENDS && e.getKind() != EdgeKind.IMPLEMENTS) continue;
                    String target = e.getTarget();
                    Boolean cached = cache.get(target);
                    if (cached != null) { if (cached) return true; continue; }
                    try {
                        List<Edge> incoming = q.getIncomingEdges(target);
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

    private boolean definesPolymorphicSupertype(List<Node> nodes, QueryBuilder q, Map<String, Boolean> cache, int minSiblings) {
        for (Node n : nodes) {
            String kn = n.getKind().name().toLowerCase();
            if (!kn.equals("class") && !kn.equals("interface") && !kn.equals("struct")
                && !kn.equals("trait") && !kn.equals("protocol") && !kn.equals("type_alias")
                && !kn.equals("enum")) continue;
            Boolean cached = cache.get(n.getId());
            if (cached != null) return cached;
            try {
                List<Edge> incoming = q.getIncomingEdges(n.getId());
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

    private String renderSkeleton(Collection<Node> nodes, List<String> fileLines,
            Set<String> pathNodeIds, Set<String> namedNodeIds, Set<String> uniqueNamedIds,
            ExploreOutputBudget budget, String lang) {

        List<Node> syms = new ArrayList<>();
        for (Node n : nodes) {
            if ((n.getKind() == NodeKind.IMPORT || n.getKind() == NodeKind.EXPORT) && n.getStartLine() <= 0) continue;
            syms.add(n);
        }
        syms.sort(Comparator.comparingInt(n -> n.getStartLine() > 0 ? n.getStartLine() : Integer.MAX_VALUE));

        // 选择哪些符号显示 body
        Set<String> bodyIds = new LinkedHashSet<>();
        int bodyChars = 0;
        int bodyCap = (int)(budget.maxCharsPerFile * 1.5);

        final Set<String> CALLABLE_KINDS = new HashSet<>(Arrays.asList(
            "method", "function", "component", "constructor", "property",
            "METHOD", "FUNCTION", "COMPONENT", "CONSTRUCTOR", "PROPERTY"));

        for (Node n : syms) {
            String kn = n.getKind().name().toLowerCase();
            int priority = !CALLABLE_KINDS.contains(kn) ? 99
                : pathNodeIds.contains(n.getId()) ? 0
                : uniqueNamedIds.contains(n.getId()) ? 1 : 99;
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

        // 渲染
        List<String> skel = new ArrayList<>();
        int coveredUntil = 0;
        int sigCount = 0;
        int sigDropped = 0;
        int SIG_MAX = Math.max(12, budget.maxSymbolsInFileHeader * 2);

        for (Node n : syms) {
            if (n.getStartLine() > 0 && coveredUntil > 0 && n.getStartLine() <= coveredUntil) continue;

            if (bodyIds.contains(n.getId())) {
                int start = Math.max(0, n.getStartLine() - 1);
                int end = Math.min(fileLines.size(), n.getEndLine());
                String body = String.join("\n", fileLines.subList(start, end));
                skel.add(numberSourceLines(body, n.getStartLine()));
                coveredUntil = n.getEndLine();
            } else {
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

    private String renderClusters(Collection<Node> nodes, List<String> fileLines,
            com.codegraph.context.Subgraph subgraph, Set<String> entryNodeIds, Set<String> glueNodeIds,
            Set<String> connectedToEntry, Set<String> pathNodeIds, Set<String> namedNodeIds,
            ExploreOutputBudget budget, String lang, String filePath) {

        final Set<String> ENVELOPE_KINDS = new HashSet<>(Arrays.asList(
            "file", "module", "class", "struct", "interface", "enum",
            "namespace", "protocol", "trait", "component",
            "FILE", "MODULE", "CLASS", "STRUCT", "INTERFACE", "ENUM",
            "NAMESPACE", "PROTOCOL", "TRAIT", "COMPONENT"));

        // 构建 range 列表
        List<NodeRange> ranges = new ArrayList<>();
        for (Node n : nodes) {
            if (n.getStartLine() <= 0 || n.getEndLine() <= n.getStartLine()) continue;
            if (ENVELOPE_KINDS.contains(n.getKind().name())
                && (n.getEndLine() - n.getStartLine() + 1) > fileLines.size() * 0.5) continue;

            int importance = 1;
            if (entryNodeIds.contains(n.getId())) importance = 10;
            else if (namedNodeIds.contains(n.getId())) importance = 9;
            else if (glueNodeIds.contains(n.getId())) importance = 6;
            else if (connectedToEntry.contains(n.getId())) importance = 3;

            ranges.add(new NodeRange(n, importance, pathNodeIds.contains(n.getId())));
        }

        if (ranges.isEmpty()) return "";

        // 按行排序
        ranges.sort(Comparator.comparingInt(r -> r.startLine));

        // 合并相邻/重叠范围
        List<NodeRange> merged = new ArrayList<>();
        for (NodeRange r : ranges) {
            if (!merged.isEmpty() && r.startLine - merged.get(merged.size() - 1).endLine <= budget.gapThreshold) {
                NodeRange last = merged.get(merged.size() - 1);
                last.endLine = Math.max(last.endLine, r.endLine);
                last.importance = Math.max(last.importance, r.importance);
                if (r.isSpine) last.isSpine = true;
            } else {
                merged.add(r);
            }
        }

        // 按重要性选择要展示的范围
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

        // 渲染
        List<String> lines = new ArrayList<>();
        List<Node> allRangeNodes = new ArrayList<>();
        for (NodeRange r : merged) allRangeNodes.add(r.node);
        String names = extractSymbolNames(allRangeNodes, budget.maxSymbolsInFileHeader);
        lines.add(fileSectionHeader(filePath, "clustered"));
        lines.add("");

        int shown = 0;
        int maxClusters = budget.maxCharsPerFile / 40; // 每约 40 字符一个 cluster
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

    // 内部类
    private static class FileGroup {
        List<Node> nodes = new ArrayList<>();
        int score = 0;
    }

    private static class NodeRange {
        Node node;
        int startLine;
        int endLine;
        int importance;
        boolean isSpine;
        NodeRange(Node n, int importance, boolean isSpine) {
            this.node = n; this.startLine = n.getStartLine(); this.endLine = n.getEndLine();
            this.importance = importance; this.isSpine = isSpine;
        }
    }

    // ============ Tool 7: codegraph_status ============

    private ToolDefinition statusTool() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
        return new ToolDefinition("codegraph_status",
            "Index health check — file/node/edge counts.",
            schema);
    }

    private ToolCallResult handleStatus(Map<String, Object> args) throws SQLException {
        long nodeCount = queries.getNodeCount();
        long edgeCount = queries.getEdgeCount();
        int fileCount = countIndexedFiles();

        // 按类型统计节点
        Map<String, Integer> kindCounts = new LinkedHashMap<>();
        for (Node n : queries.getAllNodes()) {
            kindCounts.merge(n.getKind().name(), 1, Integer::sum);
        }

        // 按类型统计边
        Map<String, Integer> edgeKindCounts = new LinkedHashMap<>();
        for (com.codegraph.core.Edge e : queries.getAllEdges()) {
            edgeKindCounts.merge(e.getKind().getValue(), 1, Integer::sum);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== CodeGraph Status ===\n\n");
        sb.append(String.format("Database:  %s/.codegraph/codegraph4j.db\n", projectPath));
        sb.append(String.format("Files:     %d\n", fileCount));
        sb.append(String.format("Nodes:     %d\n", nodeCount));
        sb.append(String.format("Edges:     %d\n\n", edgeCount));

        sb.append("Nodes by kind:\n");
        for (Map.Entry<String, Integer> e : kindCounts.entrySet()) {
            sb.append(String.format("  %-14s %d\n", e.getKey(), e.getValue()));
        }

        sb.append("\nEdges by kind:\n");
        for (Map.Entry<String, Integer> e : edgeKindCounts.entrySet()) {
            sb.append(String.format("  %-14s %d\n", e.getKey(), e.getValue()));
        }

        return text(sb.toString());
    }

    // ============ Tool 8: codegraph_files ============

    private ToolDefinition filesTool() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", prop("string", "Directory path to list"));
        props.put("format", prop("string", "Output format: tree, flat, grouped"));
        schema.put("properties", props);
        return new ToolDefinition("codegraph_files",
            "List indexed files in the project with symbol counts.",
            schema);
    }

    private ToolCallResult handleFiles(Map<String, Object> args) throws SQLException {
        String path = strArg(args, "path", "");
        List<String> allFiles = queries.getAllFiles();

        // 按目录分组
        Map<String, List<String>> byDir = new TreeMap<>();
        for (String f : allFiles) {
            if (!path.isEmpty() && !f.startsWith(path)) continue;
            String dir = getDirectory(f);
            byDir.computeIfAbsent(dir, k -> new ArrayList<>()).add(f);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Indexed Files ===\n\n");
        sb.append("Total: ").append(allFiles.size()).append(" files\n\n");

        for (Map.Entry<String, List<String>> entry : byDir.entrySet()) {
            sb.append(entry.getKey()).append("/\n");
            for (String f : entry.getValue()) {
                String fileName = f.substring(f.lastIndexOf('/') + 1);
                int symbolCount = queries.getNodesInFile(f).size();
                sb.append(String.format("  %s (%d symbols)\n", fileName, symbolCount));
            }
            sb.append("\n");
        }

        return text(sb.toString());
    }

    // ============ 辅助方法 ============

    private Node findSymbol(String symbol, String file) throws SQLException {
        List<Node> candidates;

        // 尝试按限定名查找
        candidates = queries.getNodesByQualifiedName(symbol);
        if (!candidates.isEmpty()) return filterByFile(candidates, file);

        // 按名称查找
        candidates = queries.getNodesByName(symbol);
        if (!candidates.isEmpty()) return filterByFile(candidates, file);

        // 按小写名查找
        candidates = queries.getNodesByLowerName(symbol.toLowerCase());
        if (!candidates.isEmpty()) return filterByFile(candidates, file);

        return null;
    }

    private Node filterByFile(List<Node> candidates, String file) {
        if (candidates.size() == 1) return candidates.get(0);
        if (file != null) {
            for (Node n : candidates) {
                if (n.getFilePath() != null && n.getFilePath().contains(file)) {
                    return n;
                }
            }
        }
        return candidates.get(0); // 返回第一个
    }

    private int countIndexedFiles() {
        try { return queries.getAllFiles().size(); }
        catch (SQLException e) { return 0; }
    }

    private static boolean isEnabled(String tool) {
        return DEFAULT_MCP_TOOLS.contains(tool);
    }

    // ---- 参数解析 ----

    @SuppressWarnings("unchecked")
    private String requireArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) throw new IllegalArgumentException("Missing required argument: " + key);
        return val.toString();
    }

    private String strArg(Map<String, Object> args, String key, String defaultValue) {
        Object val = args.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    private int intArg(Map<String, Object> args, String key, int defaultValue) {
        Object val = args.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Number) return ((Number) val).intValue();
        try { return Integer.parseInt(val.toString()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private boolean boolArg(Map<String, Object> args, String key, boolean defaultValue) {
        Object val = args.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Boolean) return (Boolean) val;
        return Boolean.parseBoolean(val.toString());
    }

    // ---- 响应构建 ----

    private static ToolCallResult text(String text) {
        ToolCallResult result = new ToolCallResult();
        result.content.add(new ContentItem(text));
        return result;
    }

    private static ToolCallResult error(String message) {
        ToolCallResult result = new ToolCallResult();
        result.content.add(new ContentItem(message, true));
        result.isError = true;
        return result;
    }

    // ---- JSON Schema 工具方法 ----

    private static Map<String, Object> prop(String type, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("description", description);
        return p;
    }

    private static Map<String, Object> propWithDefault(String type, String description, Object defaultValue) {
        Map<String, Object> p = prop(type, description);
        p.put("default", defaultValue);
        return p;
    }

    private static Map<String, Object> propWithEnum(String type, String description, String... enumValues) {
        Map<String, Object> p = prop(type, description);
        p.put("enum", Arrays.asList(enumValues));
        return p;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private static String getDirectory(String filePath) {
        if (filePath == null) return ".";
        int lastSep = filePath.lastIndexOf('/');
        return lastSep >= 0 ? filePath.substring(0, lastSep) : ".";
    }
}
