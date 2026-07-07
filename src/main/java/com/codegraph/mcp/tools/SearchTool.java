package com.codegraph.mcp.tools;

import com.codegraph.config.CodeGraphConfig;
import com.codegraph.core.Node;
import com.codegraph.core.types.NodeKind;
import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;
import com.codegraph.graph.GraphQueryManager;
import com.codegraph.graph.GraphTraverser;
import com.codegraph.mcp.MCPTransport.ToolCallResult;
import com.codegraph.mcp.MCPTransport.ToolDefinition;

import java.sql.SQLException;
import java.util.*;

/**
 * codegraph_search — 按名称搜索代码符号，返回位置信息（不含源码）。
 */
public class SearchTool extends BaseTool {

    public SearchTool(DatabaseConnection db, QueryBuilder queries,
                      GraphTraverser traverser, GraphQueryManager graphQueryMgr,
                      CodeGraphConfig config) {
        super(db, queries, traverser, graphQueryMgr, config);
    }

    @Override
    public String getName() {
        return "codegraph_search";
    }

    @Override
    public ToolDefinition getDefinition() {
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

    @Override
    public ToolCallResult execute(Map<String, Object> args) {
        try {
            String query = requireArg(args, "query");
            ToolCallResult validationError = validateInputArg(args, "query");
            if (validationError != null) return validationError;
            validationError = validatePathArg(args, "projectPath");
            if (validationError != null) return validationError;
            String kind = strArg(args, "kind", null);
            int limit = intArg(args, "limit", 10);

            List<Node> searchResults = queries.searchNodes(query);

            List<Node> results;
            if (kind != null && !kind.isEmpty()) {
                try {
                    NodeKind nodeKind = NodeKind.fromValue(kind.toLowerCase());
                    results = new ArrayList<>();
                    for (Node n : searchResults) {
                        if (n.getKind() == nodeKind) results.add(n);
                    }
                } catch (IllegalArgumentException e) {
                    results = searchResults;
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
                    i + 1, n.getKind().getValue(), n.getName(),
                    truncate(n.getId(), 12), n.getFilePath(), n.getStartLine()));
            }
            return text(sb.toString());
        } catch (SQLException e) {
            return error("Search failed: " + e.getMessage());
        }
    }
}
