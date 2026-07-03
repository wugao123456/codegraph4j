package com.codegraph.mcp.tools;

import com.codegraph.config.CodeGraphConfig;
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
 * codegraph_impact — 爆炸半径分析，查找修改指定符号会影响哪些代码。
 */
public class ImpactTool extends BaseTool {

    public ImpactTool(DatabaseConnection db, QueryBuilder queries,
                      GraphTraverser traverser, GraphQueryManager graphQueryMgr,
                      CodeGraphConfig config) {
        super(db, queries, traverser, graphQueryMgr, config);
    }

    @Override
    public String getName() {
        return "codegraph_impact";
    }

    @Override
    public ToolDefinition getDefinition() {
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

    @Override
    public ToolCallResult execute(Map<String, Object> args) {
        try {
            String symbol = requireArg(args, "symbol");
            String file = strArg(args, "file", null);
            int depth = intArg(args, "depth", 2);

            Node node = findSymbol(symbol, file);
            if (node == null) return text("Symbol not found: " + symbol);

            GraphTraverser.Subgraph subgraph = traverser.getImpactRadius(node.getId(), depth);

            StringBuilder sb = new StringBuilder();
            sb.append("Impact radius of ").append(node.getName())
                .append(" (depth ").append(depth).append("):\n\n");
            sb.append(subgraph.nodes.size()).append(" nodes potentially affected:\n");

            Map<String, List<Node>> byFile = new LinkedHashMap<>();
            for (Node n : subgraph.nodes.values()) {
                byFile.computeIfAbsent(n.getFilePath(), k -> new ArrayList<>()).add(n);
            }
            for (Map.Entry<String, List<Node>> entry : byFile.entrySet()) {
                sb.append("\n  ").append(entry.getKey()).append("\n");
                for (Node n : entry.getValue()) {
                    sb.append(String.format("    %s %s (line %d)\n",
                        n.getKind().getValue(), n.getName(), n.getStartLine()));
                }
            }
            return text(sb.toString());
        } catch (SQLException e) {
            return error("Impact analysis failed: " + e.getMessage());
        }
    }
}
