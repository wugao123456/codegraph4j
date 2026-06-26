package com.codegraph.cli.commands;

import com.codegraph.core.Edge;
import com.codegraph.core.Node;
import com.codegraph.core.types.EdgeKind;
import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;
import com.codegraph.graph.GraphTraverser;
import com.codegraph.graph.GraphTraverser.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;
import java.sql.SQLException;
import java.util.*;

/**
 * 图遍历命令 — 从指定节点出发执行 BFS 遍历并打印结果。
 *
 * 用法示例：
 *   codegraph traverse -n <node-id>                      # 默认 outgoing，深度不限
 *   codegraph traverse -n <node-id> -d 3                  # 最大深度 3
 *   codegraph traverse -n <node-id> --direction incoming  # 沿入边遍历
 *   codegraph traverse -n <node-id> --direction both      # 双向遍历
 *   codegraph traverse --search "Service"                 # 按名称搜索节点然后遍历
 */
@CommandLine.Command(
    name = "traverse",
    description = "BFS traverse the code graph from a node"
)
public class TraverseCommand implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(TraverseCommand.class);

    @CommandLine.Option(names = {"-p", "--project"},
        description = "Project root directory",
        defaultValue = ".")
    private String projectRoot;

    @CommandLine.Option(names = {"-n", "--node-id"},
        description = "Starting node ID for traversal")
    private String nodeId;

    @CommandLine.Option(names = {"--search"},
        description = "Search nodes by name (alternative to --node-id)")
    private String searchQuery;

    @CommandLine.Option(names = {"-d", "--depth"},
        description = "Maximum traversal depth (default: 3)",
        defaultValue = "3")
    private int maxDepth;

    @CommandLine.Option(names = {"--direction"},
        description = "Traversal direction: outgoing, incoming, both (default: outgoing)",
        defaultValue = "outgoing")
    private String direction;

    @CommandLine.Option(names = {"--limit"},
        description = "Maximum number of nodes to visit (default: 100)",
        defaultValue = "100")
    private int limit;

    @CommandLine.Option(names = {"--edge-kinds"},
        description = "Comma-separated edge kinds to follow (default: all). E.g. CONTAINS,CALLS",
        defaultValue = "")
    private String edgeKindsStr;

    @CommandLine.Option(names = {"--callers"},
        description = "Find callers of the node (instead of BFS traversal)")
    private boolean callersMode;

    @CommandLine.Option(names = {"--callees"},
        description = "Find callees of the node (instead of BFS traversal)")
    private boolean calleesMode;

    @CommandLine.Option(names = {"--impact"},
        description = "Compute impact radius of the node (instead of BFS traversal)")
    private boolean impactMode;

    @Override
    public void run() {
        File dbFile = new File(projectRoot, ".codegraph/codegraph4j.db");

        if (!dbFile.exists()) {
            System.err.println("CodeGraph not initialized. Run 'codegraph init' first.");
            return;
        }

        try (DatabaseConnection db = new DatabaseConnection(dbFile.getAbsolutePath())) {
            db.open();
            QueryBuilder queries = new QueryBuilder(db);
            GraphTraverser traverser = new GraphTraverser(queries);

            // 确定起始节点
            String startId = resolveStartNode(queries);
            if (startId == null) return;

            // 获取起始节点信息
            Node startNode = queries.getNode(startId);
            if (startNode == null) {
                System.err.println("Node not found: " + startId);
                return;
            }

            printHeader(startNode);

            if (callersMode) {
                executeCallers(traverser, startId);
            } else if (calleesMode) {
                executeCallees(traverser, startId);
            } else if (impactMode) {
                executeImpactRadius(traverser, startId);
            } else {
                executeBFS(traverser, startId);
            }

        } catch (SQLException e) {
            logger.error("Traversal failed", e);
            System.err.println("Error: " + e.getMessage());
        }
    }

    private String resolveStartNode(QueryBuilder queries) throws SQLException {
        if (nodeId != null && !nodeId.isEmpty()) {
            return nodeId;
        }

        if (searchQuery != null && !searchQuery.isEmpty()) {
            List<Node> results = queries.searchNodes(searchQuery);
            if (results.isEmpty()) {
                System.err.println("No nodes found matching: " + searchQuery);
                return null;
            }

            System.out.println("Found " + results.size() + " node(s) matching '" + searchQuery + "':");
            System.out.println();
            for (int i = 0; i < Math.min(results.size(), 20); i++) {
                Node n = results.get(i);
                System.out.printf("  [%d] %s  %s  %s  %s%n",
                    i + 1,
                    truncate(n.getId(), 16),
                    padRight(n.getKind().name(), 12),
                    padRight(n.getName(), 30),
                    n.getFilePath());
            }

            if (results.size() == 1) {
                nodeId = results.get(0).getId();
                System.out.println("\nAuto-selected the only match.");
                return nodeId;
            }

            System.out.println("\nUse --node-id <id> to specify which node to traverse.");
            return null;
        }

        System.err.println("Please specify --node-id <id> or --search <query>");
        return null;
    }

    // ========== BFS 遍历 ==========

    private void executeBFS(GraphTraverser traverser, String startId) {
        TraversalOptions options = new TraversalOptions();
        options.maxDepth = maxDepth;
        options.direction = direction;
        options.limit = limit;

        if (edgeKindsStr != null && !edgeKindsStr.isEmpty()) {
            Set<EdgeKind> kinds = new HashSet<>();
            for (String s : edgeKindsStr.split(",")) {
                try {
                    kinds.add(EdgeKind.valueOf(s.trim().toUpperCase()));
                } catch (IllegalArgumentException e) {
                    System.err.println("Warning: unknown edge kind '" + s.trim() + "', skipping");
                }
            }
            options.edgeKinds = kinds;
        }

        long startTime = System.currentTimeMillis();
        Subgraph subgraph = traverser.traverseBFS(startId, options);
        long elapsed = System.currentTimeMillis() - startTime;

        System.out.println();
        printSubgraph(subgraph);
        System.out.println();
        System.out.printf("Traversal completed: %d nodes, %d edges in %dms%n",
            subgraph.nodes.size(), subgraph.edges.size(), elapsed);
    }

    // ========== 调用者 ==========

    private void executeCallers(GraphTraverser traverser, String startId) {
        System.out.println("\n--- Callers (depth: " + maxDepth + ") ---");
        System.out.println();

        List<CallerInfo> callers = traverser.getCallers(startId, maxDepth);
        if (callers.isEmpty()) {
            System.out.println("No callers found.");
            return;
        }

        // 按深度分组打印
        Map<Integer, List<CallerInfo>> byDepth = new LinkedHashMap<>();
        // 简化展示：直接平铺
        for (CallerInfo c : callers) {
            System.out.printf("  %-12s  %-30s  [%s]  %s%n",
                c.edge.getKind().name(),
                c.node.getName(),
                truncate(c.node.getId(), 12),
                c.node.getFilePath());
        }
        System.out.println();
        System.out.println("Total callers: " + callers.size());
    }

    // ========== 被调用者 ==========

    private void executeCallees(GraphTraverser traverser, String startId) {
        System.out.println("\n--- Callees (depth: " + maxDepth + ") ---");
        System.out.println();

        List<CalleeInfo> callees = traverser.getCallees(startId, maxDepth);
        if (callees.isEmpty()) {
            System.out.println("No callees found.");
            return;
        }

        for (CalleeInfo c : callees) {
            System.out.printf("  %-12s  %-30s  [%s]  %s%n",
                c.edge.getKind().name(),
                c.node.getName(),
                truncate(c.node.getId(), 12),
                c.node.getFilePath());
        }
        System.out.println();
        System.out.println("Total callees: " + callees.size());
    }

    // ========== 影响范围 ==========

    private void executeImpactRadius(GraphTraverser traverser, String startId) {
        System.out.println("\n--- Impact Radius (depth: " + maxDepth + ") ---");
        System.out.println();

        Subgraph subgraph = traverser.getImpactRadius(startId, maxDepth);

        if (subgraph.nodes.isEmpty()) {
            System.out.println("No impacted nodes found.");
            return;
        }

        System.out.println("Nodes that would be affected by changes to this node:");
        System.out.println();

        // 构建缩进树
        printNodeTree(startId, subgraph, "", new HashSet<>());

        System.out.println();
        System.out.printf("Impact radius: %d nodes potentially affected%n",
            subgraph.nodes.size() - 1); // exclude self
    }

    // ========== 打印 ==========

    private void printHeader(Node startNode) {
        System.out.println("============================================================");
        System.out.println("  Traversal starting from:");
        System.out.printf("    ID:       %s%n", startNode.getId());
        System.out.printf("    Kind:     %s%n", startNode.getKind());
        System.out.printf("    Name:     %s%n", startNode.getName());
        System.out.printf("    QName:    %s%n", startNode.getQualifiedName());
        System.out.printf("    File:     %s%n", startNode.getFilePath());
        System.out.printf("    Language: %s%n", startNode.getLanguage());
        System.out.println("============================================================");
    }

    private void printSubgraph(Subgraph subgraph) {
        if (subgraph.nodes.isEmpty()) {
            System.out.println("No nodes found in traversal.");
            return;
        }

        System.out.println("Nodes:");
        System.out.println("------");

        // 按文件分组
        Map<String, List<Node>> byFile = new LinkedHashMap<>();
        for (Node n : subgraph.nodes.values()) {
            String file = n.getFilePath() != null ? n.getFilePath() : "(unknown)";
            byFile.computeIfAbsent(file, k -> new ArrayList<>()).add(n);
        }

        for (Map.Entry<String, List<Node>> entry : byFile.entrySet()) {
            System.out.println("  " + entry.getKey());
            for (Node n : entry.getValue()) {
                String marker = n.getId().equals(subgraph.rootId) ? " (*)" : "";
                System.out.printf("    %-14s  %-30s  %s%s%n",
                    n.getKind().name(),
                    n.getName(),
                    truncate(n.getId(), 16),
                    marker);
            }
        }

        if (!subgraph.edges.isEmpty()) {
            System.out.println();
            System.out.println("Edges (" + subgraph.edges.size() + "):");
            System.out.println("------");

            // 按类型分组
            Map<EdgeKind, List<Edge>> byKind = new LinkedHashMap<>();
            for (Edge e : subgraph.edges) {
                byKind.computeIfAbsent(e.getKind(), k -> new ArrayList<>()).add(e);
            }

            for (Map.Entry<EdgeKind, List<Edge>> entry : byKind.entrySet()) {
                System.out.printf("  %s: %d edges%n", entry.getKey().name(), entry.getValue().size());
            }
        }
    }

    private void printNodeTree(String nodeId, Subgraph subgraph, String indent, Set<String> visited) {
        if (visited.contains(nodeId)) return;
        visited.add(nodeId);

        Node node = subgraph.nodes.get(nodeId);
        if (node == null) return;

        String marker = nodeId.equals(subgraph.rootId) ? " [FOCAL]" : "";
        System.out.printf("%s%s (%s)%s%n", indent, node.getName(), node.getKind().name(), marker);

        // 找子节点（通过 CONTAINS 边）
        for (Edge e : subgraph.edges) {
            if (e.getSource().equals(nodeId) && e.getKind() == EdgeKind.CONTAINS) {
                printNodeTree(e.getTarget(), subgraph, indent + "  ", visited);
            }
        }
    }

    // ========== 工具方法 ==========

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private static String padRight(String s, int len) {
        if (s == null) s = "null";
        return s.length() >= len ? s : s + String.join("", Collections.nCopies(len - s.length(), " "));
    }
}
