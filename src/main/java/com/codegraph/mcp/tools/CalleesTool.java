package com.codegraph.mcp.tools;

import com.codegraph.config.CodeGraphConfig;
import com.codegraph.core.Node;
import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;
import com.codegraph.graph.GraphQueryManager;
import com.codegraph.graph.GraphTraverser;
import com.codegraph.graph.GraphTraverser.CalleeInfo;
import com.codegraph.mcp.MCPTransport.ToolCallResult;
import com.codegraph.mcp.MCPTransport.ToolDefinition;

import java.sql.SQLException;
import java.util.*;

/**
 * codegraph_callees — 列出指定符号调用的函数。
 */
public class CalleesTool extends BaseTool {

    public CalleesTool(DatabaseConnection db, QueryBuilder queries,
                       GraphTraverser traverser, GraphQueryManager graphQueryMgr,
                       CodeGraphConfig config) {
        super(db, queries, traverser, graphQueryMgr, config);
    }

    @Override
    public String getName() {
        return "codegraph_callees";
    }

    @Override
    public ToolDefinition getDefinition() {
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

    @Override
    public ToolCallResult execute(Map<String, Object> args) {
        try {
            String symbol = requireArg(args, "symbol");
            String file = strArg(args, "file", null);
            int limit = intArg(args, "limit", 20);

            Node node = findSymbol(symbol, file);
            if (node == null) return text("Symbol not found: " + symbol);

            List<CalleeInfo> calleeList = traverser.getCallees(node.getId(), 1);
            if (calleeList.size() > limit) calleeList = calleeList.subList(0, limit);

            StringBuilder sb = new StringBuilder();
            sb.append("Callees of ").append(node.getName())
                .append(" (").append(node.getKind().getValue()).append("):\n\n");
            if (calleeList.isEmpty()) {
                sb.append("No callees found.\n");
            } else {
                for (CalleeInfo c : calleeList) {
                    sb.append(String.format("- %s %s [%s] %s:%d\n",
                        c.node.getKind().getValue(), c.node.getName(),
                        truncate(c.node.getId(), 12),
                        c.node.getFilePath(), c.node.getStartLine()));
                }
            }
            return text(sb.toString());
        } catch (SQLException e) {
            return error("Callees lookup failed: " + e.getMessage());
        }
    }
}
