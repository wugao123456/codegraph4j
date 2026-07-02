package com.codegraph.mcp.tools;

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
 * codegraph_node — 获取符号详情：位置、签名、调用关系追踪。
 */
public class NodeTool extends BaseTool {

    public NodeTool(DatabaseConnection db, QueryBuilder queries,
                    GraphTraverser traverser, GraphQueryManager graphQueryMgr,
                    String projectPath) {
        super(db, queries, traverser, graphQueryMgr, projectPath);
    }

    @Override
    public String getName() {
        return "codegraph_node";
    }

    @Override
    public ToolDefinition getDefinition() {
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

    @Override
    public ToolCallResult execute(Map<String, Object> args) {
        try {
            String symbol = strArg(args, "symbol", null);
            String file = strArg(args, "file", null);
            boolean includeCode = boolArg(args, "includeCode", false);

            if (symbol == null && file == null) return error("Must provide 'symbol' or 'file'");

            if (symbol == null) {
                List<Node> fileNodes = queries.getNodesInFile(file);
                StringBuilder sb = new StringBuilder();
                sb.append("Symbols in ").append(file).append(":\n\n");
                for (Node n : fileNodes) {
                    sb.append(String.format("  %s %s (line %d)\n",
                        n.getKind().getValue(), n.getName(), n.getStartLine()));
                }
                sb.append("\nTotal: ").append(fileNodes.size()).append(" symbols\n");
                return text(sb.toString());
            }

            Node node = findSymbol(symbol, file);
            if (node == null) return text("Symbol not found: " + symbol);

            GraphQueryManager.NodeContext ctx = graphQueryMgr.getContext(node.getId());

            StringBuilder sb = new StringBuilder();
            sb.append("=== Symbol Details ===\n\n");
            sb.append("Name:        ").append(node.getName()).append("\n");
            sb.append("Kind:        ").append(node.getKind().getValue()).append("\n");
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
        } catch (SQLException e) {
            return error("Node lookup failed: " + e.getMessage());
        }
    }
}
