package com.codegraph.mcp;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import com.codegraph.config.CodeGraphConfig;
import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;
import com.codegraph.mcp.MCPTransport.ToolCallResult;
import com.codegraph.sync.SyncOrchestrator;
import com.codegraph.sync.SyncResult;

import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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
 *
 * 新增 catch-up sync 机制（对标 Node.js codegraph engine.ts:286-313）：
 * - MCP 服务启动后立即触发增量同步（异步）
 * - 首个工具调用等待同步完成（gate 机制）
 * - 同步完成后输出变更统计到 stderr
 */
public class MCPServer {

    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(MCPServer.class);

    private final CodeGraphConfig config;
    private final boolean javaProject;
    private DatabaseConnection db;
    private MCPSession session;
    private MCPToolHandler toolHandler;

    public MCPServer(CodeGraphConfig config) {
        this.config = config;
        System.setProperty("codegraph.projectPath", config.getProjectPath());
        this.javaProject = ProjectDetector.isJavaProject(config.getProjectPath());
        if (this.javaProject) {
            setupFileLogging();
        }
    }

    /**
     * 设置文件日志，输出到 projectPath/.codegraph/logs/codegraph4j-mcp.log。
     * 在构造函数中调用，确保所有后续日志都写入文件。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void setupFileLogging() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);

        // 如果已存在同名的 MCP_FILE appender，跳过重复创建
        if (rootLogger.getAppender("MCP_FILE") != null) {
            return;
        }

        File logDir = config.getLogDir();
        logDir.mkdirs();

        String logPath = new File(logDir, "codegraph4j-mcp.log").getAbsolutePath();

        RollingFileAppender fileAppender = new RollingFileAppender();
        fileAppender.setName("MCP_FILE");
        fileAppender.setContext(context);
        fileAppender.setFile(logPath);
        fileAppender.setAppend(true);

        TimeBasedRollingPolicy rollingPolicy = new TimeBasedRollingPolicy();
        rollingPolicy.setContext(context);
        rollingPolicy.setFileNamePattern(logPath + ".%d{yyyy-MM-dd}");
        rollingPolicy.setMaxHistory(5);
        rollingPolicy.setParent(fileAppender);
        rollingPolicy.start();

        fileAppender.setRollingPolicy(rollingPolicy);

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
        encoder.start();

        fileAppender.setEncoder(encoder);
        fileAppender.start();

        // 为所有 codegraph 模块添加文件输出
        for (String loggerName : new String[]{
            "com.codegraph", "com.codegraph.mcp", "com.codegraph.db",
            "com.codegraph.parser", "com.codegraph.cli"
        }) {
            Logger lg = context.getLogger(loggerName);
            lg.addAppender(fileAppender);
            lg.setAdditive(false);
        }

        rootLogger.addAppender(fileAppender);
    }

    /**
     * 移除控制台 appender，防止日志污染 MCP stdio 通道。
     * 仅 serve --mcp 模式调用。
     */
    public void detachConsole() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        for (String loggerName : new String[]{
            "ROOT", "com.codegraph", "com.codegraph.mcp", "com.codegraph.db",
            "com.codegraph.parser", "com.codegraph.cli"
        }) {
            Logger lg = context.getLogger(loggerName);
            lg.detachAppender("STDOUT");
        }
    }

    /**
     * 启动 MCP 服务器。阻塞直到 stdin 关闭。
     */
    public void start() {
        logger.info("========================================");
        logger.info("  CodeGraph MCP Server");
        logger.info("  Protocol: JSON-RPC 2.0 (MCP {})", MCPTransport.PROTOCOL_VERSION);
        logger.info("  Project:  {}", config.getProjectPath());
        logger.info("========================================");

        // 解析项目路径
        File dbFile = config.getDbFile();
        if (!dbFile.exists()) {
            // 尝试在父目录查找
            File parentDb = new File(new File(config.getDbFile().getParent()).getParent(), ".codegraph/codegraph4j.db");
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
                toolHandler = new MCPToolHandler(config, db, queries);

                startCatchUpSync(queries);

                session = new MCPSession(config.getProjectPath(), toolHandler);
                session.start();

                logger.info("MCP Server ready — waiting for JSON-RPC messages on stdin...");
            } catch (Exception e) {
                logger.error("Failed to initialize MCP server", e);
            }
        } else {
            // 无 DB 模式：创建空工具处理器
            logger.warn("No database found — starting in limited mode");
            try {
                MCPToolHandler toolHandler = new MCPToolHandler(config, null, null);
                session = new MCPSession(config.getProjectPath(), toolHandler);
                session.start();
            } catch (Exception e) {
                logger.error("Failed to start MCP server", e);
            }
        }
    }

    /**
     * 启动 catch-up 同步（对标 Node.js codegraph engine.ts:286-313）。
     * 异步执行增量同步，首个工具调用等待同步完成。
     */
    private void startCatchUpSync(QueryBuilder queries) {
        CompletableFuture<Void> syncFuture = CompletableFuture.runAsync(() -> {
            try {
                Path projectRoot = Paths.get(config.getProjectPath());
                SyncOrchestrator orchestrator = new SyncOrchestrator();
                SyncResult result = orchestrator.sync(projectRoot, queries, false, null);

                int changed = result.getFilesChanged();
                if (changed > 0) {
                    System.err.println("[CodeGraph MCP] Caught up " + changed + " file(s) changed since last run");
                }
            } catch (Exception e) {
                logger.warn("Catch-up sync failed: {}", e.getMessage());
            }
        });

        toolHandler.setCatchUpGate(syncFuture);
        logger.info("Catch-up sync started — first tool call will wait for completion");
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
