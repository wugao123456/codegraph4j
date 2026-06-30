package com.codegraph.cli.commands;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import com.codegraph.mcp.MCPServer;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.nio.file.Paths;

/**
 * MCP 服务器启动命令。
 * 对标 Node.js codegraph 的 serve --mcp 命令。
 *
 * 用法：codegraph serve --mcp -p <project>
 *
 * 启动后通过 stdio 接收 JSON-RPC 2.0 请求，
 * 供 Claude Desktop、Cursor、opencode 等 AI 助手使用。
 */
@CommandLine.Command(
    name = "serve",
    description = "Start CodeGraph as an MCP server for AI assistants"
)
public class ServeCommand implements Runnable {

    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(ServeCommand.class);

    @CommandLine.Option(names = {"-p", "--project"},
        description = "Project root directory",
        defaultValue = ".")
    private String projectRoot;

    @CommandLine.Option(names = {"--mcp"},
        description = "Run as MCP server (stdio transport)")
    private boolean mcp;

    @Override
    public void run() {
        if (!mcp) {
            System.out.println("Currently only MCP mode (--mcp) is supported.");
            return;
        }

        // 检测是否为交互式终端（人类误运行）
        // System.console() 在 stdin 被管道/重定向时返回 null
        if (System.console() != null) {
            System.out.println("===============================================");
            System.out.println("CodeGraph MCP Server");
            System.out.println("===============================================");
            System.out.println();
            System.out.println("This command is intended to be run by AI assistants");
            System.out.println("(Claude Desktop, Cursor, opencode, Codex, etc.) via MCP protocol.");
            System.out.println();
            System.out.println("MCP client configuration:");

            String jarPath = findJarPath();
            System.out.println();
            System.out.println("  \"codegraph\": {");
            System.out.println("    \"command\": \"java\",");
            System.out.println("    \"args\": [\"-cp\", \"" + jarPath + "\",");
            System.out.println("             \"com.codegraph.cli.CodeGraphCli\",");
            System.out.println("             \"serve\", \"--mcp\",");
            System.out.println("             \"-p\", \"" + projectRoot + "\"]");
            System.out.println("  }");
            System.out.println();
            return;
        }

        // stdio 模式：将日志重定向到文件，避免污染 MCP 的 stdio 通道
        redirectLogsToFile();

        logger.info("Starting MCP server in stdio mode for project: {}", projectRoot);

        MCPServer server = new MCPServer(projectRoot);
        server.start();
    }

    /**
     * 将 logback 日志输出重定向到文件。
     * MCP 协议通过 stdio 传输 JSON-RPC，stderr 上的任何输出都会被
     * MCP 客户端视为错误并断开连接。
     */
    @SuppressWarnings("unchecked")
    private void redirectLogsToFile() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);

        // 日志文件路径：<project>/.codegraph/codegraph4j-mcp.log
        String logPath = Paths.get(projectRoot, ".codegraph", "codegraph4j-mcp.log").toString();

        // 创建文件 appender
        ch.qos.logback.core.FileAppender fileAppender = new ch.qos.logback.core.FileAppender();
        fileAppender.setName("MCP_FILE");
        fileAppender.setContext(context);
        fileAppender.setFile(logPath);
        fileAppender.setAppend(true);

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
        encoder.start();

        fileAppender.setEncoder(encoder);
        fileAppender.start();

        // 移除所有 ConsoleAppender，防止日志污染 MCP stdio
        rootLogger.detachAppender("STDOUT");
        context.getLogger("com.codegraph").detachAppender("STDOUT");
        context.getLogger("com.codegraph.parser").detachAppender("STDOUT");
        context.getLogger("com.codegraph.db").detachAppender("STDOUT");
        context.getLogger("com.codegraph.cli").detachAppender("STDOUT");

        // 为 com.codegraph 日志添加文件输出
        ch.qos.logback.classic.Logger codeGraphLogger = context.getLogger("com.codegraph");
        codeGraphLogger.addAppender(fileAppender);
        codeGraphLogger.setAdditive(false);

        rootLogger.addAppender(fileAppender);
    }

    private String findJarPath() {
        // 尝试查找 jar 文件
        String userDir = System.getProperty("user.dir");
        java.io.File targetDir = new java.io.File(userDir, "target");
        if (targetDir.exists()) {
            java.io.File[] jars = targetDir.listFiles(
                (dir, name) -> name.endsWith(".jar") && !name.contains("sources") && !name.contains("javadoc"));
            if (jars != null && jars.length > 0) {
                return jars[0].getAbsolutePath();
            }
        }
        return "codegraph4j.jar";
    }
}
