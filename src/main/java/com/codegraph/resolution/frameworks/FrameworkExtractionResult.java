package com.codegraph.resolution.frameworks;

import com.codegraph.core.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 框架提取结果，包含框架特有节点和未解析引用。
 */
public class FrameworkExtractionResult {

    private final List<Node> nodes;
    private final List<UnresolvedRef> references;

    public FrameworkExtractionResult() {
        this.nodes = new ArrayList<>();
        this.references = new ArrayList<>();
    }

    public FrameworkExtractionResult(List<Node> nodes, List<UnresolvedRef> references) {
        this.nodes = nodes != null ? nodes : new ArrayList<>();
        this.references = references != null ? references : new ArrayList<>();
    }

    public List<Node> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    public List<UnresolvedRef> getReferences() {
        return Collections.unmodifiableList(references);
    }

    public void addNode(Node node) {
        nodes.add(node);
    }

    public void addReference(UnresolvedRef ref) {
        references.add(ref);
    }

    public void merge(FrameworkExtractionResult other) {
        if (other != null) {
            nodes.addAll(other.nodes);
            references.addAll(other.references);
        }
    }
}
