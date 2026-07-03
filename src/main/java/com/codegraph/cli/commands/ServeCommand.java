package com.codegraph.cli.commands;

import com.codegraph.config.CodeGraphConfig;
import com.codegraph.mcp.MCPServer;
import com.codegraph.utils.AppUtils;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

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

    @CommandLine.Mixin
    private ProjectOption projectOpt = new ProjectOption();

    @CommandLine.Option(names = {"--mcp"},
        description = "Run as MCP server (stdio transport)")
    private boolean mcp;

    @CommandLine.Option(names = {"--db-path"},
        description = "Database directory (default: {project}/.codegraph)")
    private String dbPath;

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

            String jarPath = AppUtils.findJarPath();
            System.out.println();
            System.out.println("  \"codegraph\": {");
            System.out.println("    \"command\": \"java\",");
            System.out.println("    \"args\": [\"-cp\", \"" + jarPath + "\",");
            System.out.println("             \"com.codegraph.cli.CodeGraphCli\",");
            System.out.println("             \"serve\", \"--mcp\",");
            System.out.println("             \"-p\", \"" + projectOpt.projectRoot + "\"]");
            System.out.println("  }");
            System.out.println();
            return;
        }

        // stdio 模式：移除控制台输出，避免污染 MCP 的 stdio 通道
        // 文件日志已在 MCPServer 构造函数中自动配置
        CodeGraphConfig config = new CodeGraphConfig(projectOpt.projectRoot, dbPath);
        MCPServer server = new MCPServer(config);
        server.detachConsole();

        logger.info("Starting MCP server in stdio mode for project: {}", projectOpt.projectRoot);

        server.start();
    }
}
