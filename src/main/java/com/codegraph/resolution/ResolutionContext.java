package com.codegraph.resolution;

import com.codegraph.core.Node;
import com.codegraph.core.types.NodeKind;
import com.codegraph.db.QueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 解析上下文 — 解析器通过此接口访问代码图谱和文件系统。
 * 对应 codegraph 项目中的 ResolutionContext 类型。
 */
public class ResolutionContext {

    private final QueryBuilder queries;
    private final String projectRoot;

    public ResolutionContext(QueryBuilder queries, String projectRoot) {
        this.queries = queries;
        this.projectRoot = projectRoot;
    }

    // ---- 节点查询 ----

    public Node getNodeById(String id) {
        try { return queries.getNode(id); } catch (SQLException e) { return null; }
    }

    public List<Node> getNodesInFile(String filePath) {
        try { return queries.getNodesInFile(filePath); } catch (SQLException e) { return Collections.emptyList(); }
    }

    public List<Node> getNodesByName(String name) {
        try { return queries.getNodesByName(name); } catch (SQLException e) { return Collections.emptyList(); }
    }

    public List<Node> getNodesByQualifiedName(String qualifiedName) {
        try { return queries.getNodesByQualifiedName(qualifiedName); } catch (SQLException e) { return Collections.emptyList(); }
    }

    public List<Node> getNodesByKind(NodeKind kind) {
        try { return queries.getNodesByKind(kind); } catch (SQLException e) { return Collections.emptyList(); }
    }

    public List<Node> getNodesByLowerName(String lowerName) {
        try { return queries.getNodesByLowerName(lowerName); } catch (SQLException e) { return Collections.emptyList(); }
    }

    // ---- 文件查询 ----

    public String getProjectRoot() {
        return projectRoot;
    }

    public List<String> getAllFiles() {
        try { return queries.getAllFiles(); } catch (SQLException e) { return Collections.emptyList(); }
    }

    /**
     * 读取文件内容。用于框架解析器检测时扫描文件特征。
     * 支持相对路径（相对于 projectRoot）和绝对路径。
     */
    public String readFile(String filePath) {
        try {
            String fullPath;
            if (filePath.startsWith("/")) {
                // 绝对路径：直接使用，但需要规范化（去除 ./ 等）
                fullPath = Paths.get(filePath).normalize().toString();
            } else {
                fullPath = projectRoot + "/" + filePath;
            }
            byte[] bytes = Files.readAllBytes(Paths.get(fullPath));
            return new String(bytes);
        } catch (IOException e) {
            return null;
        }
    }

    public boolean fileExists(String relativePath) {
        return Files.exists(Paths.get(projectRoot, relativePath));
    }

    // ---- 依赖查询 ----

    public Set<String> getDependencyFilePaths(String filePath) {
        try {
            return queries.getDependencyFilePaths(filePath,
                com.codegraph.core.types.EdgeKind.CALLS,
                com.codegraph.core.types.EdgeKind.REFERENCES,
                com.codegraph.core.types.EdgeKind.EXTENDS,
                com.codegraph.core.types.EdgeKind.IMPLEMENTS);
        } catch (SQLException e) {
            return Collections.emptySet();
        }
    }

    /**
     * 暴露底层 QueryBuilder 供高级用法。
     */
    public QueryBuilder getQueries() {
        return queries;
    }
}
