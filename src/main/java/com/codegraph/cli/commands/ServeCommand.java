package com.codegraph.cli.commands;

import com.codegraph.mcp.MCPServer;
import org.slf4j.Logger;
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

    private static final Logger logger = LoggerFactory.getLogger(ServeCommand.class);

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

        // stdio 模式：启动 MCP 服务器
        logger.info("Starting MCP server in stdio mode for project: {}", projectRoot);

        MCPServer server = new MCPServer(projectRoot);
        server.start();
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
