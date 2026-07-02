package com.codegraph.mcp;

import java.util.HashMap;
import java.util.Map;

import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;

import com.codegraph.mcp.MCPTransport.ToolCallResult;
import com.codegraph.utils.MarkdownUtils;

public class MCPToolHandlerTest {


    public static void main(String[] args) {
        String projectPath = "/Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j";
        DatabaseConnection db = new DatabaseConnection(
                "/Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/.codegraph/codegraph4j.db");
        try {
            db.open();
        } catch (Exception e) {
            System.err.println("Failed to open database: " + e.getMessage());
            System.err.println("Please ensure the database file exists or update the path in the test.");
            return;
        }

        QueryBuilder queries = new QueryBuilder(db);
        MCPToolHandler toolHandler = new MCPToolHandler(
                projectPath, db, queries);
        Map<String, Object> args1 = new HashMap<>();
        args1.put("query", "codegraph_explore 索2引流程的完整调用链和核心步骤");
        ToolCallResult result = toolHandler.execute("codegraph_explore", args1);
        System.out.println(result);
        MarkdownUtils.writeMarkdownToFile(result, "codegraph_explore", args1.get("query").toString(), projectPath);

        try {
            db.close();
        } catch (Exception e) {
            System.err.println("Failed to close database: " + e.getMessage());
        }
    }
}
