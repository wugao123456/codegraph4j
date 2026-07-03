package com.codegraph.graph;

import com.codegraph.core.Edge;
import com.codegraph.core.Node;

/**
 * 节点-边对，统一替换 GraphTraverser 中 4 个结构相同的内部类
 * （CallerInfo、CalleeInfo、UsageInfo、PathStep）。
 */
public class NodeEdgePair {
    public final Node node;
    public final Edge edge;

    public NodeEdgePair(Node node, Edge edge) {
        this.node = node;
        this.edge = edge;
    }
}
