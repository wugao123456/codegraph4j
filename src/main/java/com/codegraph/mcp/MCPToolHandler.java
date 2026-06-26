package com.codegraph.mcp;

import com.codegraph.core.Node;
import com.codegraph.core.types.EdgeKind;
import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;
import com.codegraph.graph.GraphQueryManager;
import com.codegraph.graph.GraphTraverser;
import com.codegraph.graph.GraphTraverser.*;
import com.codegraph.mcp.MCPTransport.*;
import com.codegraph.resolution.ResolutionContext;
import com.codegraph.resolution.frameworks.FrameworkRegistry;
import com.codegraph.resolution.frameworks.FrameworkResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.SQLException;
import java.util.*;

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

    private final String projectPath;
    private final DatabaseConnection db;
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

        Subgraph subgraph = traverser.getImpactRadius(node.getId(), depth);

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
        int maxFiles = intArg(args, "maxFiles", 12);

        StringBuilder sb = new StringBuilder();

        // 1. 尝试作为符号搜索
        List<Node> searchResults = queries.searchNodes(query);
        Set<String> seenFiles = new HashSet<>();

        if (!searchResults.isEmpty()) {
            sb.append("=== Explore results for \"").append(query).append("\" ===\n\n");
            sb.append("Found ").append(searchResults.size()).append(" symbol matches.\n\n");

            // 按文件分组
        Map<String, List<Node>> byFile = new LinkedHashMap<>();
        final int[] fileCount = {0};
        for (Node n : searchResults) {
            if (fileCount[0] >= maxFiles) break;
            String f = n.getFilePath();
            if (!seenFiles.contains(f)) {
                seenFiles.add(f);
                fileCount[0]++;
            }
            byFile.computeIfAbsent(f, k -> new ArrayList<>()).add(n);
        }

            for (Map.Entry<String, List<Node>> entry : byFile.entrySet()) {
                String filePath = entry.getKey();
                List<Node> nodes = entry.getValue();

                // 获取文件级上下文
                List<Node> allNodesInFile = queries.getNodesInFile(filePath);

                sb.append("--- ").append(filePath).append(" ---\n");
                sb.append("  ").append(allNodesInFile.size()).append(" symbols total, ")
                    .append(nodes.size()).append(" matches:\n");

                // 展示匹配到的符号及其上下文
                for (Node n : nodes) {
                    sb.append(String.format("  [%s] %s", n.getKind().name(), n.getName()));
                    if (n.getSignature() != null) {
                        sb.append(" ").append(n.getSignature());
                    }
                    sb.append(String.format(" (line %d)\n", n.getStartLine()));

                    // 获取该节点的上下文
                    GraphQueryManager.NodeContext ctx = graphManager.getContext(n.getId());
                    if (!ctx.children.isEmpty()) {
                        sb.append("    Children: ");
                        for (Node child : ctx.children) {
                            sb.append(child.getName()).append(", ");
                        }
                        sb.setLength(sb.length() - 2); // 去掉最后的逗号
                        sb.append("\n");
                    }
                }
                sb.append("\n");
            }
        } else {
            sb.append("No symbol matches found for \"").append(query).append("\".\n");
            sb.append("Try searching for a specific class, method, or file name.\n");
        }

        // 2. 附加状态信息
        sb.append("--- Status ---\n");
        long nodeCount = queries.getNodeCount();
        long edgeCount = queries.getEdgeCount();
        sb.append("Total indexed: ").append(nodeCount).append(" nodes, ")
            .append(edgeCount).append(" edges in ")
            .append(countIndexedFiles()).append(" files.\n");

        return text(sb.toString());
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
            edgeKindCounts.merge(e.getKind().name(), 1, Integer::sum);
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
