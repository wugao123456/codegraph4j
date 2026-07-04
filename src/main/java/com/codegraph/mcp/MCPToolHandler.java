package com.codegraph.mcp;

import com.codegraph.config.CodeGraphConfig;
import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;
import com.codegraph.graph.GraphQueryManager;
import com.codegraph.graph.GraphTraverser;
import com.codegraph.mcp.MCPTransport.ToolCallResult;
import com.codegraph.mcp.MCPTransport.ToolDefinition;
import com.codegraph.mcp.tools.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * MCP 工具处理器 — 薄层门面，负责工具注册和分发。
 * 对标 codegraph 的 tools.ts。
 *
 * 所有 Tool 的实现已拆分到 {@code com.codegraph.mcp.tools} 包中。
 *
 * 对外暴露：
 * - codegraph_explore、codegraph_search、codegraph_callers、codegraph_callees
 * - codegraph_impact、codegraph_node、codegraph_status、codegraph_files
 */
public class MCPToolHandler {

    private static final Logger logger = LoggerFactory.getLogger(MCPToolHandler.class);

    private static final String NOT_JAVA_MSG =
        "This project is not a Java project. " +
        "CodeGraph4j currently only supports Java projects (detected via pom.xml / build.gradle / .java files).";

    // 工具白名单（可通过 CODEGRAPH_MCP_TOOLS 环境变量覆盖）
    private static final Set<String> DEFAULT_MCP_TOOLS = new HashSet<>(
        Arrays.asList("explore"));

    static {
        String env = System.getenv("CODEGRAPH_MCP_TOOLS");
        if (env != null && !env.isEmpty()) {
            DEFAULT_MCP_TOOLS.clear();
            DEFAULT_MCP_TOOLS.addAll(Arrays.asList(env.split(",")));
        }
    }

    private final CodeGraphConfig config;
    private final DatabaseConnection db;
    private final QueryBuilder queries;
    private final ToolRegistry registry;
    private final boolean javaProject;

    private CompletableFuture<Void> catchUpGate = CompletableFuture.completedFuture(null);
    private volatile boolean firstToolCall = true;

    public MCPToolHandler(CodeGraphConfig config, DatabaseConnection db, QueryBuilder queries) {
        this(config, db, queries, true);
    }

    public MCPToolHandler(CodeGraphConfig config, DatabaseConnection db, QueryBuilder queries, boolean javaProject) {
        this.config = config;
        this.db = db;
        this.queries = queries;
        this.javaProject = javaProject;

        if (!javaProject) {
            this.registry = new ToolRegistry();
            return;
        }

        GraphTraverser traverser = new GraphTraverser(queries);
        GraphQueryManager graphQueryMgr = new GraphQueryManager(queries);

        this.registry = new ToolRegistry();

        if (isEnabled("explore")) registry.register(new ExploreTool(db, queries, traverser, graphQueryMgr, config));
        if (isEnabled("search"))   registry.register(new SearchTool(db, queries, traverser, graphQueryMgr, config));
        if (isEnabled("callers"))  registry.register(new CallersTool(db, queries, traverser, graphQueryMgr, config));
        if (isEnabled("callees"))  registry.register(new CalleesTool(db, queries, traverser, graphQueryMgr, config));
        if (isEnabled("impact"))   registry.register(new ImpactTool(db, queries, traverser, graphQueryMgr, config));
        if (isEnabled("node"))     registry.register(new NodeTool(db, queries, traverser, graphQueryMgr, config));
        if (isEnabled("status"))   registry.register(new StatusTool(db, queries, traverser, graphQueryMgr, config));
        if (isEnabled("files"))    registry.register(new FilesTool(db, queries, traverser, graphQueryMgr, config));

        logger.info("Enabled tools: {} tools", registry.size());
    }

    public void setCatchUpGate(CompletableFuture<Void> gate) {
        this.catchUpGate = gate;
    }

    private void waitForCatchUp() {
        if (firstToolCall) {
            firstToolCall = false;
            try {
                catchUpGate.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.warn("Catch-up sync timeout or failed: {}", e.getMessage());
            }
        }
    }

    public DatabaseConnection getDb() {
        return db;
    }

    /** 返回所有已启用工具的定义列表（JSON Schema 元信息） */
    public List<ToolDefinition> getTools() {
        return registry.getDefinitions();
    }

    /** 按工具名分发执行 */
    public ToolCallResult execute(String toolName, Map<String, Object> args) {
        waitForCatchUp();

        if (!javaProject) {
            return error(NOT_JAVA_MSG);
        }
        try {
            Tool tool = registry.get(toolName);
            if (tool == null) {
                return error("Unknown tool: " + toolName);
            }
            return tool.execute(args);
        } catch (Exception e) {
            logger.error("Tool {} failed", toolName, e);
            return error("Tool execution failed: " + e.getMessage());
        }
    }

    private static boolean isEnabled(String tool) {
        return DEFAULT_MCP_TOOLS.contains(tool);
    }

    private static ToolCallResult error(String message) {
        return BaseTool.error(message);
    }
}
