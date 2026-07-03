package com.codegraph.mcp.tools;

import com.codegraph.config.CodeGraphConfig;
import com.codegraph.core.Node;
import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;
import com.codegraph.graph.GraphQueryManager;
import com.codegraph.graph.GraphTraverser;
import com.codegraph.graph.NodeEdgePair;
import com.codegraph.mcp.MCPTransport.ContentItem;
import com.codegraph.mcp.MCPTransport.ToolCallResult;
import com.codegraph.utils.StringUtils;

import java.sql.SQLException;
import java.util.*;

/**
 * Tool 抽象基类 — 提供所有 Tool 共享的依赖注入、参数解析、响应构建和辅助方法。
 */
public abstract class BaseTool implements Tool {

    protected final DatabaseConnection db;
    protected final QueryBuilder queries;
    protected final GraphTraverser traverser;
    protected final GraphQueryManager graphQueryMgr;
    protected final CodeGraphConfig config;

    protected BaseTool(DatabaseConnection db, QueryBuilder queries,
                       GraphTraverser traverser, GraphQueryManager graphQueryMgr,
                       CodeGraphConfig config) {
        this.db = db;
        this.queries = queries;
        this.traverser = traverser;
        this.graphQueryMgr = graphQueryMgr;
        this.config = config;
    }

    // ---- 参数解析 ----

    @SuppressWarnings("unchecked")
    protected String requireArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) throw new IllegalArgumentException("Missing required argument: " + key);
        return val.toString();
    }

    protected String strArg(Map<String, Object> args, String key, String defaultValue) {
        Object val = args.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    protected int intArg(Map<String, Object> args, String key, int defaultValue) {
        Object val = args.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Number) return ((Number) val).intValue();
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    protected boolean boolArg(Map<String, Object> args, String key, boolean defaultValue) {
        Object val = args.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Boolean) return (Boolean) val;
        return Boolean.parseBoolean(val.toString());
    }

    // ---- 响应构建 ----

    protected static ToolCallResult text(String text) {
        ToolCallResult result = new ToolCallResult();
        result.content.add(new ContentItem(text));
        return result;
    }

    public static ToolCallResult error(String message) {
        ToolCallResult result = new ToolCallResult();
        result.content.add(new ContentItem(message, true));
        result.isError = true;
        return result;
    }

    // ---- JSON Schema 辅助 ----

    protected static Map<String, Object> prop(String type, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("description", description);
        return p;
    }

    protected static Map<String, Object> propWithDefault(String type, String description, Object defaultValue) {
        Map<String, Object> p = prop(type, description);
        p.put("default", defaultValue);
        return p;
    }

    protected static Map<String, Object> propWithEnum(String type, String description, String... enumValues) {
        Map<String, Object> p = prop(type, description);
        p.put("enum", Arrays.asList(enumValues));
        return p;
    }

    // ---- 符号查找辅助 ----

    /**
     * 按符号名查找节点，支持 file 过滤器和三级降级搜索（QName → Name → LowerName）。
     */
    protected Node findSymbol(String symbol, String file) throws SQLException {
        List<Node> candidates;

        candidates = queries.getNodesByQualifiedName(symbol);
        if (!candidates.isEmpty()) return filterByFile(candidates, file);

        candidates = queries.getNodesByName(symbol);
        if (!candidates.isEmpty()) return filterByFile(candidates, file);

        candidates = queries.getNodesByLowerName(symbol.toLowerCase());
        if (!candidates.isEmpty()) return filterByFile(candidates, file);

        return null;
    }

    /**
     * 按 file 过滤器从候选列表中选取最佳匹配。
     */
    protected Node filterByFile(List<Node> candidates, String file) {
        if (candidates.size() == 1) return candidates.get(0);
        if (file != null) {
            for (Node n : candidates) {
                if (n.getFilePath() != null && n.getFilePath().contains(file)) {
                    return n;
                }
            }
        }
        return candidates.get(0);
    }

    // ---- 字符串工具 ----

    protected static String truncate(String s, int maxLen) {
        return StringUtils.truncate(s, maxLen);
    }

    protected static String getDirectory(String filePath) {
        if (filePath == null) return ".";
        int lastSep = filePath.lastIndexOf('/');
        return lastSep >= 0 ? filePath.substring(0, lastSep) : ".";
    }

    /**
     * 构建调用者/被调用者输出。CallersTool 和 CalleesTool 的共享格式化逻辑。
     */
    protected static String formatTraversalResult(String label, Node node,
                                                   List<? extends NodeEdgePair> list, int limit) {
        List<? extends NodeEdgePair> resultList = list;
        if (resultList.size() > limit) resultList = resultList.subList(0, limit);

        StringBuilder sb = new StringBuilder();
        sb.append(label).append(node.getName())
            .append(" (").append(node.getKind().getValue()).append("):\n\n");
        if (resultList.isEmpty()) {
            sb.append("No ").append(label.toLowerCase()).append(" found.\n");
        } else {
            for (NodeEdgePair c : resultList) {
                sb.append(String.format("- %s %s [%s] %s:%d\n",
                    c.node.getKind().getValue(), c.node.getName(),
                    truncate(c.node.getId(), 12),
                    c.node.getFilePath(), c.node.getStartLine()));
            }
        }
        return sb.toString();
    }
}
