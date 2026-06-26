package com.codegraph.parser;

import com.codegraph.core.Edge;
import com.codegraph.core.Node;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析结果，包含节点和边。
 */
public class ParseResult {

    private final List<Node> nodes;
    private final List<Edge> edges;

    public ParseResult() {
        this.nodes = new ArrayList<>();
        this.edges = new ArrayList<>();
    }

    public ParseResult(List<Node> nodes, List<Edge> edges) {
        this.nodes = nodes != null ? nodes : new ArrayList<>();
        this.edges = edges != null ? edges : new ArrayList<>();
    }

    public List<Node> getNodes() {
        return nodes;
    }

    public List<Edge> getEdges() {
        return edges;
    }

    public void addNode(Node node) {
        nodes.add(node);
    }

    public void addEdge(Edge edge) {
        edges.add(edge);
    }

    public void merge(ParseResult other) {
        if (other != null) {
            nodes.addAll(other.nodes);
            edges.addAll(other.edges);
        }
    }

    public int getNodeCount() {
        return nodes.size();
    }

    public int getEdgeCount() {
        return edges.size();
    }

    @Override
    public String toString() {
        return "ParseResult{nodes=" + nodes.size() + ", edges=" + edges.size() + "}";
    }
}
