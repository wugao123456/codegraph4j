package com.codegraph.mcp;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * CodeGraph MCP 客户端测试 — 连接到已安装的 codegraph npm 包的 MCP 服务。
 *
 * 使用系统已安装的 codegraph CLI（npm package）:
 *   command: codegraph
 *   args: ["serve", "--mcp", "-p", "/path/to/project"]
 *
 * 完整模拟 Trae 的 MCP 交互流程：
 * 1. spawn codegraph 子进程 (codegraph serve --mcp -p /path/to/project)
 * 2. 发送 initialize → 获取 capabilities
 * 3. 发送 tools/list → 获取可用工具列表
 * 4. 发送 tools/call codegraph_explore → 获取源码分析结果
 * 5. 关闭 stdin → 进程优雅退出
 *
 * 运行方式：
 *   cd codegraph4j && mvn test-compile -q
 *   java -cp "target/test-classes:target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout)" \
 *     com.codegraph.mcp.CodegraphMcpClientTest -p /path/to/project query=MCPServer
 */
public class CodegraphMcpClientTest {

    private static final String DEFAULT_PROJECT_PATH = "/Users/wugao-pc/Desktop/Project/codegraph4j";

    public static void main(String[] args) throws Exception {
        String projectPath = DEFAULT_PROJECT_PATH;
        List<String> queries = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            if ("-p".equals(args[i]) && i + 1 < args.length) {
                projectPath = args[i + 1];
                i++;
            } else if (args[i].startsWith("project=")) {
                projectPath = args[i].substring(8);
            } else if (args[i].startsWith("query=")) {
                queries.add(args[i].substring(6));
            } else {
                queries.add(args[i]);
            }
        }
        if (queries.isEmpty()) {
            queries.add("MCPServer");
        }

        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║   CodeGraph MCP Client Test (npm package)   ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println("项目路径: " + projectPath);
        System.out.println("查询内容: " + queries);
        System.out.println();

        ProcessBuilder pb = new ProcessBuilder("codegraph", "serve", "--mcp", "-p", projectPath);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        BufferedReader stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
        BufferedWriter stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), "UTF-8"));
        BufferedReader stderr = new BufferedReader(new InputStreamReader(process.getErrorStream(), "UTF-8"));

        Thread stderrReader = new Thread(() -> {
            try {
                String line;
                while ((line = stderr.readLine()) != null) {
                    System.err.println("[codegraph] " + line);
                }
            } catch (IOException ignored) {}
        }, "stderr-reader");
        stderrReader.setDaemon(true);
        stderrReader.start();

        try {
            System.out.println(">>> [1/4] 发送 initialize...");
            String initMsg = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"initialize\"," +
                "\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{}," +
                "\"clientInfo\":{\"name\":\"java-client\",\"version\":\"1.0\"}}}";
            stdin.write(initMsg);
            stdin.newLine();
            stdin.flush();

            String initResponse = readResponse(stdout, 10000);
            System.out.println("<<< initialize 响应 (前5000字符):");
            System.out.println(truncate(initResponse, 5000));
            System.out.println();

            System.out.println(">>> [2/4] 发送 tools/list...");
            String listMsg = "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"method\":\"tools/list\",\"params\":{}}";
            stdin.write(listMsg);
            stdin.newLine();
            stdin.flush();

            String listResponse = readResponse(stdout, 10000);
            System.out.println("<<< tools/list 响应:");
            printToolList(listResponse);
            System.out.println();

            for (int i = 0; i < queries.size(); i++) {
                String query = queries.get(i);
                System.out.println(">>> [" + (i + 3) + "/" + (queries.size() + 3) +
                    "] 发送 tools/call codegraph_explore query=\"" + query + "\"...");

                String exploreMsg = "{\"jsonrpc\":\"2.0\",\"id\":\"" + (i + 3) +
                    "\",\"method\":\"tools/call\",\"params\":{\"name\":\"codegraph_explore\"," +
                    "\"arguments\":{\"query\":\"" + escapeJson(query) + "\"}}}";
                stdin.write(exploreMsg);
                stdin.newLine();
                stdin.flush();

                String exploreResponse = readResponse(stdout, 30000);
                System.out.println("<<< codegraph_explore 响应 (前500字符):");
                System.out.println(truncate(exploreResponse, 500));
                System.out.println();

                extractStats(exploreResponse);
                System.out.println();
            }

            System.out.println("══════════════════════════════════════════════");
            System.out.println("  测试完成！");
            System.out.println("══════════════════════════════════════════════");

        } finally {
            stdin.close();
            process.waitFor(5, TimeUnit.SECONDS);
            process.destroyForcibly();
        }
    }

    private static String readResponse(BufferedReader stdout, long timeoutMs) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(stdout::readLine);
        try {
            String line = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            executor.shutdown();
            return line != null ? line : "(无响应)";
        } catch (TimeoutException e) {
            executor.shutdownNow();
            return "(超时 " + timeoutMs + "ms)";
        }
    }

    private static void printToolList(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            int toolsStart = json.indexOf("\"tools\"");
            if (toolsStart < 0) {
                System.out.println("  " + truncate(json, 200));
                return;
            }
            int bracketStart = json.indexOf('[', toolsStart);
            int bracketEnd = findMatchingBracket(json, bracketStart);
            if (bracketStart >= 0 && bracketEnd > bracketStart) {
                String toolsStr = json.substring(bracketStart, bracketEnd + 1);
                String[] tools = toolsStr.split("\\{\"name\"");
                for (int i = 1; i < tools.length; i++) {
                    String tool = tools[i];
                    int nameEnd = tool.indexOf('"', 1);
                    String name = nameEnd > 0 ? tool.substring(1, nameEnd) : "unknown";
                    int descStart = tool.indexOf("\"description\"");
                    if (descStart >= 0) {
                        int descValueStart = tool.indexOf('"', descStart + 14);
                        int descValueEnd = tool.indexOf('"', descValueStart + 1);
                        String desc = descValueEnd > 0 ? tool.substring(descValueStart + 1, descValueEnd) : "";
                        System.out.println("  " + i + ". " + name + " - " + truncate(desc, 60));
                    } else {
                        System.out.println("  " + i + ". " + name);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("  解析工具列表失败: " + e.getMessage());
        }
    }

    private static int findMatchingBracket(String str, int start) {
        int depth = 0;
        for (int i = start; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static void extractStats(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            if (json.contains("\"Exploration:")) {
                int start = json.indexOf("Found ");
                if (start >= 0) {
                    int end = json.indexOf("\\n", start);
                    if (end < 0) end = Math.min(start + 60, json.length());
                    System.out.println("  " + json.substring(start, end).replace("\\n", ""));
                }
            }
            if (json.contains("耗时=")) {
                int start = json.indexOf("耗时=");
                int end = json.indexOf("ms", start);
                if (end < 0) end = json.indexOf("\\n", start);
                if (end < 0) end = Math.min(start + 30, json.length());
                System.out.println("  " + json.substring(start, end + 2).replace("\\n", ""));
            }
        } catch (Exception ignored) {}
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "(null)";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
