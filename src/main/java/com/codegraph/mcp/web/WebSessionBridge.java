package com.codegraph.mcp.web;

import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;
import com.codegraph.graph.GraphViewer;
import com.codegraph.graph.GraphViewer.GraphViewData;
import com.codegraph.mcp.MCPToolHandler;
import com.codegraph.mcp.MCPTransport;
import com.codegraph.mcp.MCPTransport.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;

/**
 * Web HTTP → MCP JSON-RPC 桥接器。
 * 将来自 HTTP 的 JSON-RPC 请求解析后分发：graph/view 方法直接查询数据库，
 * 其他方法委托给 MCPToolHandler 执行。
 */
public class WebSessionBridge {

    private static final Logger logger = LoggerFactory.getLogger(WebSessionBridge.class);
    private static final ObjectMapper JSON = MCPTransport.JSON;

    private final MCPToolHandler toolHandler;
    private final DatabaseConnection db;
    private final QueryBuilder queries;

    public WebSessionBridge(MCPToolHandler toolHandler, DatabaseConnection db, QueryBuilder queries) {
        this.toolHandler = toolHandler;
        this.db = db;
        this.queries = queries;
    }

    /**
     * 处理来自 HTTP 的 JSON-RPC 请求体，返回 JSON-RPC 响应字符串。
     */
    @SuppressWarnings("unchecked")
    public String handleJsonRpc(String requestBody) {
        try {
            JsonRpcMessage request = JSON.readValue(requestBody, JsonRpcMessage.class);

            // 验证 jsonrpc
            if (request.jsonrpc == null || !"2.0".equals(request.jsonrpc)) {
                return buildErrorResponse(request.id, -32600, "Invalid Request: missing jsonrpc");
            }

            String method = request.method;

            if ("tools/list".equals(method)) {
                return handleToolsList(request);
            } else if ("tools/call".equals(method)) {
                return handleToolsCall(request);
            } else if ("graph/view".equals(method)) {
                return handleGraphView(request);
            } else if ("ping".equals(method)) {
                return buildResultResponse(request.id, Collections.emptyMap());
            } else {
                return buildErrorResponse(request.id, -32601, "Method not found: " + method);
            }
        } catch (Exception e) {
            logger.error("Error handling JSON-RPC request", e);
            return buildErrorResponse(null, -32700, "Parse error: " + e.getMessage());
        }
    }

    private String handleToolsList(JsonRpcMessage request) throws JsonProcessingException {
        ToolListResult result = new ToolListResult();
        result.tools = toolHandler.getTools();

        JsonRpcMessage response = new JsonRpcMessage();
        response.id = request.id;
        response.result = result;
        return JSON.writeValueAsString(response);
    }

    @SuppressWarnings("unchecked")
    private String handleToolsCall(JsonRpcMessage request) throws JsonProcessingException {
        Map<String, Object> params = (Map<String, Object>) request.params;
        if (params == null) {
            return buildErrorResponse(request.id, -32602, "Missing params");
        }

        String toolName = (String) params.get("name");
        Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");
        if (arguments == null) arguments = Collections.emptyMap();

        logger.info("[WebBridge] tools/call: {}", toolName);

        ToolCallResult result = toolHandler.execute(toolName, arguments);

        JsonRpcMessage response = new JsonRpcMessage();
        response.id = request.id;
        response.result = result;
        return JSON.writeValueAsString(response);
    }

    @SuppressWarnings("unchecked")
    private String handleGraphView(JsonRpcMessage request) throws JsonProcessingException {
        Map<String, Object> params = (Map<String, Object>) request.params;
        if (params == null) params = Collections.emptyMap();

        String symbol = (String) params.get("symbol");
        String file = (String) params.get("file");
        boolean includeImports = Boolean.TRUE.equals(params.get("includeImports"));
        int maxNodes = 250;
        if (params.get("maxNodes") instanceof Number) {
            maxNodes = ((Number) params.get("maxNodes")).intValue();
        }
        int degree = 3;
        if (params.get("degree") instanceof Number) {
            degree = ((Number) params.get("degree")).intValue();
        }

        logger.info("[WebBridge] graph/view symbol={}, file={}, maxNodes={}, degree={}", symbol, file, maxNodes, degree);

        try {
            GraphViewData data = GraphViewer.buildViewData(db, symbol, file, includeImports, maxNodes, degree);

            JsonRpcMessage response = new JsonRpcMessage();
            response.id = request.id;
            response.result = data;
            return JSON.writeValueAsString(response);
        } catch (Exception e) {
            logger.error("graph/view failed", e);
            ToolCallResult errorResult = new ToolCallResult();
            errorResult.isError = true;
            ContentItem item = new ContentItem(e.getMessage(), true);
            errorResult.content = Collections.singletonList(item);

            JsonRpcMessage response = new JsonRpcMessage();
            response.id = request.id;
            response.result = errorResult;
            return JSON.writeValueAsString(response);
        }
    }

    private String buildResultResponse(String id, Object result) {
        try {
            JsonRpcMessage response = new JsonRpcMessage();
            response.id = id;
            response.result = result;
            return JSON.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            return buildErrorResponse(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private String buildErrorResponse(String id, int code, String message) {
        try {
            JsonRpcMessage response = new JsonRpcMessage();
            response.id = id;
            response.error = new JsonRpcError(code, message);
            return JSON.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error: " + e.getMessage() + "\"}}";
        }
    }
}
