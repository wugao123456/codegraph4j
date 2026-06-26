package com.codegraph.graph;

import com.codegraph.core.Edge;
import com.codegraph.core.Node;
import com.codegraph.core.types.EdgeKind;
import com.codegraph.core.types.NodeKind;
import com.codegraph.db.QueryBuilder;
import com.codegraph.graph.GraphTraverser.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 图查询管理器 — 在 GraphTraverser 之上提供高层业务查询。
 *
 * 包括：上下文获取、文件依赖分析、循环依赖检测、死代码查找、节点度量等。
 *
 * 对应 codegraph 项目中的 GraphQueryManager（graph/queries.ts）。
 */
public class GraphQueryManager {

    private static final Logger logger = LoggerFactory.getLogger(GraphQueryManager.class);

    private final QueryBuilder queries;
    private final GraphTraverser traverser;

    public GraphQueryManager(QueryBuilder queries) {
        this.queries = queries;
        this.traverser = new GraphTraverser(queries);
    }

    /**
     * 获取暴露的遍历器实例。
     */
    public GraphTraverser getTraverser() {
        return traverser;
    }

    // ========== 节点上下文 ==========

    /**
     * 获取节点的完整上下文信息。
     */
    public NodeContext getContext(String nodeId) {
        NodeContext context = new NodeContext();
        try {
            context.focal = queries.getNode(nodeId);
            if (context.focal == null) return context;

            // 祖先链
            context.ancestors = traverser.getAncestors(nodeId);

            // 直接子节点
            context.children = traverser.getChildren(nodeId);

            // 入边引用（排除 contains）
            List<Edge> incomingEdges = queries.getIncomingEdges(nodeId);
            for (Edge e : incomingEdges) {
                if (e.getKind() != EdgeKind.CONTAINS) {
                    Node source = queries.getNode(e.getSource());
                    if (source != null) {
                        context.incomingRefs.add(new ContextRef(source, e));
                    }
                }
            }

            // 出边引用（排除 contains）
            List<Edge> outgoingEdges = queries.getOutgoingEdges(nodeId);
            for (Edge e : outgoingEdges) {
                if (e.getKind() != EdgeKind.CONTAINS) {
                    Node target = queries.getNode(e.getTarget());
                    if (target != null) {
                        context.outgoingRefs.add(new ContextRef(target, e));
                    }
                }
            }

        } catch (SQLException e) {
            logger.error("[GraphQueryManager] getContext 失败: {}", e.getMessage());
        }
        return context;
    }

    // ========== 文件依赖分析 ==========

    /**
     * 获取某个文件的所有依赖文件。
     */
    public Set<String> getFileDependencies(String filePath) {
        try {
            return queries.getDependencyFilePaths(filePath,
                EdgeKind.CALLS, EdgeKind.REFERENCES, EdgeKind.EXTENDS,
                EdgeKind.IMPLEMENTS, EdgeKind.INSTANTIATES);
        } catch (SQLException e) {
            return Collections.emptySet();
        }
    }

    /**
     * 获取依赖某个文件的所有文件（反向依赖）。
     */
    public Set<String> getFileDependents(String filePath) {
        Set<String> dependents = new HashSet<>();
        try {
            List<Node> fileNodes = queries.getNodesInFile(filePath);
            if (fileNodes.isEmpty()) return dependents;

            Set<String> fileNodeIds = new HashSet<>();
            for (Node n : fileNodes) fileNodeIds.add(n.getId());

            // 查找所有入边指向本文件节点的外部文件
            for (Node n : fileNodes) {
                List<Edge> incomingEdges = queries.getIncomingEdges(n.getId());
                for (Edge e : incomingEdges) {
                    if (e.getKind() == EdgeKind.CONTAINS) continue;
                    if (!fileNodeIds.contains(e.getSource())) {
                        Node sourceNode = queries.getNode(e.getSource());
                        if (sourceNode != null) {
                            dependents.add(sourceNode.getFilePath());
                        }
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("[GraphQueryManager] getFileDependents 失败: {}", e.getMessage());
        }
        return dependents;
    }

    // ========== 循环依赖检测 ==========

    /**
     * 检测项目中的循环依赖（文件级）。
     */
    public List<List<String>> findCircularDependencies() {
        List<List<String>> cycles = new ArrayList<>();
        try {
            List<String> allFiles = queries.getAllFiles();
            // 构建文件依赖图
            Map<String, Set<String>> depGraph = new HashMap<>();
            for (String file : allFiles) {
                depGraph.put(file, getFileDependencies(file));
            }

            // DFS 检测环
            Set<String> visited = new HashSet<>();
            Set<String> recursionStack = new HashSet<>();
            List<String> path = new ArrayList<>();

            for (String file : allFiles) {
                if (!visited.contains(file)) {
                    detectCycleDFS(file, depGraph, visited, recursionStack, path, cycles);
                }
            }
        } catch (SQLException e) {
            logger.error("[GraphQueryManager] findCircularDependencies 失败: {}", e.getMessage());
        }
        return cycles;
    }

    private void detectCycleDFS(String current, Map<String, Set<String>> graph,
                                 Set<String> visited, Set<String> recursionStack,
                                 List<String> path, List<List<String>> cycles) {
        visited.add(current);
        recursionStack.add(current);
        path.add(current);

        Set<String> deps = graph.getOrDefault(current, Collections.emptySet());
        for (String dep : deps) {
            if (!visited.contains(dep)) {
                detectCycleDFS(dep, graph, visited, recursionStack, path, cycles);
            } else if (recursionStack.contains(dep)) {
                // 发现环
                int startIdx = path.indexOf(dep);
                if (startIdx >= 0) {
                    List<String> cycle = new ArrayList<>(path.subList(startIdx, path.size()));
                    cycle.add(dep); // 闭合环
                    cycles.add(cycle);
                }
            }
        }

        path.remove(path.size() - 1);
        recursionStack.remove(current);
    }

    // ========== 死代码检测 ==========

    /**
     * 查找可能的死代码（未被引用的函数、方法、类）。
     */
    public List<Node> findDeadCode(NodeKind... kinds) {
        if (kinds == null || kinds.length == 0) {
            kinds = new NodeKind[]{ NodeKind.METHOD, NodeKind.FUNCTION, NodeKind.CLASS };
        }

        List<Node> deadCode = new ArrayList<>();
        Set<NodeKind> targetKinds = new HashSet<>(Arrays.asList(kinds));

        try {
            List<Node> allNodes = queries.getAllNodes();
            for (Node node : allNodes) {
                if (!targetKinds.contains(node.getKind())) continue;
                if (node.isExported()) continue;

                // 检查除 contains 外是否有入边
                List<Edge> incoming = queries.getIncomingEdges(node.getId());
                boolean hasUsage = false;
                for (Edge e : incoming) {
                    if (e.getKind() != EdgeKind.CONTAINS) {
                        hasUsage = true;
                        break;
                    }
                }
                if (!hasUsage) {
                    deadCode.add(node);
                }
            }
        } catch (SQLException e) {
            logger.error("[GraphQueryManager] findDeadCode 失败: {}", e.getMessage());
        }

        return deadCode;
    }

    // ========== 节点度量 ==========

    /**
     * 获取节点的度量信息。
     */
    public NodeMetrics getNodeMetrics(String nodeId) {
        NodeMetrics metrics = new NodeMetrics();
        try {
            List<Edge> incoming = queries.getIncomingEdges(nodeId);
            List<Edge> outgoing = queries.getOutgoingEdges(nodeId);

            metrics.incomingEdgeCount = incoming.size();
            metrics.outgoingEdgeCount = outgoing.size();
            metrics.childCount = (int) outgoing.stream().filter(e -> e.getKind() == EdgeKind.CONTAINS).count();

            // 调用数量
            metrics.callCount = (int) outgoing.stream()
                .filter(e -> e.getKind() == EdgeKind.CALLS || e.getKind() == EdgeKind.INSTANTIATES)
                .count();

            metrics.callerCount = (int) incoming.stream()
                .filter(e -> e.getKind() == EdgeKind.CALLS || e.getKind() == EdgeKind.INSTANTIATES)
                .count();

            // 深度（祖先链长度）
            metrics.depth = traverser.getAncestors(nodeId).size();

            // 死代码判定
            boolean hasExternalUsage = false;
            for (Edge e : incoming) {
                if (e.getKind() != EdgeKind.CONTAINS) {
                    hasExternalUsage = true;
                    break;
                }
            }
            metrics.isDeadCode = !hasExternalUsage;

        } catch (SQLException e) {
            logger.error("[GraphQueryManager] getNodeMetrics 失败: {}", e.getMessage());
        }
        return metrics;
    }

    // ========== 查询 ==========

    /**
     * 按全限定名模式匹配查找节点（支持 * 和 ? 通配符）。
     */
    public List<Node> findByQualifiedName(String pattern) {
        List<Node> result = new ArrayList<>();
        try {
            String regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
            Pattern p = Pattern.compile("^" + regex + "$", Pattern.CASE_INSENSITIVE);

            List<Node> allNodes = queries.getAllNodes();
            for (Node node : allNodes) {
                if (node.getQualifiedName() != null && p.matcher(node.getQualifiedName()).matches()) {
                    result.add(node);
                }
            }
        } catch (SQLException e) {
            logger.error("[GraphQueryManager] findByQualifiedName 失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 获取项目模块结构（按目录组织文件）。
     */
    public Map<String, List<String>> getModuleStructure() {
        Map<String, List<String>> structure = new HashMap<>();
        try {
            List<String> allFiles = queries.getAllFiles();
            for (String file : allFiles) {
                String dir = getDirectory(file);
                structure.computeIfAbsent(dir, k -> new ArrayList<>()).add(file);
            }
        } catch (SQLException e) {
            logger.error("[GraphQueryManager] getModuleStructure 失败: {}", e.getMessage());
        }
        return structure;
    }

    /**
     * 获取文件中的导出符号。
     */
    public List<Node> getExportedSymbols(String filePath) {
        List<Node> exported = new ArrayList<>();
        try {
            List<Node> fileNodes = queries.getNodesInFile(filePath);
            for (Node node : fileNodes) {
                if (node.isExported()) {
                    exported.add(node);
                }
            }
        } catch (SQLException e) {
            logger.error("[GraphQueryManager] getExportedSymbols 失败: {}", e.getMessage());
        }
        return exported;
    }

    private String getDirectory(String filePath) {
        if (filePath == null) return "";
        int lastSep = filePath.lastIndexOf('/');
        return lastSep >= 0 ? filePath.substring(0, lastSep) : "";
    }

    // ========== 公共类型 ==========

    /** 节点上下文 */
    public static class NodeContext {
        public Node focal;
        public List<Node> ancestors = new ArrayList<>();
        public List<Node> children = new ArrayList<>();
        public List<ContextRef> incomingRefs = new ArrayList<>();
        public List<ContextRef> outgoingRefs = new ArrayList<>();
    }

    /** 上下文引用 */
    public static class ContextRef {
        public final Node node;
        public final Edge edge;
        public ContextRef(Node node, Edge edge) { this.node = node; this.edge = edge; }
    }

    /** 节点度量 */
    public static class NodeMetrics {
        public int incomingEdgeCount;
        public int outgoingEdgeCount;
        public int callCount;
        public int callerCount;
        public int childCount;
        public int depth;
        public boolean isDeadCode;
    }
}
