package com.codegraph.core;

import com.codegraph.core.types.Language;
import com.codegraph.core.types.NodeKind;
import com.codegraph.core.types.Visibility;

import java.util.List;
import java.util.Objects;

public class Node {
    private String id;
    private NodeKind kind;
    private String name;
    private String qualifiedName;
    private String filePath;
    private Language language;
    private int startLine;
    private int endLine;
    private int startColumn;
    private int endColumn;
    private String docstring;
    private String signature;
    private Visibility visibility;
    private boolean isExported;
    private boolean isAsync;
    private boolean isStatic;
    private boolean isAbstract;
    private List<String> decorators;
    private List<String> typeParameters;
    private String returnType;
    private long updatedAt;

    public Node() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public NodeKind getKind() {
        return kind;
    }

    public void setKind(NodeKind kind) {
        this.kind = kind;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getQualifiedName() {
        return qualifiedName;
    }

    public void setQualifiedName(String qualifiedName) {
        this.qualifiedName = qualifiedName;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Language getLanguage() {
        return language;
    }

    public void setLanguage(Language language) {
        this.language = language;
    }

    public int getStartLine() {
        return startLine;
    }

    public void setStartLine(int startLine) {
        this.startLine = startLine;
    }

    public int getEndLine() {
        return endLine;
    }

    public void setEndLine(int endLine) {
        this.endLine = endLine;
    }

    public int getStartColumn() {
        return startColumn;
    }

    public void setStartColumn(int startColumn) {
        this.startColumn = startColumn;
    }

    public int getEndColumn() {
        return endColumn;
    }

    public void setEndColumn(int endColumn) {
        this.endColumn = endColumn;
    }

    public String getDocstring() {
        return docstring;
    }

    public void setDocstring(String docstring) {
        this.docstring = docstring;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public Visibility getVisibility() {
        return visibility;
    }

    public void setVisibility(Visibility visibility) {
        this.visibility = visibility;
    }

    public boolean isExported() {
        return isExported;
    }

    public void setExported(boolean exported) {
        isExported = exported;
    }

    public boolean isAsync() {
        return isAsync;
    }

    public void setAsync(boolean async) {
        isAsync = async;
    }

    public boolean isStatic() {
        return isStatic;
    }

    public void setStatic(boolean staticFlag) {
        isStatic = staticFlag;
    }

    public boolean isAbstract() {
        return isAbstract;
    }

    public void setAbstract(boolean abstractFlag) {
        isAbstract = abstractFlag;
    }

    public List<String> getDecorators() {
        return decorators;
    }

    public void setDecorators(List<String> decorators) {
        this.decorators = decorators;
    }

    public List<String> getTypeParameters() {
        return typeParameters;
    }

    public void setTypeParameters(List<String> typeParameters) {
        this.typeParameters = typeParameters;
    }

    public String getReturnType() {
        return returnType;
    }

    public void setReturnType(String returnType) {
        this.returnType = returnType;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Node node = (Node) o;
        return Objects.equals(id, node.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Node{" +
                "id='" + id + '\'' +
                ", kind=" + kind +
                ", name='" + name + '\'' +
                ", qualifiedName='" + qualifiedName + '\'' +
                ", filePath='" + filePath + '\'' +
                ", language=" + language +
                '}';
    }
}