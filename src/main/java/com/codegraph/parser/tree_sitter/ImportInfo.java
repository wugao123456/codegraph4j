package com.codegraph.parser.tree_sitter;

/**
 * 导入信息 DTO。
 */
public class ImportInfo {

    private String moduleName;
    private String signature;

    public ImportInfo() {}

    public ImportInfo(String moduleName, String signature) {
        this.moduleName = moduleName;
        this.signature = signature;
    }

    public String getModuleName() {
        return moduleName;
    }

    public void setModuleName(String moduleName) {
        this.moduleName = moduleName;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    @Override
    public String toString() {
        return "ImportInfo{" +
            "moduleName='" + moduleName + '\'' +
            ", signature='" + signature + '\'' +
            '}';
    }
}
