package com.codegraph.installer;

import picocli.CommandLine;

import java.nio.file.*;
import java.util.*;

import com.codegraph.installer.target.*;

/**
 * install   —   CodeGraph MCP        AI    。
 * 对标 codegraph index.ts 的 runInstallerWithOptions()。
 */
@CommandLine.Command(
        name = "install",
        description = "Install CodeGraph MCP server config for AI assistants"
)
public class InstallCommand implements Runnable {

    @CommandLine.Option(names = {"--target"}, defaultValue = "all",
            description = "Target agents (comma-separated): claude, cursor, trae, all, auto")
    private String target;

    @CommandLine.Option(names = {"--global"}, defaultValue = "false",
            description = "Install globally (user-wide config)")
    private boolean globalInstall;

    @CommandLine.Option(names = {"-p", "--project"}, defaultValue = ".",
            description = "Project root directory")
    private String projectRoot;

    @CommandLine.Option(names = {"--print-config"}, defaultValue = "false",
            description = "Print config paths and status without making changes")
    private boolean printConfig;

    @Override
    public void run() {
        Path projectPath = Paths.get(projectRoot).toAbsolutePath().normalize();
        Location loc = globalInstall ? Location.GLOBAL : Location.LOCAL;

        List<AgentTarget> targets;
        try {
            targets = TargetRegistry.resolve(
                    Arrays.asList(target.split(",")), loc);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("Known targets: claude, cursor, trae, all, auto");
            return;
        }

        if (printConfig) {
            // --print-config 模式：只打印状态，不安装
            System.out.println("CodeGraph Config Status");
            System.out.println("  Location: " + loc);
            System.out.println();

            for (AgentTarget t : targets) {
                if (!t.supportsLocation(loc)) {
                    System.out.println("--- " + t.displayName()
                            + " (unsupported for " + loc + ") ---");
                    System.out.println();
                    continue;
                }
                System.out.println("--- " + t.displayName() + " ---");
                List<ConfigPathInfo> paths = t.describePaths(loc, projectPath);
                for (ConfigPathInfo info : paths) {
                    String status = info.configured
                            ? "configured"
                            : (info.exists ? "not configured" : "not found");
                    System.out.printf("  [%s] %s  (%s)%n",
                            status, info.path, info.description);
                }
                System.out.println();
            }
            return;
        }

        if (targets.isEmpty()) {
            System.out.println("No targets to install.");
            return;
        }

        String jarPath = findJarPath();

        System.out.println("CodeGraph Installer");
        System.out.println("  Location: " + loc);
        System.out.println("  Targets:  " + target);
        System.out.println("  Project:  " + projectPath);
        System.out.println("  JAR:      " + jarPath);
        System.out.println();

        for (AgentTarget t : targets) {
            System.out.println("--- " + t.displayName() + " ---");
            WriteResult result = t.install(loc, projectPath, jarPath);
            for (FileChange fc : result.files) {
                System.out.printf("  [%s] %s%n", fc.action, fc.path);
            }
            for (String note : result.notes) {
                System.out.println("  Note: " + note);
            }
        }

        System.out.println();
        System.out.println("Done! Restart your IDE/agent to use CodeGraph.");
    }

    /**
     * 查找 shaded JAR 文件路径。排除 original、sources、javadoc。
     */
    public static String findJarPath() {
        String userDir = System.getProperty("user.dir");
        Path targetDir = Paths.get(userDir, "target");
        if (Files.isDirectory(targetDir)) {
            try (DirectoryStream<Path> stream =
                         Files.newDirectoryStream(targetDir, "*.jar")) {
                for (Path jar : stream) {
                    String name = jar.getFileName().toString();
                    if (!name.contains("sources")
                            && !name.contains("javadoc")
                            && !name.contains("original")) {
                        return jar.toAbsolutePath().toString();
                    }
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        return "codegraph4j.jar";
    }
}
