package com.codegraph.parser.tree_sitter;

import com.codegraph.core.Edge;
import com.codegraph.core.Node;
import com.codegraph.core.types.*;
import com.codegraph.parser.bridge.TSNode;
import com.codegraph.parser.bridge.TSPoint;
import com.codegraph.parser.bridge.TreeSitterNative;

import java.util.*;

/**
 * 提取上下文。
 *
 * 维护 AST 遍历过程中的:
 *   - 作用域栈（用于构建完全限定名和 CONTAINS 边）
 *   - 节点和边的结果列表
 *   - 源文件信息
 */
public class ExtractorContext {

    private final String filePath;
    private final String source;
    private final TreeSitterNative ts;
    private final List<Node> nodes;
    private final List<Edge> edges;
    private final Deque<String> scopeStack;
    private String packageName;

    public ExtractorContext(String filePath, String source, TreeSitterNative ts) {
        this.filePath = filePath;
        this.source = source;
        this.ts = ts;
        this.nodes = new ArrayList<>();
        this.edges = new ArrayList<>();
        this.scopeStack = new ArrayDeque<>();
        this.packageName = "";
    }

    // =========================================================================
    // Public accessors
    // =========================================================================

    public String getFilePath() {
        return filePath;
    }

    public String getSource() {
        return source;
    }

    public TreeSitterNative getTreeSitter() {
        return ts;
    }

    public List<Node> getNodes() {
        return nodes;
    }

    public List<Edge> getEdges() {
        return edges;
    }

    // =========================================================================
    // Scope management
    // =========================================================================

    /**
     * 进入作用域。应作为父节点压栈。
     */
    public void pushScope(String nodeId) {
        scopeStack.push(nodeId);
    }

    /**
     * 离开作用域。
     */
    public void popScope() {
        if (!scopeStack.isEmpty()) {
            scopeStack.pop();
        }
    }

    /**
     * 获取当前作用域的节点 ID（栈顶）。
     */
    public String getCurrentScopeId() {
        return scopeStack.isEmpty() ? null : scopeStack.peek();
    }

    /**
     * 获取当前作用域深度。
     */
    public int getScopeDepth() {
        return scopeStack.size();
    }

    // =========================================================================
    // Node creation
    // =========================================================================

    /**
     * 创建节点并添加到结果列表。
     *
     * @param kind         节点类型
     * @param name         简单名称
     * @param tsNode       tree-sitter 节点
     * @param visibility   可见性
     * @param isStatic     是否 static
     * @param isAbstract   是否 abstract
     * @param signature    方法签名（可为 null）
     * @param returnType   返回类型（可为 null）
     * @param docstring    文档注释（可为 null）
     * @return 创建的节点
     */
    public Node createNode(NodeKind kind, String name, TSNode tsNode,
                           Visibility visibility, boolean isStatic, boolean isAbstract,
                           String signature, String returnType, String docstring) {
        Node node = new Node();

        int startByte = ts.ts_node_start_byte(tsNode);
        TSPoint startPoint = ts.ts_node_start_point(tsNode);
        TSPoint endPoint = ts.ts_node_end_point(tsNode);

        String qualifiedName = buildQualifiedName(name);
        String nodeId = TreeSitterHelpers.generateNodeId(filePath, kind.name(), qualifiedName, startPoint.row + 1);

        node.setId(nodeId);
        node.setKind(kind);
        node.setName(name);
        node.setQualifiedName(qualifiedName);
        node.setFilePath(filePath);
        node.setLanguage(Language.JAVA);
        node.setStartLine(startPoint.row + 1);      // tree-sitter lines are 0-based
        node.setStartColumn(startPoint.column + 1);  // tree-sitter columns are 0-based
        node.setEndLine(endPoint.row + 1);
        node.setEndColumn(endPoint.column + 1);
        node.setVisibility(visibility);
        node.setStatic(isStatic);
        node.setAbstract(isAbstract);
        node.setSignature(signature);
        node.setReturnType(returnType);
        node.setDocstring(docstring);
        node.setUpdatedAt(System.currentTimeMillis());

        // 公共/受保护类成员视为 exported
        boolean exported = (visibility == Visibility.PUBLIC || visibility == Visibility.PROTECTED)
            || (kind == NodeKind.CLASS || kind == NodeKind.INTERFACE || kind == NodeKind.ENUM);
        node.setExported(exported);

        nodes.add(node);

        // 添加到当前作用域
        String parentId = getCurrentScopeId();
        if (parentId != null) {
            addEdge(parentId, nodeId, EdgeKind.CONTAINS, startPoint.row + 1, startPoint.column + 1);
        }

        return node;
    }

    /**
     * 创建包/导入节点（不生成 CONTAINS 边）。
     */
    public void addPackageOrImportNode(NodeKind kind, String name, TSNode tsNode) {
        Node node = new Node();
        TSPoint startPoint = ts.ts_node_start_point(tsNode);
        TSPoint endPoint = ts.ts_node_end_point(tsNode);

        String nodeId = TreeSitterHelpers.generateNodeId(filePath, kind.name(), name, startPoint.row + 1);

        node.setId(nodeId);
        node.setKind(kind);
        node.setName(name);
        node.setQualifiedName(name);
        node.setFilePath(filePath);
        node.setLanguage(Language.JAVA);
        node.setStartLine(startPoint.row + 1);
        node.setStartColumn(startPoint.column + 1);
        node.setEndLine(endPoint.row + 1);
        node.setEndColumn(endPoint.column + 1);
        node.setVisibility(Visibility.PUBLIC);
        node.setUpdatedAt(System.currentTimeMillis());

        nodes.add(node);
    }

    // =========================================================================
    // Edge creation
    // =========================================================================

    /**
     * 添加关系边。
     */
    public void addEdge(String sourceId, String targetId, EdgeKind kind, int line, int column) {
        Edge edge = new Edge();
        edge.setSource(sourceId);
        edge.setTarget(targetId);
        edge.setKind(kind);
        edge.setLine(line);
        edge.setColumn(column);
        edges.add(edge);
    }

    // =========================================================================
    // Qualified name
    // =========================================================================

    /**
     * 设置包名（从 package_declaration 提取后设置）。
     */
    public void setPackageName(String packageName) {
        this.packageName = packageName != null ? packageName : "";
    }

    /**
     * 获取包名。
     */
    public String getPackageName() {
        return packageName;
    }

    /**
     * 构建完全限定名：包名 + scopeStack 从栈底到栈顶 + name。
     */
    private String buildQualifiedName(String name) {
        StringBuilder sb = new StringBuilder();

        // 包名前缀
        if (packageName != null && !packageName.isEmpty()) {
            sb.append(packageName);
        }

        // scopeStack 是栈（栈顶是当前类），需要反序
        Iterator<String> it = scopeStack.descendingIterator();
        while (it.hasNext()) {
            if (sb.length() > 0) sb.append(".");
            sb.append(it.next());
        }

        if (sb.length() > 0) sb.append(".");
        sb.append(name);

        return sb.toString();
    }
}
