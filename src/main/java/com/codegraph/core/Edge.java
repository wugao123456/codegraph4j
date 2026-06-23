package com.codegraph.core;

import com.codegraph.core.types.EdgeKind;

import java.util.Map;
import java.util.Objects;

public class Edge {
    private String source;
    private String target;
    private EdgeKind kind;
    private Map<String, Object> metadata;
    private int line;
    private int column;
    private String provenance;

    public Edge() {
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public EdgeKind getKind() {
        return kind;
    }

    public void setKind(EdgeKind kind) {
        this.kind = kind;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }

    public int getColumn() {
        return column;
    }

    public void setColumn(int column) {
        this.column = column;
    }

    public String getProvenance() {
        return provenance;
    }

    public void setProvenance(String provenance) {
        this.provenance = provenance;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Edge edge = (Edge) o;
        return Objects.equals(source, edge.source) &&
                Objects.equals(target, edge.target) &&
                Objects.equals(kind, edge.kind);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, target, kind);
    }

    @Override
    public String toString() {
        return "Edge{" +
                "source='" + source + '\'' +
                ", target='" + target + '\'' +
                ", kind=" + kind +
                '}';
    }
}