package com.codegraph.mcp;

import com.codegraph.mcp.MCPTransport.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * MCP 会话 — 管理单个客户端 JSON-RPC 连接的完整生命周期。
 * 对标 codegraph 的 session.ts。
 *
 * 状态机：UNINITIALIZED → INITIALIZED → 运行中
 *
 * 支持的 JSON-RPC 方法：
 * - initialize          → 握手
 * - initialized          → 通知（忽略）
 * - tools/list           → 返回工具列表
 * - tools/call           → 执行工具
 * - ping                 → 心跳
 * - resources/list       → 空列表（兼容 opencode/Codex）
 * - prompts/list         → 空列表（兼容）
 */
public class MCPSession implements MCPTransport.MessageHandler {

    private static final Logger logger = LoggerFactory.getLogger(MCPSession.class);

    private enum State { UNINITIALIZED, INITIALIZED }

    private final StdioTransport transport;
    private final MCPToolHandler toolHandler;
    private State state = State.UNINITIALIZED;
    private String clientName = "unknown";
    private String projectPath;

    public MCPSession(String projectPath, MCPToolHandler toolHandler) {
        this.projectPath = projectPath;
        this.toolHandler = toolHandler;
        this.transport = new StdioTransport(this);
    }

    public void start() {
        transport.start();
        logger.info("[MCPSession] MCP session started for project: {}", projectPath);
    }

    public void stop() {
        transport.stop();
        logger.info("[MCPSession] MCP session stopped");
    }

    // ---- MessageHandler ----

    @Override
    public void onMessage(JsonRpcMessage msg) {
        try {
            if (msg.isResponse()) {
                // 目前不需要处理服务器发起请求的响应
                return;
            }

            if (msg.isRequest()) {
                handleRequest(msg);
            } else if (msg.isNotification()) {
                handleNotification(msg);
            }
        } catch (Exception e) {
            logger.error("[MCPSession] Error handling message", e);
            sendError(msg.id, -32603, "Internal error: " + e.getMessage());
        }
    }

    @Override
    public void onError(Throwable error) {
        logger.error("[MCPSession] Transport error", error);
    }

    @Override
    public void onClose() {
        logger.info("[MCPSession] Transport closed");
        stop();
    }

    // ---- 请求处理 ----

    private void handleRequest(JsonRpcMessage msg) {
        String method = msg.method;

        switch (method) {
            case "initialize":
                handleInitialize(msg);
                break;
            case "tools/list":
                handleToolsList(msg);
                break;
            case "tools/call":
                handleToolsCall(msg);
                break;
            case "ping":
                handlePing(msg);
                break;
            case "resources/list":
            case "resources/templates/list":
            case "prompts/list":
                // 兼容性：返回空列表
                sendResult(msg.id, Collections.emptyList());
                break;
            default:
                sendError(msg.id, -32601, "Method not found: " + method);
                break;
        }
    }

    private void handleNotification(JsonRpcMessage msg) {
        if ("initialized".equals(msg.method)) {
            logger.info("[MCPSession] Client initialized: {}", clientName);
            // initialized 通知接收后不需要操作
        } else if ("notifications/initialized".equals(msg.method)) {
            logger.info("[MCPSession] Client notifications initialized");
        }
    }

    // ---- initialize ----

    @SuppressWarnings("unchecked")
    private void handleInitialize(JsonRpcMessage msg) {
        Map<String, Object> params = (Map<String, Object>) msg.params;
        if (params != null) {
            Map<String, Object> clientInfo = (Map<String, Object>) params.get("clientInfo");
            if (clientInfo != null) {
                clientName = (String) clientInfo.getOrDefault("name", "unknown");
                logger.info("[MCPSession] Client: {} v{}", clientName, clientInfo.get("version"));
            }
        }

        state = State.INITIALIZED;

        InitializeResult result = new InitializeResult();
        result.protocolVersion = MCPTransport.PROTOCOL_VERSION;
        result.serverInfo = new ServerInfo();
        result.instructions = buildInstructions();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("project", projectPath);
        meta.put("toolCount", toolHandler.getTools().size());
        result.meta = meta;

        JsonRpcMessage response = new JsonRpcMessage();
        response.id = msg.id;
        response.result = result;
        transport.send(response);

        logger.info("[MCPSession] Initialize complete — protocol: {}, {} tools available",
            MCPTransport.PROTOCOL_VERSION, toolHandler.getTools().size());
    }

    private String buildInstructions() {
        StringBuilder sb = new StringBuilder();
        sb.append("# CodeGraph MCP Server\n\n");
        sb.append("Use `codegraph_explore` for most questions. It returns contextual source code.\n\n");

        sb.append("## Available Tools\n\n");
        for (ToolDefinition tool : toolHandler.getTools()) {
            sb.append("- **").append(tool.name).append("**: ").append(tool.description).append("\n");
        }

        sb.append("\n## Project\n");
        sb.append("Path: ").append(projectPath).append("\n");
        sb.append("Language: Java\n");

        return sb.toString();
    }

    // ---- tools/list ----

    private void handleToolsList(JsonRpcMessage msg) {
        if (state != State.INITIALIZED) {
            sendError(msg.id, -32000, "Server not initialized");
            return;
        }

        ToolListResult result = new ToolListResult();
        result.tools = toolHandler.getTools();

        JsonRpcMessage response = new JsonRpcMessage();
        response.id = msg.id;
        response.result = result;
        transport.send(response);
    }

    // ---- tools/call ----

    @SuppressWarnings("unchecked")
    private void handleToolsCall(JsonRpcMessage msg) {
        if (state != State.INITIALIZED) {
            sendError(msg.id, -32000, "Server not initialized");
            return;
        }

        Map<String, Object> params = (Map<String, Object>) msg.params;
        if (params == null) {
            sendError(msg.id, -32602, "Missing params");
            return;
        }

        String toolName = (String) params.get("name");
        Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");
        if (arguments == null) arguments = Collections.emptyMap();

        logger.info("[MCPSession] tools/call: {} with {}", toolName, arguments.keySet());

        long startTime = System.currentTimeMillis();
        ToolCallResult result = toolHandler.execute(toolName, arguments);
        long elapsed = System.currentTimeMillis() - startTime;

        logger.info("[MCPSession] tools/call {} completed in {}ms, isError={}",
            toolName, elapsed, result.isError);

        JsonRpcMessage response = new JsonRpcMessage();
        response.id = msg.id;
        response.result = result;
        transport.send(response);
    }

    // ---- ping ----

    private void handlePing(JsonRpcMessage msg) {
        JsonRpcMessage response = new JsonRpcMessage();
        response.id = msg.id;
        response.result = Collections.emptyMap();
        transport.send(response);
    }

    // ---- 响应辅助 ----

    private void sendResult(String id, Object result) {
        JsonRpcMessage response = new JsonRpcMessage();
        response.id = id;
        response.result = result;
        transport.send(response);
    }

    private void sendError(String id, int code, String message) {
        JsonRpcMessage response = new JsonRpcMessage();
        response.id = id;
        response.error = new JsonRpcError(code, message);
        transport.send(response);
    }
}
