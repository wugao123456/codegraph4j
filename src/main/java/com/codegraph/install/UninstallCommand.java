package com.codegraph.install;

import com.codegraph.install.target.*;
import picocli.CommandLine;

import java.util.*;

/**
 * uninstall   —   CodeGraph MCP     AI      。
 * 对标 codegraph index.ts 的 runUninstaller()。
 */
@CommandLine.Command(
        name = "uninstall",
        description = "Uninstall CodeGraph MCP config from AI assistants"
)
public class UninstallCommand implements Runnable {

    @CommandLine.Option(names = {"--target"}, defaultValue = "all",
            description = "Target agents (comma-separated): claude, cursor, trae, all")
    private String target;

    @Override
    public void run() {
        Location loc = Location.GLOBAL;

        List<AgentTarget> targets;
        try {
            targets = TargetRegistry.resolve(
                    Arrays.asList(target.split(",")), loc);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("Known targets: claude, cursor, trae, all");
            return;
        }

        if (targets.isEmpty()) {
            System.out.println("No targets to uninstall.");
            return;
        }

        for (AgentTarget t : targets) {
            System.out.println("--- " + t.displayName() + " ---");
            WriteResult result = t.uninstall(loc);
            for (FileChange fc : result.files) {
                System.out.printf("  [%s] %s%n", fc.action, fc.path);
            }
            for (String note : result.notes) {
                System.out.println("  Note: " + note);
            }
        }

        System.out.println();
        System.out.println("Done!");
    }
}
