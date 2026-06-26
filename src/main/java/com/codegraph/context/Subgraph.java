package com.codegraph.context;

import com.codegraph.core.Edge;
import com.codegraph.core.Node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子图数据结构 — 保存 explore 工具的查询结果。
 * 对标 codegraph context/index.ts 的 Subgraph 类型。
 */
public class Subgraph {

    /** 节点 ID → 节点对象 */
    public final Map<String, Node> nodes = new LinkedHashMap<>();

    /** 子图中的边 */
    public final List<Edge> edges = new ArrayList<>();

    /** 入口节点 ID 列表（搜索种子节点） */
    public final List<String> roots = new ArrayList<>();

    public void addNode(Node node) {
        if (node != null && node.getId() != null) {
            nodes.put(node.getId(), node);
        }
    }

    public void addEdge(Edge edge) {
        if (edge != null) {
            edges.add(edge);
        }
    }

    public void addRoot(String nodeId) {
        if (nodeId != null) {
            roots.add(nodeId);
        }
    }

    public Node getNode(String id) {
        return nodes.get(id);
    }

    public boolean hasNode(String id) {
        return nodes.containsKey(id);
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edges.size();
    }
}
