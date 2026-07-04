package com.codegraph.mcp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * JSON-RPC 2.0 协议消息定义 + StdioTransport。
 * 对标 codegraph 的 transport.ts。
 */
public class MCPTransport {

    static final ObjectMapper JSON = new ObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public static final String PROTOCOL_VERSION = "2024-11-05";

    // ---- JSON-RPC 消息 ----

    public static class JsonRpcMessage {
        public String jsonrpc = "2.0";
        public String id;       // null for notifications
        public String method;
        public Object params;
        public Object result;
        public JsonRpcError error;

        @JsonIgnore public boolean isRequest() { return method != null && id != null; }
        @JsonIgnore public boolean isNotification() { return method != null && id == null; }
        @JsonIgnore public boolean isResponse() { return method == null && (result != null || error != null); }
    }

    public static class JsonRpcError {
        public int code;
        public String message;
        public Object data;

        public JsonRpcError() {}
        public JsonRpcError(int code, String message) { this.code = code; this.message = message; }
    }

    // ---- StdioTransport ----

    public interface MessageHandler {
        void onMessage(JsonRpcMessage message);
        void onError(Throwable error);
        void onClose();
    }

    public static class StdioTransport {
        private final MessageHandler handler;
        private volatile boolean running = true;
        private final StringBuilder buffer = new StringBuilder();
        private Thread readerThread;

        public StdioTransport(MessageHandler handler) {
            this.handler = handler;
        }

        /**
         * 启动 stdio 传输，阻塞直到 stdin 关闭。
         */
        public void start() {
            readerThread = new Thread(() -> {
                try {
                    java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(System.in, "UTF-8"));
                    String line;
                    while (running && (line = br.readLine()) != null) {
                        processLine(line);
                    }
                } catch (Exception e) {
                    if (running) handler.onError(e);
                }
                handler.onClose();
            }, "mcp-stdin-reader");
            readerThread.setDaemon(false);
            readerThread.start();

            // 阻塞主线程直到 stdin 读取线程结束
            try {
                readerThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * 发送 JSON-RPC 消息到 stdout。
         */
        public synchronized void send(JsonRpcMessage message) {
            try {
                String json = JSON.writeValueAsString(message);
                System.out.println(json);
                System.out.flush();
            } catch (JsonProcessingException e) {
                handler.onError(e);
            }
        }

        public synchronized void sendRaw(String json) {
            System.out.println(json);
            System.out.flush();
        }

        public void stop() {
            running = false;
        }

        private void processLine(String line) {
            if (line.trim().isEmpty()) return;

            try {
                JsonRpcMessage msg = JSON.readValue(line, JsonRpcMessage.class);

                // 验证 jsonrpc
                if (msg.jsonrpc == null || !"2.0".equals(msg.jsonrpc)) {
                    JsonRpcMessage errorResp = new JsonRpcMessage();
                    errorResp.id = msg.id;
                    errorResp.error = new JsonRpcError(-32600, "Invalid Request: missing jsonrpc");
                    send(errorResp);
                    return;
                }

                handler.onMessage(msg);
            } catch (Exception e) {
                JsonRpcMessage errorResp = new JsonRpcMessage();
                errorResp.error = new JsonRpcError(-32700, "Parse error: " + e.getMessage());
                send(errorResp);
            }
        }
    }

    // ---- MCP 特定类型 ----

    public static class InitializeRequest {
        public String protocolVersion;
        public ClientCapabilities capabilities;
        public ClientInfo clientInfo;

        @JsonProperty("rootUri")
        public String rootUri;
        public List workspaceFolders;

        public static class List extends java.util.ArrayList<WorkspaceFolder> {}
    }

    public static class WorkspaceFolder {
        public String uri;
        public String name;
    }

    public static class ClientCapabilities {
        public Map<String, Object> roots;
        public Map<String, Object> sampling;
    }

    public static class ClientInfo {
        public String name;
        public String version;
    }

    public static class InitializeResult {
        public String protocolVersion = PROTOCOL_VERSION;
        public ServerCapabilities capabilities = new ServerCapabilities();
        public ServerInfo serverInfo = new ServerInfo();
        public String instructions;

        @JsonProperty("_meta")
        public Map<String, Object> meta;
    }

    public static class ServerCapabilities {
        public Map<String, Object> tools = new java.util.HashMap<>();
    }

    public static class ServerInfo {
        public String name = "codegraph";
        public String version = "1.0.0-SNAPSHOT";
    }

    public static class ToolDefinition {
        public String name;
        public String description;
        public Map<String, Object> inputSchema;

        public ToolDefinition() {}
        public ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
        }
    }

    public static class ToolListResult {
        public java.util.List<ToolDefinition> tools = new java.util.ArrayList<>();
    }

    public static class ToolCallRequest {
        public String name;
        public Map<String, Object> arguments;
    }

    public static class ToolCallResult {
        public java.util.List<ContentItem> content = new java.util.ArrayList<>();
        @JsonProperty("isError")
        public boolean isError;
    }

    public static class ContentItem {
        public String type = "text";
        public String text;

        public ContentItem() {}
        public ContentItem(String text) { this.text = text; }
        public ContentItem(String text, boolean isError) {
            this.text = text;
            if (isError) this.type = "error";
        }
    }
}
