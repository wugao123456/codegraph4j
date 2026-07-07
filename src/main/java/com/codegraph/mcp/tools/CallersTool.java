package com.codegraph.mcp.tools;

import com.codegraph.config.CodeGraphConfig;
import com.codegraph.core.Node;
import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;
import com.codegraph.graph.GraphQueryManager;
import com.codegraph.graph.GraphTraverser;
import com.codegraph.graph.GraphTraverser.CallerInfo;
import com.codegraph.mcp.MCPTransport.ToolCallResult;
import com.codegraph.mcp.MCPTransport.ToolDefinition;

import java.sql.SQLException;
import java.util.*;

/**
 * codegraph_callers — 列出调用指定符号的函数。
 */
public class CallersTool extends BaseTool {

    public CallersTool(DatabaseConnection db, QueryBuilder queries,
                       GraphTraverser traverser, GraphQueryManager graphQueryMgr,
                       CodeGraphConfig config) {
        super(db, queries, traverser, graphQueryMgr, config);
    }

    @Override
    public String getName() {
        return "codegraph_callers";
    }

    @Override
    public ToolDefinition getDefinition() {
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

    @Override
    public ToolCallResult execute(Map<String, Object> args) {
        try {
            String symbol = requireArg(args, "symbol");
            ToolCallResult validationError = validateInputArg(args, "symbol");
            if (validationError != null) return validationError;
            String file = strArg(args, "file", null);
            validationError = validatePathArg(args, "file");
            if (validationError != null) return validationError;
            int limit = intArg(args, "limit", 20);

            Node node = findSymbol(symbol, file);
            if (node == null) return text("Symbol not found: " + symbol);

            List<CallerInfo> callerList = traverser.getCallers(node.getId(), 1);
            return text(formatTraversalResult("Callers of ", node, callerList, limit));
        } catch (SQLException e) {
            return error("Callers lookup failed: " + e.getMessage());
        }
    }
}
