package com.codegraph.mcp;

import com.codegraph.mcp.MCPTransport.*;
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
    private String clientName = "codegraph4j";
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
        result.capabilities = new ServerCapabilities();
        result.serverInfo = new ServerInfo();
        result.instructions = buildInstructions();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("project", projectPath);
        meta.put("toolCount", toolHandler.getTools().size());
       

        JsonRpcMessage response = new JsonRpcMessage();
        response.id = msg.id;
        response.result = result;
        transport.send(response);

        logger.info("[MCPSession] Initialize complete — protocol: {}, {} tools available",
            MCPTransport.PROTOCOL_VERSION, toolHandler.getTools().size());
    }

    private String buildInstructions() {
        return "# codegraph4j — code intelligence over an indexed knowledge graph\n\n" +
            "codegraph4j is a SQLite knowledge graph of every symbol, edge, and file in\n" +
            "the workspace — pre-computed structure you would otherwise re-derive by\n" +
            "reading files (cached intelligence: thousands of parse/trace decisions you\n" +
            "don't pay to re-reason each run). Reads are sub-millisecond; the index lags\n" +
            "writes by ~1s through the file watcher. Reach for it BEFORE *and* while\n" +
            "writing or editing code — not just for questions: one call returns the\n" +
            "verbatim source PLUS who calls it and what it affects, so you edit with the\n" +
            "blast radius in view. More accurate context, in far fewer tokens and\n" +
            "round-trips than reading files yourself.\n\n" +
            "## One tool: codegraph_explore — use it instead of reading files\n\n" +
            "There is a single tool, `codegraph_explore`, and it is Read-equivalent. It\n" +
            "takes either a natural-language question or a bag of symbol/file names and\n" +
            "returns the **verbatim, line-numbered source** of the relevant symbols\n" +
            "grouped by file — the same `<n>\\t<line>` shape `Read` gives you, safe to\n" +
            "`Edit` from — PLUS the call path among them (including dynamic-dispatch hops\n" +
            "like callbacks, React re-render, and JSX children that grep can't follow) and\n" +
            "a blast-radius summary of what depends on them.\n\n" +
            "Whether you're answering \"how does X work\" or implementing a change (fixing a\n" +
            "bug, adding a feature), call `codegraph_explore` before you Read. ONE call\n" +
            "usually answers the whole question. Codegraph IS the pre-built search index —\n" +
            "so running your own grep + read loop, or delegating the lookup to a separate\n" +
            "file-reading sub-task/agent, repeats work codegraph already did and costs more\n" +
            "for the same answer. A direct codegraph4j answer is typically one to a few\n" +
            "calls; a grep/read exploration is dozens.\n\n" +
            "## How to query\n\n" +
            "- **Almost any question — \"how does X work\", architecture, a bug, \"what/where is X\", or surveying an area** → `codegraph_explore` with a natural-language question or the relevant names. ONE capped call returns the verbatim source grouped by file; most often the ONLY call you need.\n" +
            "- **\"How does X reach/become Y? / the flow / the path from X to Y\"** → `codegraph_explore`, naming the symbols that span the flow (e.g. `mutateElement renderScene`) — it surfaces the call path among them, riding dynamic-dispatch hops, and returns their source.\n" +
            "- **Reading or editing a file/symbol you can name** → put its name or file path in the `codegraph_explore` query — it returns that current line-numbered source (safe to `Edit` from) with the call path and blast radius attached, so you don't Read it separately. For an overloaded name it returns every matching definition's body in one call.\n" +
            "- **Need more?** Call `codegraph_explore` again with more specific names — treat the source it returns as already Read.\n\n" +
            "## Anti-patterns\n\n" +
            "- **Trust codegraph4j's results — don't re-verify them with grep.** They come from a full AST parse; re-checking with grep is slower, less accurate, and wastes context.\n" +
            "- **Don't grep or Read first** to find or understand indexed code — ONE `codegraph_explore` returns the relevant symbols' source together in a single round-trip. Reach for raw `Read`/`Grep` only to confirm a specific detail codegraph didn't cover, or for what codegraph doesn't index (configs, docs).\n" +
            "- **Don't reconstruct a flow by hand** — name the endpoints in one `codegraph_explore` and it surfaces the path between them, dynamic-dispatch hops included.\n" +
            "- **After editing, check the staleness banner.** When a tool response starts with \"⚠️ Some files referenced below were edited since the last index sync…\", the listed files are pending re-index — Read those specific files for accurate content. Every file NOT in that banner is fresh, so still trust codegraph. A different, rarer banner — \"⚠️ CodeGraph auto-sync is DISABLED…\" — means live watching stopped entirely (the whole index is frozen, not just a few files); until it's resolved, Read files directly to confirm anything that may have changed.\n\n" +
            "## Limitations\n\n" +
            "- If a tool reports a project isn't indexed (no `.codegraph/`), stop calling codegraph4j tools for that project for the rest of the session and use your built-in tools there instead. Indexing is the user's decision — mention they can run `codegraph init` if it comes up, but don't run it yourself.\n" +
            "- Index lags file writes by ~1 second.\n" +
            "- Cross-file resolution is best-effort name matching; ambiguous calls may return multiple candidates.\n" +
            "- No live correctness validation — that's still the TypeScript compiler / test suite / linter's job. codegraph4j supplements those with structural context they don't have.\n";
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
