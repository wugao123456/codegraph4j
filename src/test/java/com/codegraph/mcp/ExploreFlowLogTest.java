package com.codegraph.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;
import com.codegraph.mcp.MCPTransport.ToolCallResult;

public class ExploreFlowLogTest {

    @Test
    public void testExploreFlowLog() {
        String projectPath = "/Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j";
        Path logFile = Paths.get(projectPath, ".codegraph", "logs", "codegraph4j-mcp.log");
        
        long beforeSize = 0;
        if (Files.exists(logFile)) {
            try {
                beforeSize = Files.size(logFile);
            } catch (Exception e) {
                System.err.println("Failed to get log file size: " + e.getMessage());
            }
        }

        DatabaseConnection db = new DatabaseConnection(
                "/Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/.codegraph/codegraph4j.db");
        try {
            db.open();
        } catch (Exception e) {
            System.err.println("Failed to open database: " + e.getMessage());
            System.err.println("Please ensure the database file exists or update the path in the test.");
            return;
        }

        System.out.println("=== 测试 codegraph_explore 流程日志 ===");
        System.out.println("日志文件: " + logFile);
        System.out.println("测试前文件大小: " + beforeSize + " bytes");

        // 通过 MCPServer 构造函数触发文件日志配置
        new MCPServer(projectPath);

        QueryBuilder queries = new QueryBuilder(db);
        MCPToolHandler toolHandler = new MCPToolHandler(projectPath, db, queries);
        
        Map<String, Object> args1 = new HashMap<>();
        args1.put("query", "MCPToolHandler");
        ToolCallResult result = toolHandler.execute("codegraph_explore", args1);

        System.out.println("\n执行结果: " + (result.isError ? "❌ Error" : "✅ Success"));

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {}

        try {
            db.close();
        } catch (Exception e) {
            System.err.println("Failed to close database: " + e.getMessage());
        }

        verifyLogOutput(logFile, beforeSize);
    }

    private static void verifyLogOutput(Path logFile, long beforeSize) {
        try {
            if (!Files.exists(logFile)) {
                System.err.println("❌ 日志文件不存在: " + logFile);
                return;
            }

            long afterSize = Files.size(logFile);
            long diff = afterSize - beforeSize;
            System.out.println("\n测试后文件大小: " + afterSize + " bytes");
            System.out.println("新增日志大小: " + diff + " bytes");

            List<String> lines = Files.readAllLines(logFile);
            
            System.out.println("\n=== 新增的流程日志 ===");
            boolean foundExploreLog = false;
            int stepCount = 0;
            String lastQuery = "";

            for (String line : lines) {
                if (line.contains("[codegraph_explore]")) {
                    if (line.contains("开始处理查询")) {
                        int idx = line.indexOf("query=\"");
                        if (idx >= 0) {
                            lastQuery = line.substring(idx + 7, line.indexOf("\"", idx + 7));
                        }
                    }
                    if (line.contains("Step")) {
                        stepCount++;
                    }
                    System.out.println(line);
                    foundExploreLog = true;
                }
            }

            if (!foundExploreLog) {
                System.err.println("❌ 未找到 [codegraph_explore] 日志");
            } else {
                System.out.println("\n=== 日志验证结果 ===");
                System.out.println("✓ 查询词: " + lastQuery);
                System.out.println("✓ 步骤数: " + stepCount);
                
                if (stepCount >= 7) {
                    System.out.println("✅ 流程日志验证通过！");
                } else {
                    System.out.println("⚠️ 步骤数不足，预期至少7个步骤");
                }
            }

        } catch (Exception e) {
            System.err.println("❌ 读取日志文件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
