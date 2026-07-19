package com.codegraph.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * 嵌入式 HTTP Web 服务器。
 * 在 MCP stdio 服务之外提供 Web 可视化界面访问。
 *
 * 路由：
 * - GET  /           → viewer.html
 * - POST /api/mcp    → JSON-RPC over HTTP
 * - GET  /assets/*   → 静态资源（vis-network.min.js 等）
 */
public class WebServer {

    private static final Logger logger = LoggerFactory.getLogger(WebServer.class);

    private final int port;
    private final WebSessionBridge bridge;
    private HttpServer server;

    public WebServer(int port, WebSessionBridge bridge) {
        this.port = port;
        this.bridge = bridge;
    }

    /**
     * 启动 HTTP 服务器。HttpServer 内部使用非守护线程池，JVM 不会因主线程退出而关闭。
     */
    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", new StaticFileHandler());
            server.createContext("/api/mcp", new JsonRpcHandler());
            server.createContext("/assets/", new AssetsHandler());
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();
            logger.info("Web viewer started at http://localhost:{}", port);
        } catch (Exception e) {
            logger.error("Failed to start Web server on port {}: {}", port, e.getMessage());
        }
    }

    /**
     * 停止 HTTP 服务器。
     */
    public void stop() {
        if (server != null) {
            server.stop(1);
            logger.info("Web server stopped");
        }
    }

    // ===== 请求处理器 =====

    /**
     * 主页处理器 — 返回 viewer.html。
     */
    private class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                InputStream is = getClass().getResourceAsStream("/web/viewer.html");
                if (is == null) {
                    sendText(exchange, 404, "Not Found");
                    return;
                }
                byte[] content = readAllBytes(is);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, content.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(content);
                }
            } catch (Exception e) {
                logger.error("Error serving static file", e);
                sendText(exchange, 500, "Internal Server Error");
            }
        }
    }

    /**
     * JSON-RPC API 处理器。
     */
    private class JsonRpcHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // CORS 头
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }

            try {
                String requestBody = readRequestBody(exchange);
                String responseBody = bridge.handleJsonRpc(requestBody);
                sendJson(exchange, 200, responseBody);
            } catch (Exception e) {
                logger.error("Error handling API request", e);
                sendJson(exchange, 500, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}");
            }
        }
    }

    /**
     * 静态资源处理器 — 服务 /assets/* 路径下的文件。
     */
    private class AssetsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            // 提取 /assets/ 之后的文件名
            String assetName = path.substring("/assets/".length());
            if (assetName.isEmpty()) {
                sendText(exchange, 404, "Not Found");
                return;
            }

            // 尝试从 classpath 加载
            InputStream is = getClass().getResourceAsStream("/graph/" + assetName);
            if (is == null) {
                is = getClass().getResourceAsStream("/assets/" + assetName);
            }
            if (is == null) {
                sendText(exchange, 404, "Not Found");
                return;
            }

            byte[] content = readAllBytes(is);
            String contentType = "application/octet-stream";
            if (assetName.endsWith(".js")) contentType = "application/javascript";
            else if (assetName.endsWith(".css")) contentType = "text/css";
            else if (assetName.endsWith(".html")) contentType = "text/html";

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(content);
            }
        }
    }

    // ===== 辅助方法 =====

    private void sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private byte[] readAllBytes(InputStream is) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }
}
