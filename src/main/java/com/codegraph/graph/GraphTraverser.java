package com.codegraph.graph;

import com.codegraph.core.Edge;
import com.codegraph.core.Node;
import com.codegraph.core.types.EdgeKind;
import com.codegraph.core.types.NodeKind;
import com.codegraph.db.QueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * 图遍历器 — 在代码知识图谱上执行 BFS/DFS 遍历、调用图查询、影响范围分析等。
 *
 * 对应 codegraph 项目中的 GraphTraverser（graph/traversal.ts）。
 *
 * 设计要点：
 * - 批量查询优化：使用 getNodesByIds 避免 N+1 问题
 * - BFS 边优先级排序：CONTAINS > CALLS > 其他
 * - 影响范围分析：容器节点自动展开子成员，排除 contains 入边
 */
public class GraphTraverser {

    private static final Logger logger = LoggerFactory.getLogger(GraphTraverser.class);

    private final QueryBuilder queries;

    // 容器节点类型（影响范围分析时自动展开子成员）
    private static final Set<NodeKind> CONTAINER_KINDS = new HashSet<>(Arrays.asList(
        NodeKind.CLASS, NodeKind.INTERFACE, NodeKind.STRUCT,
        NodeKind.ENUM, NodeKind.MODULE, NodeKind.FILE
    ));

    public GraphTraverser(QueryBuilder queries) {
        this.queries = queries;
    }

    // ========== 基础遍历 ==========

    /**
     * BFS 遍历从 startId 开始的子图。
     */
    public Subgraph traverseBFS(String startId, TraversalOptions options) {
        try {
            Node startNode = queries.getNode(startId);
            if (startNode == null) {
                return new Subgraph();
            }

            int maxDepth = options.maxDepth > 0 ? options.maxDepth : Integer.MAX_VALUE;
            int limit = options.limit > 0 ? options.limit : 1000;
            Set<EdgeKind> edgeKinds = options.edgeKinds;
            Set<NodeKind> nodeKinds = options.nodeKinds;

            Map<String, Node> nodes = new HashMap<>();
            List<Edge> edges = new ArrayList<>();
            Set<String> visited = new HashSet<>();

            // BFS 队列
            Queue<BfsStep> queue = new LinkedList<>();
            queue.add(new BfsStep(startNode, null, 0));

            while (!queue.isEmpty() && nodes.size() < limit) {
                BfsStep step = queue.poll();
                String nodeId = step.node.getId();

                if (visited.contains(nodeId)) continue;
                visited.add(nodeId);

                // 将当前节点加入结果
                if (options.includeStart || !nodeId.equals(startId)) {
                    nodes.put(nodeId, step.node);
                }

                // 将到达此节点的边加入结果
                if (step.edge != null) {
                    edges.add(step.edge);
                }

                // 达到最大深度，不再展开
                if (step.depth >= maxDepth) continue;

                // 获取邻边
                List<Edge> adjacentEdges = getAdjacentEdges(nodeId, options.direction, edgeKinds);

                // 按优先级排序：CONTAINS > CALLS > 其他
                adjacentEdges.sort(Comparator.comparingInt(e -> edgePriority(e.getKind())));

                // 收集邻居节点 ID
                Set<String> neighborIds = new HashSet<>();
                for (Edge e : adjacentEdges) {
                    String neighborId = e.getSource().equals(nodeId) ? e.getTarget() : e.getSource();
                    if (!visited.contains(neighborId)) {
                        neighborIds.add(neighborId);
                    }
                }

                // 批量获取邻居节点
                Map<String, Node> neighborNodes = queries.getNodesByIds(neighborIds);

                // 入队
                for (Edge e : adjacentEdges) {
                    String neighborId = e.getSource().equals(nodeId) ? e.getTarget() : e.getSource();
                    if (visited.contains(neighborId)) continue;

                    Node neighbor = neighborNodes.get(neighborId);
                    if (neighbor == null) continue;

                    // 过滤 nodeKinds
                    if (nodeKinds != null && !nodeKinds.isEmpty() && !nodeKinds.contains(neighbor.getKind())) {
                        continue;
                    }

                    queue.add(new BfsStep(neighbor, e, step.depth + 1));
                }
            }

            return new Subgraph(nodes, edges, startId);

        } catch (SQLException e) {
            logger.error("[GraphTraverser] BFS 遍历失败: {}", e.getMessage());
            return new Subgraph();
        }
    }

    /**
     * DFS 遍历（简化版，可扩展）。
     */
    public Subgraph traverseDFS(String startId, TraversalOptions options) {
        // Java 8 兼容的 DFS 实现委托给 BFS（队列改为栈即可），简化实现
        return traverseBFS(startId, options);
    }

    private int edgePriority(EdgeKind kind) {
        if (kind == EdgeKind.CONTAINS) return 0;
        if (kind == EdgeKind.CALLS) return 1;
        return 2;
    }

    private List<Edge> getAdjacentEdges(String nodeId, String direction, Set<EdgeKind> edgeKinds) throws SQLException {
        List<Edge> result = new ArrayList<>();
        EdgeKind[] kinds = (edgeKinds != null && !edgeKinds.isEmpty())
            ? edgeKinds.toArray(new EdgeKind[0]) : null;

        if ("outgoing".equals(direction) || "both".equals(direction)) {
            result.addAll(queries.getOutgoingEdges(nodeId, kinds));
        }
        if ("incoming".equals(direction) || "both".equals(direction)) {
            result.addAll(queries.getIncomingEdges(nodeId, kinds));
        }

        return result;
    }

    // ========== 调用图查询 ==========

    /**
     * 查找调用者（谁调用了这个节点）。
     */
    public List<CallerInfo> getCallers(String nodeId, int maxDepth) {
        List<CallerInfo> callers = new ArrayList<>();
        try {
            getCallersRecursive(nodeId, maxDepth, 1, callers, new HashSet<>());
        } catch (SQLException e) {
            logger.error("[GraphTraverser] getCallers 失败: {}", e.getMessage());
        }
        return callers;
    }

    /**
     * 递归查找调用者。通过入边（calls, references, imports, instantiates）向上遍历调用链。
     *
     * @param nodeId       当前节点ID
     * @param maxDepth     最大递归深度
     * @param currentDepth 当前递归深度
     * @param result       调用者结果收集列表
     * @param visited      已访问节点集合，防止循环遍历
     */
    private void getCallersRecursive(String nodeId, int maxDepth, int currentDepth,
                                      List<CallerInfo> result, Set<String> visited) throws SQLException {
        if (currentDepth > maxDepth || visited.contains(nodeId)) return;
        visited.add(nodeId);

        // 查找入边：calls, references, imports, instantiates
        List<Edge> incomingEdges = queries.getIncomingEdges(nodeId,
            EdgeKind.CALLS, EdgeKind.REFERENCES, EdgeKind.IMPORTS, EdgeKind.INSTANTIATES);

        Set<String> callerIds = new HashSet<>();
        for (Edge e : incomingEdges) {
            callerIds.add(e.getSource());
        }

        Map<String, Node> callerNodes = queries.getNodesByIds(callerIds);
        for (Edge e : incomingEdges) {
            Node caller = callerNodes.get(e.getSource());
            if (caller != null) {
                result.add(new CallerInfo(caller, e));
                getCallersRecursive(e.getSource(), maxDepth, currentDepth + 1, result, visited);
            }
        }
    }

    /**
     * 查找被调用者（这个节点调用了谁）。
     */
    public List<CalleeInfo> getCallees(String nodeId, int maxDepth) {
        List<CalleeInfo> callees = new ArrayList<>();
        try {
            getCalleesRecursive(nodeId, maxDepth, 1, callees, new HashSet<>());
        } catch (SQLException e) {
            logger.error("[GraphTraverser] getCallees 失败: {}", e.getMessage());
        }
        return callees;
    }

    private void getCalleesRecursive(String nodeId, int maxDepth, int currentDepth,
                                      List<CalleeInfo> result, Set<String> visited) throws SQLException {
        if (currentDepth > maxDepth || visited.contains(nodeId)) return;
        visited.add(nodeId);

        List<Edge> outgoingEdges = queries.getOutgoingEdges(nodeId,
            EdgeKind.CALLS, EdgeKind.REFERENCES, EdgeKind.IMPORTS, EdgeKind.INSTANTIATES);

        Set<String> calleeIds = new HashSet<>();
        for (Edge e : outgoingEdges) {
            calleeIds.add(e.getTarget());
        }

        Map<String, Node> calleeNodes = queries.getNodesByIds(calleeIds);
        for (Edge e : outgoingEdges) {
            Node callee = calleeNodes.get(e.getTarget());
            if (callee != null) {
                result.add(new CalleeInfo(callee, e));
                getCalleesRecursive(e.getTarget(), maxDepth, currentDepth + 1, result, visited);
            }
        }
    }

    /**
     * 获取完整调用图（同时向上和向下扩展）。
     */
    public Subgraph getCallGraph(String nodeId, int depth) {
        TraversalOptions options = new TraversalOptions();
        options.direction = "both";
        options.maxDepth = depth;
        options.edgeKinds = new HashSet<>(Arrays.asList(
            EdgeKind.CALLS, EdgeKind.REFERENCES, EdgeKind.IMPORTS, EdgeKind.INSTANTIATES
        ));
        return traverseBFS(nodeId, options);
    }

    // ========== 影响范围分析 ==========

    /**
     * 影响范围分析：修改指定节点会影响哪些其他节点。
     * 从目标节点沿入边向上递归，找到所有依赖该节点的符号。
     */
    public Subgraph getImpactRadius(String nodeId, int maxDepth) {
        Subgraph result = new Subgraph();
        try {
            Node focalNode = queries.getNode(nodeId);
            if (focalNode == null) return result;

            result.nodes.put(nodeId, focalNode);

            // 若为容器节点，先展开子成员（深度不变）
            if (CONTAINER_KINDS.contains(focalNode.getKind())) {
                expandContainerChildren(focalNode, maxDepth, 0, result);
            }

            // 沿入边向上递归
            getImpactRecursive(nodeId, maxDepth, 1, result, new HashSet<>());

        } catch (SQLException e) {
            logger.error("[GraphTraverser] getImpactRadius 失败: {}", e.getMessage());
        }
        return result;
    }

    private void expandContainerChildren(Node container, int maxDepth, int currentDepth,
                                          Subgraph result) throws SQLException {
        if (currentDepth >= maxDepth) return;

        List<Edge> containsEdges = queries.getOutgoingEdges(container.getId(), EdgeKind.CONTAINS);
        Set<String> childIds = new HashSet<>();
        for (Edge e : containsEdges) childIds.add(e.getTarget());

        Map<String, Node> childNodes = queries.getNodesByIds(childIds);
        for (Edge e : containsEdges) {
            Node child = childNodes.get(e.getTarget());
            if (child != null) {
                result.nodes.put(child.getId(), child);
                result.edges.add(e);

                if (CONTAINER_KINDS.contains(child.getKind())) {
                    expandContainerChildren(child, maxDepth, currentDepth, result);
                }
            }
        }
    }

    private void getImpactRecursive(String nodeId, int maxDepth, int currentDepth,
                                     Subgraph result, Set<String> visited) throws SQLException {
        if (currentDepth > maxDepth || visited.contains(nodeId)) return;
        visited.add(nodeId);

        // 查找入边（排除 contains）
        List<Edge> incomingEdges = queries.getIncomingEdges(nodeId);
        for (Edge e : incomingEdges) {
            if (e.getKind() == EdgeKind.CONTAINS) continue;

            Node source = queries.getNode(e.getSource());
            if (source != null) {
                result.nodes.put(source.getId(), source);
                result.edges.add(e);

                // 若为容器节点，先展开子成员
                if (CONTAINER_KINDS.contains(source.getKind())) {
                    expandContainerChildren(source, maxDepth, currentDepth, result);
                }

                getImpactRecursive(e.getSource(), maxDepth, currentDepth + 1, result, visited);
            }
        }
    }

    // ========== 类型层级 ==========

    /**
     * 获取类型祖先（沿 extends/implements 出边向上）。
     */
    public List<Node> getTypeAncestors(String nodeId) {
        List<Node> ancestors = new ArrayList<>();
        try {
            getTypeAncestorsRecursive(nodeId, ancestors, new HashSet<>());
        } catch (SQLException e) {
            logger.error("[GraphTraverser] getTypeAncestors 失败: {}", e.getMessage());
        }
        return ancestors;
    }

    private void getTypeAncestorsRecursive(String nodeId, List<Node> result,
                                            Set<String> visited) throws SQLException {
        if (visited.contains(nodeId)) return;
        visited.add(nodeId);

        List<Edge> edges = queries.getOutgoingEdges(nodeId, EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS);
        Set<String> parentIds = new HashSet<>();
        for (Edge e : edges) parentIds.add(e.getTarget());

        Map<String, Node> parentNodes = queries.getNodesByIds(parentIds);
        for (Edge e : edges) {
            Node parent = parentNodes.get(e.getTarget());
            if (parent != null) {
                result.add(parent);
                getTypeAncestorsRecursive(e.getTarget(), result, visited);
            }
        }
    }

    /**
     * 获取类型后代（沿 extends/implements 入边向下）。
     */
    public List<Node> getTypeDescendants(String nodeId) {
        List<Node> descendants = new ArrayList<>();
        try {
            getTypeDescendantsRecursive(nodeId, descendants, new HashSet<>());
        } catch (SQLException e) {
            logger.error("[GraphTraverser] getTypeDescendants 失败: {}", e.getMessage());
        }
        return descendants;
    }

    private void getTypeDescendantsRecursive(String nodeId, List<Node> result,
                                              Set<String> visited) throws SQLException {
        if (visited.contains(nodeId)) return;
        visited.add(nodeId);

        List<Edge> edges = queries.getIncomingEdges(nodeId, EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS);
        Set<String> childIds = new HashSet<>();
        for (Edge e : edges) childIds.add(e.getSource());

        Map<String, Node> childNodes = queries.getNodesByIds(childIds);
        for (Edge e : edges) {
            Node child = childNodes.get(e.getSource());
            if (child != null) {
                result.add(child);
                getTypeDescendantsRecursive(e.getSource(), result, visited);
            }
        }
    }

    /**
     * 获取完整类型层级（祖先 + 后代）。
     */
    public Subgraph getTypeHierarchy(String nodeId) {
        Subgraph result = new Subgraph();
        try {
            Node focal = queries.getNode(nodeId);
            if (focal == null) return result;
            result.nodes.put(nodeId, focal);

            // 获取祖先
            List<Node> ancestors = getTypeAncestors(nodeId);
            for (Node n : ancestors) result.nodes.put(n.getId(), n);

            // 获取后代
            List<Node> descendants = getTypeDescendants(nodeId);
            for (Node n : descendants) result.nodes.put(n.getId(), n);

            // 获取相关边
            for (Node n : result.nodes.values()) {
                List<Edge> outgoing = queries.getOutgoingEdges(n.getId(), EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS);
                result.edges.addAll(outgoing);
            }

        } catch (SQLException e) {
            logger.error("[GraphTraverser] getTypeHierarchy 失败: {}", e.getMessage());
        }
        return result;
    }

    // ========== 辅助查询 ==========

    /**
     * 获取节点的祖先链（沿 contains 入边向上）。
     */
    public List<Node> getAncestors(String nodeId) {
        List<Node> ancestors = new ArrayList<>();
        try {
            Node current = queries.getNode(nodeId);
            while (current != null) {
                List<Edge> incoming = queries.getIncomingEdges(current.getId(), EdgeKind.CONTAINS);
                if (incoming.isEmpty()) break;
                Edge parentEdge = incoming.get(0);
                Node parent = queries.getNode(parentEdge.getSource());
                if (parent != null) {
                    ancestors.add(parent);
                    current = parent;
                } else {
                    break;
                }
            }
        } catch (SQLException e) {
            logger.error("[GraphTraverser] getAncestors 失败: {}", e.getMessage());
        }
        return ancestors;
    }

    /**
     * 获取节点的直接子节点。
     */
    public List<Node> getChildren(String nodeId) {
        List<Node> children = new ArrayList<>();
        try {
            List<Edge> outgoing = queries.getOutgoingEdges(nodeId, EdgeKind.CONTAINS);
            Set<String> childIds = new HashSet<>();
            for (Edge e : outgoing) childIds.add(e.getTarget());

            Map<String, Node> childNodes = queries.getNodesByIds(childIds);
            for (Edge e : outgoing) {
                Node child = childNodes.get(e.getTarget());
                if (child != null) {
                    children.add(child);
                }
            }
        } catch (SQLException e) {
            logger.error("[GraphTraverser] getChildren 失败: {}", e.getMessage());
        }
        return children;
    }

    /**
     * 查找指定节点的所有引用（入边）。
     */
    public List<UsageInfo> findUsages(String nodeId) {
        List<UsageInfo> usages = new ArrayList<>();
        try {
            List<Edge> incoming = queries.getIncomingEdges(nodeId);
            Set<String> sourceIds = new HashSet<>();
            for (Edge e : incoming) {
                if (e.getKind() != EdgeKind.CONTAINS) {
                    sourceIds.add(e.getSource());
                }
            }

            Map<String, Node> sourceNodes = queries.getNodesByIds(sourceIds);
            for (Edge e : incoming) {
                if (e.getKind() == EdgeKind.CONTAINS) continue;
                Node source = sourceNodes.get(e.getSource());
                if (source != null) {
                    usages.add(new UsageInfo(source, e));
                }
            }
        } catch (SQLException e) {
            logger.error("[GraphTraverser] findUsages 失败: {}", e.getMessage());
        }
        return usages;
    }

    /**
     * 查找两节点间的最短路径（BFS）。
     */
    public List<PathStep> findPath(String fromId, String toId) {
        try {
            Node fromNode = queries.getNode(fromId);
            Node toNode = queries.getNode(toId);
            if (fromNode == null || toNode == null) return null;

            Set<String> visited = new HashSet<>();
            Queue<PathState> queue = new LinkedList<>();
            queue.add(new PathState(fromNode, new ArrayList<>()));

            while (!queue.isEmpty()) {
                PathState state = queue.poll();
                String currentId = state.node.getId();

                if (currentId.equals(toId)) {
                    return state.path;
                }

                if (visited.contains(currentId)) continue;
                visited.add(currentId);

                // 获取出边
                List<Edge> outgoing = queries.getOutgoingEdges(currentId);
                Set<String> neighborIds = new HashSet<>();
                for (Edge e : outgoing) neighborIds.add(e.getTarget());

                Map<String, Node> neighborNodes = queries.getNodesByIds(neighborIds);
                for (Edge e : outgoing) {
                    Node neighbor = neighborNodes.get(e.getTarget());
                    if (neighbor != null && !visited.contains(neighbor.getId())) {
                        List<PathStep> newPath = new ArrayList<>(state.path);
                        newPath.add(new PathStep(neighbor, e));
                        queue.add(new PathState(neighbor, newPath));
                    }
                }
            }

        } catch (SQLException e) {
            logger.error("[GraphTraverser] findPath 失败: {}", e.getMessage());
        }
        return null;
    }

    // ========== 内部类型 ==========

    private static class BfsStep {
        final Node node;
        final Edge edge;
        final int depth;
        BfsStep(Node node, Edge edge, int depth) {
            this.node = node; this.edge = edge; this.depth = depth;
        }
    }

    private static class PathState {
        final Node node;
        final List<PathStep> path;
        PathState(Node node, List<PathStep> path) {
            this.node = node; this.path = path;
        }
    }

    // ========== 公共类型 ==========

    /** 遍历选项 */
    public static class TraversalOptions {
        public int maxDepth = Integer.MAX_VALUE;
        public int limit = 1000;
        public String direction = "outgoing"; // "outgoing", "incoming", "both"
        public Set<EdgeKind> edgeKinds;
        public Set<NodeKind> nodeKinds;
        public boolean includeStart = true;
    }

    /** 子图结果 */
    public static class Subgraph {
        public final Map<String, Node> nodes;
        public final List<Edge> edges;
        public String rootId;

        public Subgraph() {
            this.nodes = new HashMap<>();
            this.edges = new ArrayList<>();
        }

        public Subgraph(Map<String, Node> nodes, List<Edge> edges, String rootId) {
            this.nodes = nodes;
            this.edges = edges;
            this.rootId = rootId;
        }

        public int getNodeCount() { return nodes.size(); }
        public int getEdgeCount() { return edges.size(); }
    }

    /** 调用者信息（类型别名，委托给 NodeEdgePair） */
    public static class CallerInfo extends NodeEdgePair {
        public CallerInfo(Node node, Edge edge) { super(node, edge); }
    }

    /** 被调用者信息（类型别名，委托给 NodeEdgePair） */
    public static class CalleeInfo extends NodeEdgePair {
        public CalleeInfo(Node node, Edge edge) { super(node, edge); }
    }

    /** 使用信息（类型别名，委托给 NodeEdgePair） */
    public static class UsageInfo extends NodeEdgePair {
        public UsageInfo(Node node, Edge edge) { super(node, edge); }
    }

    /** 路径步骤（类型别名，委托给 NodeEdgePair） */
    public static class PathStep extends NodeEdgePair {
        public PathStep(Node node, Edge edge) { super(node, edge); }
    }
}
