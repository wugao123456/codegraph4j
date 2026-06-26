package com.codegraph.extraction.tree_sitter;

import com.codegraph.core.Edge;
import com.codegraph.core.Node;
import com.codegraph.core.types.*;
import com.codegraph.extraction.bridge.TSNode;
import com.codegraph.extraction.bridge.TSPoint;
import com.codegraph.extraction.bridge.TreeSitterNative;

import java.util.*;

/**
 * 提取上下文。
 *
 * 维护 AST 遍历过程中的:
 *   - 作用域栈（用于构建完全限定名和 CONTAINS 边）
 *   - 节点和边的结果列表
 *   - 源文件信息
 *   - 调用引用跟踪（用于启发式 CALLS 边生成）
 */
public class ExtractorContext {

    private final String filePath;
    private final String source;
    private final TreeSitterNative ts;
    private final List<Node> nodes;
    private final List<Edge> edges;
    private final Deque<String> scopeStack;  // 类名栈，用于构建 qualifiedName
    private final Deque<String> parentIdStack; // 父节点 ID 栈，用于 CONTAINS 边
    private String packageName;

    // ===== 调用引用跟踪（启发式 CALLS 边生成） =====
    /** 当前方法上下文（ID） */
    private String currentMethodId;
    /** 当前方法 qualified name */
    private String currentMethodQName;
    /** 当前类 ID（用于 super_method_invocation 解析） */
    private String currentClassId;
    /** 待解析的方法调用引用 */
    private final List<CallReference> callReferences = new ArrayList<>();
    /** 待解析的父类方法调用引用（super.xxx） */
    private final List<CallReference> superCallReferences = new ArrayList<>();

    public ExtractorContext(String filePath, String source, TreeSitterNative ts) {
        this.filePath = filePath;
        this.source = source;
        this.ts = ts;
        this.nodes = new ArrayList<>();
        this.edges = new ArrayList<>();
        this.scopeStack = new ArrayDeque<>();
        this.parentIdStack = new ArrayDeque<>();
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
     * 进入作用域。压入类名（用于 qualifiedName）和节点 ID（用于 CONTAINS 边）。
     */
    public void pushScope(String className, String nodeId) {
        scopeStack.push(className);
        parentIdStack.push(nodeId);
    }

    /**
     * 离开作用域。
     */
    public void popScope() {
        if (!scopeStack.isEmpty()) {
            scopeStack.pop();
        }
        if (!parentIdStack.isEmpty()) {
            parentIdStack.pop();
        }
    }

    /**
     * 获取当前作用域的节点 ID（栈顶），用于添加 CONTAINS 边。
     */
    public String getCurrentScopeId() {
        return parentIdStack.isEmpty() ? null : parentIdStack.peek();
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
        addEdge(sourceId, targetId, kind, line, column, null);
    }

    /**
     * 添加关系边（带 provenance）。
     */
    public void addEdge(String sourceId, String targetId, EdgeKind kind, int line, int column, String provenance) {
        Edge edge = new Edge();
        edge.setSource(sourceId);
        edge.setTarget(targetId);
        edge.setKind(kind);
        edge.setLine(line);
        edge.setColumn(column);
        edge.setProvenance(provenance);
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

    // =========================================================================
    // Method context tracking (for call graph generation)
    // =========================================================================

    /**
     * 进入方法作用域时调用，记录当前方法上下文。
     */
    public void enterMethod(String methodId, String methodQName) {
        this.currentMethodId = methodId;
        this.currentMethodQName = methodQName;
    }

    /**
     * 离开方法作用域时调用。
     */
    public void exitMethod() {
        this.currentMethodId = null;
        this.currentMethodQName = null;
    }

    /**
     * 进入类作用域时调用，记录当前类上下文。
     */
    public void enterClass(String classId) {
        this.currentClassId = classId;
    }

    /**
     * 离开类作用域时调用。
     */
    public void exitClass() {
        this.currentClassId = null;
    }

    /**
     * 获取当前方法 ID（调用者）。
     */
    public String getCurrentMethodId() {
        return currentMethodId;
    }

    /**
     * 获取当前类 ID。
     */
    public String getCurrentClassId() {
        return currentClassId;
    }

    /**
     * 获取当前类名（从作用域栈顶获取）。
     */
    public String getCurrentClassName() {
        return scopeStack.isEmpty() ? null : scopeStack.peek();
    }

    /**
     * 记录一个方法调用引用（待后置解析）。
     *
     * @param calleeName    被调用方法的简单名
     * @param receiverType  调用者类型（如 "this" 或类名），可为 null
     * @param line           调用所在行
     * @param column         调用所在列
     * @param isChained      是否为链式调用（如 foo.bar() 中的 bar()）
     */
    public void addCallReference(String calleeName, String receiverType, int line, int column, boolean isChained) {
        if (currentMethodId == null || calleeName == null || calleeName.isEmpty()) return;
        callReferences.add(new CallReference(currentMethodId, calleeName, receiverType, line, column, isChained));
    }

    /**
     * 记录一个 super 方法调用引用。
     */
    public void addSuperCallReference(String calleeName, int line, int column) {
        if (currentMethodId == null || currentClassId == null || calleeName == null) return;
        superCallReferences.add(new CallReference(currentMethodId, calleeName, null, line, column, false));
    }

    public List<CallReference> getCallReferences() {
        return callReferences;
    }

    public List<CallReference> getSuperCallReferences() {
        return superCallReferences;
    }

    // =========================================================================
    // Call reference record
    // =========================================================================

    /**
     * 方法调用引用记录 — 在 AST 遍历时收集，遍历后统一解析为 CALLS 边。
     */
    public static class CallReference {
        public final String callerId;
        public final String calleeName;
        public final String receiverType;
        public final int line;
        public final int column;
        public final boolean isChained;

        public CallReference(String callerId, String calleeName, String receiverType,
                            int line, int column, boolean isChained) {
            this.callerId = callerId;
            this.calleeName = calleeName;
            this.receiverType = receiverType;
            this.line = line;
            this.column = column;
            this.isChained = isChained;
        }
    }
}
