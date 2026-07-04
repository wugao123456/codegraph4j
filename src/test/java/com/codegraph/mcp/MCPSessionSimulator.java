package com.codegraph.mcp;


import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * MCP 会话模拟器 — 模拟 Trae 等 AI 助手向 codegraph4j MCP 服务发送 codegraph_explore 请求。
 *
 * 完整模拟 Trae 的交互流程：
 * 1. spawn MCP 子进程 (java ... serve --mcp)
 * 2. 发送 initialize → 获取 capabilities
 * 3. 发送 tools/call codegraph_explore → 获取源码分析结果
 * 4. 关闭 stdin → 进程优雅退出
 *
 * 运行方式：
 *   cd codegraph4j && mvn compile -q
 *   mvn exec:java -Dexec.mainClass="com.codegraph.mcp.MCPSessionSimulator" \
 *     -Dexec.args="query=MCPServer" -q
 *
 *   或指定多个查询：
 *   mvn exec:java -Dexec.mainClass="com.codegraph.mcp.MCPSessionSimulator" \
 *     -Dexec.args="query=MCPServer query=MCPToolHandler handleExplore" -q
 */
public class MCPSessionSimulator {

    private static final String PROJECT_PATH = "/Users/wugao-pc/Desktop/Project/codegraph4j";
    private static final String CLASSPATH;

    static {
        // 构建 classpath：target/classes + Maven 依赖
        String cp = "target/classes";
        try {
            Process p = new ProcessBuilder(
                "mvn", "-q", "dependency:build-classpath", "-Dmdep.outputFile=/dev/stdout"
            ).directory(new java.io.File(PROJECT_PATH)).start();
            String deps = new BufferedReader(new InputStreamReader(p.getInputStream())).readLine();
            if (deps != null && !deps.isEmpty()) {
                cp += ":" + deps;
            }
            p.waitFor();
        } catch (Exception e) {
            System.err.println("⚠ 无法获取 Maven classpath，仅使用 target/classes");
        }
        CLASSPATH = cp;
    }

    public static void main(String[] args) throws Exception {
        // 解析查询参数
        List<String> queries = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("query=")) {
                queries.add(arg.substring(6));
            } else {
                queries.add(arg);
            }
        }
        if (queries.isEmpty()) {
            queries.add("MCPServer codegraph_explore"); // 默认查询
        }

        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║   CodeGraph MCP Session Simulator (Trae)    ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println("项目路径: " + PROJECT_PATH);
        System.out.println("查询内容: " + queries);
        System.out.println("CLASSPATH: " + (CLASSPATH.length() > 100 ? CLASSPATH.substring(0, 100) + "..." : CLASSPATH));
        System.out.println();

        // 启动 MCP 子进程
        ProcessBuilder pb = new ProcessBuilder(
            "java", "-cp", CLASSPATH,
            "com.codegraph.cli.CodeGraphCli", "serve", "--mcp",
            "-p", PROJECT_PATH
        );
        pb.directory(new java.io.File(PROJECT_PATH));
        pb.redirectErrorStream(false);
        Process process = pb.start();

        BufferedReader stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
        BufferedWriter stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), "UTF-8"));
        BufferedReader stderr = new BufferedReader(new InputStreamReader(process.getErrorStream(), "UTF-8"));

        // 后台线程读取 stderr（日志输出）
        Thread stderrReader = new Thread(() -> {
            try {
                String line;
                while ((line = stderr.readLine()) != null) {
                    // 静默处理，日志已写入文件
                }
            } catch (IOException ignored) {}
        }, "stderr-reader");
        stderrReader.setDaemon(true);
        stderrReader.start();

        try {
            // ---- 第1步: initialize ----
            System.out.println(">>> [1/3] 发送 initialize...");
            String initMsg = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"initialize\"," +
                "\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{}," +
                "\"clientInfo\":{\"name\":\"trae\",\"version\":\"1.0\"}}}";
            stdin.write(initMsg);
            stdin.newLine();
            stdin.flush();

            String initResponse = readResponse(stdout, 5000);
            System.out.println("<<< initialize 响应 (前100000字符):");
            System.out.println("    " + truncate(initResponse, 100000));
            System.out.println();

            // ---- 第2步: 发送 tools/list ----
            System.out.println(">>> [2/3] 发送 tools/list...");
            stdin.write("{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"method\":\"tools/list\",\"params\":{}}");
            stdin.newLine();
            stdin.flush();

            String listResponse = readResponse(stdout, 5000);
            System.out.println("<<< tools/list 响应 (完整):");
            System.out.println(listResponse);
            System.out.println();

            // ---- 第3步: 逐个发送 codegraph_explore 请求 ----
            for (int i = 0; i < queries.size(); i++) {
                String query = queries.get(i);
                System.out.println(">>> [" + (i + 3) + "/" + (queries.size() + 2) +
                    "] 发送 tools/call codegraph_explore query=\"" + query + "\"...");

                String exploreMsg = "{\"jsonrpc\":\"2.0\",\"id\":\"" + (i + 2) +
                    "\",\"method\":\"tools/call\",\"params\":{\"name\":\"codegraph_explore\"," +
                    "\"arguments\":{\"query\":\"" + escapeJson(query) + "\"}}}";
                stdin.write(exploreMsg);
                stdin.newLine();
                stdin.flush();

                String exploreResponse = readResponse(stdout, 30000);
                System.out.println("<<< codegraph_explore 响应 (前300字符):");
                System.out.println("    " + truncate(exploreResponse, 300));
                System.out.println();

                // 从响应中提取统计信息
                extractStats(exploreResponse);
                System.out.println();
            }

            System.out.println("══════════════════════════════════════════════");
            System.out.println("  测试完成！查看详细日志:");
            System.out.println("  tail -20 " + PROJECT_PATH + "/.codegraph/logs/codegraph4j-mcp.log");
            System.out.println("══════════════════════════════════════════════");

        } finally {
            // 关闭 stdin，让进程优雅退出
            stdin.close();
            process.waitFor(5, TimeUnit.SECONDS);
            process.destroyForcibly();
        }
    }

    /**
     * 从 stdout 读取一条 JSON-RPC 响应（一行一个 JSON 对象）。
     */
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

    /**
     * 从响应中提取符号数、文件数等统计信息。
     */
    private static void extractStats(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            // 简单提取爆炸半径信息
            if (json.contains("\"Exploration:")) {
                int start = json.indexOf("Found ");
                if (start >= 0) {
                    int end = json.indexOf("\\n", start);
                    if (end < 0) end = Math.min(start + 60, json.length());
                    System.out.println("  " + json.substring(start, end).replace("\\n", ""));
                }
            }
            // 提取耗时
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
