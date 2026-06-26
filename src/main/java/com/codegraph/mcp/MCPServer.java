package com.codegraph.mcp;

import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * MCP 服务器入口点。
 * 对标 codegraph 的 index.ts → MCPServer。
 *
 * 生命周期：
 * 1. new MCPServer(projectPath)
 * 2. server.start()
 * 3. 建立数据库连接 → 创建 QueryBuilder → 创建 ToolHandler → 创建 Session
 * 4. Session 在 stdio 上监听 JSON-RPC 请求
 * 5. 当 stdin 关闭时，server 自动退出
 */
public class MCPServer {

    private static final Logger logger = LoggerFactory.getLogger(MCPServer.class);

    private final String projectPath;
    private DatabaseConnection db;
    private MCPSession session;

    public MCPServer(String projectPath) {
        this.projectPath = projectPath;
    }

    /**
     * 启动 MCP 服务器。阻塞直到 stdin 关闭。
     */
    public void start() {
        logger.info("========================================");
        logger.info("  CodeGraph MCP Server");
        logger.info("  Protocol: JSON-RPC 2.0 (MCP {})", MCPTransport.PROTOCOL_VERSION);
        logger.info("  Project:  {}", projectPath);
        logger.info("========================================");

        // 解析项目路径
        File dbFile = new File(projectPath, ".codegraph/codegraph4j.db");
        if (!dbFile.exists()) {
            // 尝试在父目录查找
            File parentDb = new File(new File(projectPath).getParent(), ".codegraph/codegraph4j.db");
            if (parentDb.exists()) {
                dbFile = parentDb;
                logger.info("Found database in parent directory: {}", parentDb.getAbsolutePath());
            } else {
                logger.error("CodeGraph not initialized. Run 'codegraph init' first.");
                // 仍然启动 session 以便报告错误
                dbFile = null;
            }
        }

        if (dbFile != null && dbFile.exists()) {
            try {
                db = new DatabaseConnection(dbFile.getAbsolutePath());
                db.open();
                logger.info("Database opened: {}", dbFile.getAbsolutePath());

                QueryBuilder queries = new QueryBuilder(db);
                MCPToolHandler toolHandler = new MCPToolHandler(
                    projectPath, db, queries);

                session = new MCPSession(projectPath, toolHandler);
                session.start();

                logger.info("MCP Server ready — waiting for JSON-RPC messages on stdin...");
            } catch (Exception e) {
                logger.error("Failed to initialize MCP server", e);
            }
        } else {
            // 无 DB 模式：创建空工具处理器
            logger.warn("No database found — starting in limited mode");
            try {
                MCPToolHandler toolHandler = new MCPToolHandler(projectPath, null, null);
                session = new MCPSession(projectPath, toolHandler);
                session.start();
            } catch (Exception e) {
                logger.error("Failed to start MCP server", e);
            }
        }
    }

    /**
     * 停止 MCP 服务器。
     */
    public void stop() {
        logger.info("Shutting down MCP server...");
        if (session != null) {
            session.stop();
        }
        if (db != null) {
            try {
                db.close();
                logger.info("Database closed");
            } catch (Exception e) {
                logger.warn("Error closing database: {}", e.getMessage());
            }
        }
        logger.info("MCP server stopped");
    }
}
