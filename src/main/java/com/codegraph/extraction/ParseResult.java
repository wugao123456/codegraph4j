package com.codegraph.extraction;

import com.codegraph.core.Edge;
import com.codegraph.core.Node;
import com.codegraph.resolution.frameworks.UnresolvedRef;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析结果，包含节点、边和未解析引用。
 */
public class ParseResult {

    private final List<Node> nodes;
    private final List<Edge> edges;
    private final List<UnresolvedRef> unresolvedRefs;

    public ParseResult() {
        this.nodes = new ArrayList<>();
        this.edges = new ArrayList<>();
        this.unresolvedRefs = new ArrayList<>();
    }

    public ParseResult(List<Node> nodes, List<Edge> edges) {
        this.nodes = nodes != null ? nodes : new ArrayList<>();
        this.edges = edges != null ? edges : new ArrayList<>();
        this.unresolvedRefs = new ArrayList<>();
    }

    public List<Node> getNodes() {
        return nodes;
    }

    public List<Edge> getEdges() {
        return edges;
    }

    public List<UnresolvedRef> getUnresolvedRefs() {
        return unresolvedRefs;
    }

    public void addNode(Node node) {
        nodes.add(node);
    }

    public void addEdge(Edge edge) {
        edges.add(edge);
    }

    public void addUnresolvedRef(UnresolvedRef ref) {
        unresolvedRefs.add(ref);
    }

    public void merge(ParseResult other) {
        if (other != null) {
            nodes.addAll(other.nodes);
            edges.addAll(other.edges);
            unresolvedRefs.addAll(other.unresolvedRefs);
        }
    }

    public int getNodeCount() {
        return nodes.size();
    }

    public int getEdgeCount() {
        return edges.size();
    }

    public int getUnresolvedRefCount() {
        return unresolvedRefs.size();
    }

    @Override
    public String toString() {
        return "ParseResult{nodes=" + nodes.size() + ", edges=" + edges.size() +
                ", unresolvedRefs=" + unresolvedRefs.size() + "}";
    }
}
