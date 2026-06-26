package com.codegraph.resolution.frameworks;

/**
 * 未解析引用 — 框架提取器产生的待解析引用。
 * 对应 codegraph 项目中的 UnresolvedRef 类型。
 */
public class UnresolvedRef {

    private String fromNodeId;
    private String referenceName;
    private String referenceKind;   // "references", "calls", "imports", "instantiates", "extends"
    private int line;
    private int column;
    private String filePath;

    public UnresolvedRef() {}

    public UnresolvedRef(String fromNodeId, String referenceName, String referenceKind,
                         String filePath, int line, int column) {
        this.fromNodeId = fromNodeId;
        this.referenceName = referenceName;
        this.referenceKind = referenceKind;
        this.filePath = filePath;
        this.line = line;
        this.column = column;
    }

    public String getFromNodeId() { return fromNodeId; }
    public void setFromNodeId(String fromNodeId) { this.fromNodeId = fromNodeId; }

    public String getReferenceName() { return referenceName; }
    public void setReferenceName(String referenceName) { this.referenceName = referenceName; }

    public String getReferenceKind() { return referenceKind; }
    public void setReferenceKind(String referenceKind) { this.referenceKind = referenceKind; }

    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }

    public int getColumn() { return column; }
    public void setColumn(int column) { this.column = column; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
}
