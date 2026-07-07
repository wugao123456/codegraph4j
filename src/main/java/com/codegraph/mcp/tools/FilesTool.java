package com.codegraph.mcp.tools;

import com.codegraph.config.CodeGraphConfig;
import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;
import com.codegraph.graph.GraphQueryManager;
import com.codegraph.graph.GraphTraverser;
import com.codegraph.mcp.MCPTransport.ToolCallResult;
import com.codegraph.mcp.MCPTransport.ToolDefinition;

import java.sql.SQLException;
import java.util.*;

/**
 * codegraph_files — 列出项目中已索引的文件及符号数量。
 */
public class FilesTool extends BaseTool {

    public FilesTool(DatabaseConnection db, QueryBuilder queries,
                     GraphTraverser traverser, GraphQueryManager graphQueryMgr,
                     CodeGraphConfig config) {
        super(db, queries, traverser, graphQueryMgr, config);
    }

    @Override
    public String getName() {
        return "codegraph_files";
    }

    @Override
    public ToolDefinition getDefinition() {
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

    @Override
    public ToolCallResult execute(Map<String, Object> args) {
        try {
            ToolCallResult validationError = validatePathArg(args, "path");
            if (validationError != null) return validationError;
            validationError = validatePathArg(args, "pattern");
            if (validationError != null) return validationError;
            String path = strArg(args, "path", "");
            List<String> allFiles = queries.getAllFiles();

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
        } catch (SQLException e) {
            return error("File listing failed: " + e.getMessage());
        }
    }
}
