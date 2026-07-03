package com.codegraph.mcp.tools;

import com.codegraph.config.CodeGraphConfig;
import com.codegraph.core.Edge;
import com.codegraph.core.Node;
import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;
import com.codegraph.graph.GraphQueryManager;
import com.codegraph.graph.GraphTraverser;
import com.codegraph.mcp.MCPTransport.ToolCallResult;
import com.codegraph.mcp.MCPTransport.ToolDefinition;

import java.sql.SQLException;
import java.util.*;

/**
 * codegraph_status — 索引健康检查：文件/节点/边的计数统计。
 */
public class StatusTool extends BaseTool {

    public StatusTool(DatabaseConnection db, QueryBuilder queries,
                      GraphTraverser traverser, GraphQueryManager graphQueryMgr,
                      CodeGraphConfig config) {
        super(db, queries, traverser, graphQueryMgr, config);
    }

    @Override
    public String getName() {
        return "codegraph_status";
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
        return new ToolDefinition("codegraph_status",
            "Index health check — file/node/edge counts.",
            schema);
    }

    @Override
    public ToolCallResult execute(Map<String, Object> args) {
        try {
            long nodeCount = queries.getNodeCount();
            long edgeCount = queries.getEdgeCount();
            int fileCount = queries.getAllFiles().size();

            Map<String, Integer> kindCounts = new LinkedHashMap<>();
            for (Node n : queries.getAllNodes()) {
                kindCounts.merge(n.getKind().getValue(), 1, Integer::sum);
            }

            Map<String, Integer> edgeKindCounts = new LinkedHashMap<>();
            for (Edge e : queries.getAllEdges()) {
                edgeKindCounts.merge(e.getKind().getValue(), 1, Integer::sum);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== CodeGraph Status ===\n\n");
            sb.append(String.format("Database:  %s\n", config.getDbFile().getAbsolutePath()));
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
        } catch (SQLException e) {
            return error("Status check failed: " + e.getMessage());
        }
    }
}
