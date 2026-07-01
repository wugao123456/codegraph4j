package com.codegraph.extraction.tree_sitter;

import com.codegraph.core.types.EdgeKind;
import com.codegraph.core.types.NodeKind;
import com.codegraph.core.types.Visibility;
import com.codegraph.extraction.ParseResult;
import com.codegraph.extraction.bridge.*;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 tree-sitter 的通用代码提取器。
 *
 * 核心流程:
 *   1. 创建 parser，设置语言
 *   2. 解析源码为 AST
 *   3. 深度优先遍历 AST，根据 LanguageExtractor 配置提取符号节点和关系边
 *   4. 清理 parser/tree 资源
 *   5. 返回 ParseResult
 */
public class TreeSitterExtractor {

    private static final Logger logger = LoggerFactory.getLogger(TreeSitterExtractor.class);

    private final TreeSitterNative ts;

    public TreeSitterExtractor() {
        this.ts = TreeSitterLibrary.getTreeSitter();
    }

    // =========================================================================
    // Main extraction
    // =========================================================================

    /**
     * 提取源码中的符号节点和关系边。
     *
     * @param filePath  文件路径
     * @param source    源码内容
     * @param extractor 语言提取器配置
     * @return 解析结果
     */
    public ParseResult extract(Path filePath, String source, LanguageExtractor extractor) {
        String filePathStr = filePath.toString();
        int sourceLen = source.length();
        long startTime = System.currentTimeMillis();

        logger.debug("[extract] === 开始解析: {} ({} chars) ===", filePathStr, sourceLen);

        // 1. 获取 language 指针
        Pointer language = TreeSitterLibrary.getJavaLanguage();
        if (language == null) {
            logger.error("[extract] Java language pointer is null");
            return new ParseResult();
        }
        logger.trace("[extract] language pointer: {}", language);

        // 2. 创建 parser 并设置语言
        Pointer parser = ts.ts_parser_new();
        if (parser == null) {
            logger.error("[extract] Failed to create parser");
            return new ParseResult();
        }
        logger.trace("[extract] parser created: {}", parser);

        boolean langOk = ts.ts_parser_set_language(parser, language) != 0;
        if (!langOk) {
            logger.error("[extract] Failed to set language on parser");
            ts.ts_parser_delete(parser);
            return new ParseResult();
        }
        logger.trace("[extract] language set on parser: ok");

        // 3. 解析（使用 UTF-8 字节长度，而非 Java 字符数）
        byte[] sourceBytes = source.getBytes(StandardCharsets.UTF_8);
        int byteLen = sourceBytes.length;
        logger.trace("[extract] parsing {} chars ({} UTF-8 bytes)...", sourceLen, byteLen);

        Pointer tree = ts.ts_parser_parse_string(parser, null, source, byteLen);
        if (tree == null) {
            logger.error("[extract] Parse returned null tree for {}", filePathStr);
            ts.ts_parser_delete(parser);
            return new ParseResult();
        }
        logger.trace("[extract] tree created: {}", tree);

        TSNode rootNode = ts.ts_tree_root_node(tree);
        if (ts.ts_node_is_null(rootNode)) {
            logger.error("[extract] Root node is null for {}", filePathStr);
            ts.ts_tree_delete(tree);
            ts.ts_parser_delete(parser);
            return new ParseResult();
        }

        String rootType = ts.ts_node_type(rootNode);
        int rootNamedChildren = ts.ts_node_named_child_count(rootNode);
        boolean hasError = ts.ts_node_has_error(rootNode);
        logger.debug("[extract] root node: type={}, namedChildren={}, hasError={}",
            rootType, rootNamedChildren, hasError);

        // 4. 遍历 AST
        ExtractorContext ctx = new ExtractorContext(filePathStr, source, ts);

        // 创建文件节点并推入作用域栈（后续所有顶层节点会自动建立 file→节点的 CONTAINS 边）
        String fileNodeId = TreeSitterHelpers.generateNodeId(filePathStr, "file", filePathStr, 1);
        com.codegraph.core.Node fileNode = new com.codegraph.core.Node();
        fileNode.setId(fileNodeId);
        fileNode.setKind(NodeKind.FILE);
        fileNode.setName(filePathStr);
        fileNode.setQualifiedName(filePathStr);
        fileNode.setFilePath(filePathStr);
        fileNode.setLanguage(com.codegraph.core.types.Language.JAVA);
        fileNode.setStartLine(1);
        fileNode.setStartColumn(1);
        fileNode.setEndLine(1);
        fileNode.setEndColumn(1);
        fileNode.setUpdatedAt(System.currentTimeMillis());
        ctx.getNodes().add(fileNode);
        ctx.pushScope(filePathStr, fileNodeId);

        try {
            visitNode(rootNode, ctx, extractor);
        } catch (Exception e) {
            logger.error("[extract] Error during AST traversal for {}: {}", filePathStr, e.getMessage(), e);
        }

        ctx.popScope();

        // 4.1 解析方法调用引用，生成 CALLS 边（启发式）
        resolvePendingReferences(ctx);

        // 5. 清理
        ts.ts_tree_delete(tree);
        ts.ts_parser_delete(parser);
        logger.trace("[extract] parser & tree cleaned up");

        ParseResult result = new ParseResult(ctx.getNodes(), ctx.getEdges());
        // 将无法在当前文件内解析的引用移到 ParseResult，供 SyncOrchestrator 写入 unresolved_refs
        result.getUnresolvedRefs().addAll(ctx.getUnresolvedRefs());
        long elapsed = System.currentTimeMillis() - startTime;

        // 按类型统计
        long classCount = result.getNodes().stream().filter(n -> n.getKind() == NodeKind.CLASS).count();
        long methodCount = result.getNodes().stream().filter(n -> n.getKind() == NodeKind.METHOD).count();
        long fieldCount = result.getNodes().stream().filter(n -> n.getKind() == NodeKind.FIELD).count();
        long ifaceCount = result.getNodes().stream().filter(n -> n.getKind() == NodeKind.INTERFACE).count();
        long enumCount  = result.getNodes().stream().filter(n -> n.getKind() == NodeKind.ENUM).count();
        long moduleCount = result.getNodes().stream().filter(n -> n.getKind() == NodeKind.MODULE).count();
        long importCount = result.getNodes().stream().filter(n -> n.getKind() == NodeKind.IMPORT).count();

        long extendsCount = result.getEdges().stream().filter(e -> e.getKind() == EdgeKind.EXTENDS).count();
        long implementsCount = result.getEdges().stream().filter(e -> e.getKind() == EdgeKind.IMPLEMENTS).count();
        long containsCount = result.getEdges().stream().filter(e -> e.getKind() == EdgeKind.CONTAINS).count();

        logger.debug("[extract] === 解析完成: {} ({}ms) ===", filePathStr, elapsed);
        logger.debug("[extract]   nodes={}  edges={}", result.getNodeCount(), result.getEdgeCount());
        logger.debug("[extract]   按类型: class={}, interface={}, enum={}, method={}, field={}, module={}, import={}",
            classCount, ifaceCount, enumCount, methodCount, fieldCount, moduleCount, importCount);
        logger.debug("[extract]   按边: contains={}, extends={}, implements={}",
            containsCount, extendsCount, implementsCount);

        return result;
    }

    // =========================================================================
    // AST traversal
    // =========================================================================

    private void visitNode(TSNode node, ExtractorContext ctx, LanguageExtractor extractor) {
        if (ts.ts_node_is_null(node)) return;

        String nodeType = ts.ts_node_type(node);
        if (nodeType == null) return;

        int namedChildCount = ts.ts_node_named_child_count(node);
        int depth = ctx.getScopeDepth();
        boolean isError = ts.ts_node_is_error(node);
        boolean isMissing = ts.ts_node_is_missing(node);

        logger.trace("[visit] {}depth={} type='{}' namedChildren={} error={} missing={}",
            depthPrefix(depth), depth, nodeType, namedChildCount, isError, isMissing);

        String source = ctx.getSource();
        boolean visited = false;

        // ---- Class declaration ----
        if (extractor.classTypes().contains(nodeType)) {
            visited = true;
            logger.trace("[visit] {}→ visitClass (kind=CLASS)", depthPrefix(depth));
            visitClass(node, ctx, extractor, NodeKind.CLASS);
        }

        // ---- Interface declaration ----
        if (!visited && extractor.interfaceTypes().contains(nodeType)) {
            visited = true;
            logger.trace("[visit] {}→ visitClass (kind=INTERFACE)", depthPrefix(depth));
            visitClass(node, ctx, extractor, NodeKind.INTERFACE);
        }

        // ---- Enum declaration ----
        if (!visited && extractor.enumTypes().contains(nodeType)) {
            visited = true;
            logger.trace("[visit] {}→ visitEnum", depthPrefix(depth));
            visitEnum(node, ctx, extractor);
        }

        // ---- Method / Constructor ----
        if (!visited && extractor.methodTypes().contains(nodeType)) {
            visited = true;
            TSNode nameNode = TreeSitterHelpers.getChildByField(node, extractor.nameField(), ts);
            String methodPreview = ts.ts_node_is_null(nameNode)
                ? "?" : TreeSitterHelpers.getNodeText(nameNode, source, ts);
            logger.trace("[visit] {}→ visitMethod '{}' (type={})", depthPrefix(depth), methodPreview, nodeType);
            visitMethod(node, ctx, extractor, nodeType);
        }

        // ---- Field ----
        if (!visited && extractor.fieldTypes().contains(nodeType)) {
            visited = true;
            logger.trace("[visit] {}→ visitField", depthPrefix(depth));
            visitField(node, ctx, extractor);
        }

        // ---- Package ----
        if (!visited && extractor.packageTypes().contains(nodeType)) {
            visited = true;
            String pkgName = extractor.extractPackage(node, source);
            logger.trace("[visit] {}→ package '{}'", depthPrefix(depth), pkgName);
            if (pkgName != null && !pkgName.isEmpty()) {
                ctx.setPackageName(pkgName);
                ctx.addPackageOrImportNode(NodeKind.MODULE, pkgName, node);
                logger.debug("[visit] {}  package set: {}", depthPrefix(depth), pkgName);
            }
        }

        // ---- Import ----
        if (!visited && extractor.importTypes().contains(nodeType)) {
            visited = true;
            ImportInfo importInfo = extractor.extractImport(node, source);
            if (importInfo != null && importInfo.getModuleName() != null
                && !importInfo.getModuleName().isEmpty()) {
                logger.trace("[visit] {}→ import '{}'", depthPrefix(depth), importInfo.getModuleName());
                ctx.addPackageOrImportNode(NodeKind.IMPORT, importInfo.getModuleName(), node);
            } else {
                logger.trace("[visit] {}→ import (skipped, empty)", depthPrefix(depth));
            }
        }

        // ---- Enum constant ----
        if (!visited && extractor.enumMemberTypes().contains(nodeType)) {
            visited = true;
            logger.trace("[visit] {}→ visitEnumConstant", depthPrefix(depth));
            visitEnumConstant(node, ctx, extractor);
        }

        // ---- Method invocation (method call) ----
        if (!visited && extractor.methodInvocationTypes().contains(nodeType)) {
            visited = true;
            logger.trace("[visit] {}→ processMethodInvocation '{}'", depthPrefix(depth), nodeType);
            processMethodInvocation(node, ctx, extractor, false);
        }

        // ---- Super method invocation ----
        if (!visited && extractor.superMethodTypes().contains(nodeType)) {
            visited = true;
            logger.trace("[visit] {}→ processMethodInvocation (super) '{}'", depthPrefix(depth), nodeType);
            processMethodInvocation(node, ctx, extractor, true);
        }

        // ---- Recursion: visit children ----
        if (!visited) {
            for (int i = 0; i < namedChildCount; i++) {
                TSNode child = ts.ts_node_named_child(node, i);
                visitNode(child, ctx, extractor);
            }
        }
    }

    /** 生成缩进前缀，配合 scopeDepth 可视化 AST 层级 */
    private static String depthPrefix(int depth) {
        if (depth <= 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        return sb.toString();
    }

    // =========================================================================
    // Class / Interface
    // =========================================================================

    private void visitClass(TSNode node, ExtractorContext ctx, LanguageExtractor extractor, NodeKind kind) {
        String source = ctx.getSource();
        TreeSitterNative ts = ctx.getTreeSitter();
        int depth = ctx.getScopeDepth();

        // 获取类名
        TSNode nameNode = TreeSitterHelpers.getChildByField(node, extractor.nameField(), ts);
        if (ts.ts_node_is_null(nameNode)) {
            logger.trace("[class] {}nameNode is null, skipping", depthPrefix(depth));
            return;
        }
        String className = TreeSitterHelpers.getNodeText(nameNode, source, ts);
        if (className == null || className.isEmpty()) {
            logger.trace("[class] {}className is empty, skipping", depthPrefix(depth));
            return;
        }

        // 获取文档注释
        String docstring = TreeSitterHelpers.getPrecedingDocstring(node, source, ts);

        // 获取可见性和修饰符
        Visibility visibility = extractor.getVisibility(node, ctx);
        boolean isStatic = extractor.isStatic(node, ctx);
        boolean isAbstract = extractor.isAbstract(node, ctx);

        logger.debug("[class] {}创建 {} '{}' vis={} static={} abstract={} docstring={}",
            depthPrefix(depth), kind, className, visibility, isStatic, isAbstract,
            docstring != null && !docstring.isEmpty());

        // 创建节点
        com.codegraph.core.Node classNode = ctx.createNode(
            kind, className, node,
            visibility, isStatic, isAbstract,
            null, null, docstring
        );
        logger.trace("[class] {}  nodeId={}", depthPrefix(depth), classNode.getId());

        // 进入类作用域
        ctx.pushScope(className, classNode.getId());
        ctx.enterClass(classNode.getId());

        // 处理 EXTENDS
        int edgesBefore = ctx.getEdges().size();
        processExtends(node, classNode, ctx, extractor);
        int extendsAdded = ctx.getEdges().size() - edgesBefore;
        if (extendsAdded > 0) {
            logger.trace("[class] {}  added {} EXTENDS edge(s)", depthPrefix(depth), extendsAdded);
        }

        // 处理 IMPLEMENTS
        edgesBefore = ctx.getEdges().size();
        processImplements(node, classNode, ctx, extractor);
        int implementsAdded = ctx.getEdges().size() - edgesBefore;
        if (implementsAdded > 0) {
            logger.trace("[class] {}  added {} IMPLEMENTS edge(s)", depthPrefix(depth), implementsAdded);
        }

        // 遍历 body 中的成员
        int nodesBefore = ctx.getNodes().size();
        TSNode body = TreeSitterHelpers.getChildByField(node, extractor.bodyField(), ts);
        if (!ts.ts_node_is_null(body)) {
            int childCount = ts.ts_node_named_child_count(body);
            logger.trace("[class] {}  body has {} named children", depthPrefix(depth), childCount);
            for (int i = 0; i < childCount; i++) {
                TSNode child = ts.ts_node_named_child(body, i);
                visitNode(child, ctx, extractor);
            }
        } else {
            logger.trace("[class] {}  no body field found", depthPrefix(depth));
        }

        // Lombok 合成成员
        int synthBefore = ctx.getNodes().size();
        extractor.synthesizeMembers(node, ctx);
        int synthAdded = ctx.getNodes().size() - synthBefore;
        if (synthAdded > 0) {
            logger.debug("[class] {}  Lombok 合成了 {} 个成员", depthPrefix(depth), synthAdded);
        }

        int bodyNodesAdded = ctx.getNodes().size() - nodesBefore - synthAdded;
        logger.debug("[class] {}完成: '{}' body中提取了 {} 个成员节点",
            depthPrefix(depth), className, bodyNodesAdded);

        // 离开类作用域
        ctx.exitClass();
        ctx.popScope();
    }

    // =========================================================================
    // Enum
    // =========================================================================

    private void visitEnum(TSNode node, ExtractorContext ctx, LanguageExtractor extractor) {
        String source = ctx.getSource();
        TreeSitterNative ts = ctx.getTreeSitter();
        int depth = ctx.getScopeDepth();

        TSNode nameNode = TreeSitterHelpers.getChildByField(node, extractor.nameField(), ts);
        if (ts.ts_node_is_null(nameNode)) {
            logger.trace("[enum] {}nameNode is null, skipping", depthPrefix(depth));
            return;
        }
        String enumName = TreeSitterHelpers.getNodeText(nameNode, source, ts);
        if (enumName == null || enumName.isEmpty()) {
            logger.trace("[enum] {}enumName is empty, skipping", depthPrefix(depth));
            return;
        }

        Visibility visibility = extractor.getVisibility(node, ctx);
        boolean isStatic = extractor.isStatic(node, ctx);
        boolean isAbstract = extractor.isAbstract(node, ctx);

        logger.debug("[enum] {}创建 ENUM '{}' vis={}", depthPrefix(depth), enumName, visibility);

        com.codegraph.core.Node enumNode = ctx.createNode(
            NodeKind.ENUM, enumName, node,
            visibility, isStatic, isAbstract,
            null, null, null
        );
        logger.trace("[enum] {}  nodeId={}", depthPrefix(depth), enumNode.getId());

        ctx.pushScope(enumName, enumNode.getId());

        // 处理接口实现
        int edgesBefore = ctx.getEdges().size();
        processImplements(node, enumNode, ctx, extractor);
        int added = ctx.getEdges().size() - edgesBefore;
        if (added > 0) logger.trace("[enum] {}  added {} IMPLEMENTS edge(s)", depthPrefix(depth), added);

        // 遍历 body
        int nodesBefore = ctx.getNodes().size();
        TSNode body = TreeSitterHelpers.getChildByField(node, extractor.bodyField(), ts);
        if (!ts.ts_node_is_null(body)) {
            int childCount = ts.ts_node_named_child_count(body);
            logger.trace("[enum] {}  body has {} named children", depthPrefix(depth), childCount);
            for (int i = 0; i < childCount; i++) {
                TSNode child = ts.ts_node_named_child(body, i);
                visitNode(child, ctx, extractor);
            }
        }

        int bodyNodesAdded = ctx.getNodes().size() - nodesBefore;
        logger.debug("[enum] {}完成: '{}' body中提取了 {} 个成员", depthPrefix(depth), enumName, bodyNodesAdded);

        ctx.popScope();
    }

    // =========================================================================
    // Method / Constructor
    // =========================================================================

    private void visitMethod(TSNode node, ExtractorContext ctx, LanguageExtractor extractor, String nodeType) {
        String source = ctx.getSource();
        TreeSitterNative ts = ctx.getTreeSitter();
        int depth = ctx.getScopeDepth();

        TSNode nameNode = TreeSitterHelpers.getChildByField(node, extractor.nameField(), ts);

        boolean isConstructor = "constructor_declaration".equals(nodeType);
        String methodName = ts.ts_node_is_null(nameNode)
            ? (isConstructor ? "<init>" : "")
            : TreeSitterHelpers.getNodeText(nameNode, source, ts);

        if (methodName.isEmpty()) {
            logger.trace("[method] {}name is empty (type={}), skipping", depthPrefix(depth), nodeType);
            return;
        }

        Visibility visibility = extractor.getVisibility(node, ctx);
        boolean isStatic = extractor.isStatic(node, ctx);
        boolean isAbstract = extractor.isAbstract(node, ctx);
        String signature = extractor.getSignature(node, source);

        // 返回类型（仅对 non-constructor 方法）
        String returnType = null;
        if (!isConstructor) {
            TSNode retNode = TreeSitterHelpers.getChildByField(node, extractor.returnField(), ts);
            if (!ts.ts_node_is_null(retNode)) {
                returnType = TreeSitterHelpers.getNodeText(retNode, source, ts);
            }
        }

        String docstring = TreeSitterHelpers.getPrecedingDocstring(node, source, ts);

        com.codegraph.core.Node methodNode = ctx.createNode(
            NodeKind.METHOD, methodName, node,
            visibility, isStatic, isAbstract,
            signature, returnType, docstring
        );

        logger.debug("[method] {}创建 {} '{}' sig={} ret={} vis={} static={} abstract={}",
            depthPrefix(depth), isConstructor ? "constructor" : "method",
            methodName, signature, returnType, visibility, isStatic, isAbstract);

        // 进入方法上下文（用于 CALLS 边收集）
        ctx.enterMethod(methodNode.getId(), methodNode.getQualifiedName());

        // 遍历方法体，收集调用引用
        TSNode body = TreeSitterHelpers.getChildByField(node, extractor.bodyField(), ts);
        if (!ts.ts_node_is_null(body)) {
            int childCount = ts.ts_node_named_child_count(body);
            logger.trace("[method] {}  body has {} named children", depthPrefix(depth), childCount);
            for (int i = 0; i < childCount; i++) {
                TSNode child = ts.ts_node_named_child(body, i);
                visitNode(child, ctx, extractor);
            }
        }

        // 退出方法上下文
        ctx.exitMethod();
    }

    // =========================================================================
    // Field
    // =========================================================================

    private void visitField(TSNode node, ExtractorContext ctx, LanguageExtractor extractor) {
        String source = ctx.getSource();
        TreeSitterNative ts = ctx.getTreeSitter();
        int depth = ctx.getScopeDepth();

        // fields can have multiple variable_declarators (e.g. int a, b, c;)
        int childCount = ts.ts_node_named_child_count(node);
        int created = 0;
        for (int i = 0; i < childCount; i++) {
            TSNode child = ts.ts_node_named_child(node, i);
            if ("variable_declarator".equals(ts.ts_node_type(child))) {
                TSNode nameNode = TreeSitterHelpers.getChildByField(child, extractor.nameField(), ts);
                if (!ts.ts_node_is_null(nameNode)) {
                    String fieldName = TreeSitterHelpers.getNodeText(nameNode, source, ts);
                    if (fieldName != null && !fieldName.isEmpty()) {
                        Visibility visibility = extractor.getVisibility(node, ctx);
                        boolean isStatic = extractor.isStatic(node, ctx);
                        String docstring = TreeSitterHelpers.getPrecedingDocstring(node, source, ts);

                        ctx.createNode(
                            NodeKind.FIELD, fieldName, child,
                            visibility, isStatic, false,
                            null, null, docstring
                        );
                        created++;
                    }
                }
            }
        }
        logger.debug("[field] {}创建了 {} 个 FIELD 节点 vis={} static={}",
            depthPrefix(depth), created,
            extractor.getVisibility(node, ctx), extractor.isStatic(node, ctx));
    }

    // =========================================================================
    // Enum constant
    // =========================================================================

    private void visitEnumConstant(TSNode node, ExtractorContext ctx, LanguageExtractor extractor) {
        String source = ctx.getSource();
        TreeSitterNative ts = ctx.getTreeSitter();
        int depth = ctx.getScopeDepth();

        TSNode nameNode = TreeSitterHelpers.getChildByField(node, extractor.nameField(), ts);
        if (ts.ts_node_is_null(nameNode)) {
            // enum_constant 的 name 可能是直接文本
            String text = TreeSitterHelpers.getNodeText(node, source, ts);
            if (text != null && !text.isEmpty()) {
                logger.debug("[enum_const] {}创建 ENUM_MEMBER '{}'", depthPrefix(depth), text);
                ctx.createNode(
                    NodeKind.ENUM_MEMBER, text, node,
                    Visibility.PUBLIC, true, false,
                    null, null, null
                );
            } else {
                logger.trace("[enum_const] {}name is empty, skipping", depthPrefix(depth));
            }
            return;
        }

        String name = TreeSitterHelpers.getNodeText(nameNode, source, ts);
        if (name != null && !name.isEmpty()) {
            logger.debug("[enum_const] {}创建 ENUM_MEMBER '{}'", depthPrefix(depth), name);
            ctx.createNode(
                NodeKind.ENUM_MEMBER, name, node,
                Visibility.PUBLIC, true, false,
                null, null, null
            );
        } else {
            logger.trace("[enum_const] {}extracted name is empty, skipping", depthPrefix(depth));
        }
    }

    // =========================================================================
    // Edge generation: EXTENDS
    // =========================================================================

    private void processExtends(TSNode classNode, com.codegraph.core.Node classEntity,
                                ExtractorContext ctx, LanguageExtractor extractor) {
        TreeSitterNative ts = ctx.getTreeSitter();
        String source = ctx.getSource();

        TSNode superclass = TreeSitterHelpers.getChildByField(classNode, "superclass", ts);
        logger.debug("[extends] checking '{}': superclass field isNull={}",
            classEntity.getName(), ts.ts_node_is_null(superclass));
        if (!ts.ts_node_is_null(superclass)) {
            String superType = ts.ts_node_type(superclass);
            String superText = TreeSitterHelpers.getNodeText(superclass, source, ts);
            logger.debug("[extends] superclass node type={} text='{}'", superType, superText);
            String superName = extractSimpleTypeName(superclass, source, ts);
            logger.debug("[extends] extractSimpleTypeName → '{}'", superName);
            if (superName != null && !superName.isEmpty()) {
                String superId = buildExternalNodeId(ctx.getFilePath(), "CLASS", superName);
                Map<String, Object> extendsMeta = new HashMap<>();
                extendsMeta.put("provenance", "tree-sitter");
                ctx.addEdge(classEntity.getId(), superId, EdgeKind.EXTENDS,
                    ts.ts_node_start_point(superclass).row + 1,
                    ts.ts_node_start_point(superclass).column + 1, null, extendsMeta);
                logger.debug("[extends] {} extends {} → edge source={} target={}",
                    classEntity.getName(), superName, classEntity.getId(), superId);
            } else {
                logger.debug("[extends] {} has superclass node but empty name", classEntity.getName());
            }
        }
    }

    // =========================================================================
    // Edge generation: IMPLEMENTS
    // =========================================================================

    private void processImplements(TSNode classNode, com.codegraph.core.Node classEntity,
                                   ExtractorContext ctx, LanguageExtractor extractor) {
        TreeSitterNative ts = ctx.getTreeSitter();
        String source = ctx.getSource();

        TSNode superifaces = TreeSitterHelpers.getChildByField(classNode, "interfaces", ts);
        if (!ts.ts_node_is_null(superifaces)) {
            // interfaces → type_list → type_identifier ...
            int childCount = ts.ts_node_named_child_count(superifaces);
            logger.debug("[implements] checking '{}': interfaces namedChildren={}",
                classEntity.getName(), childCount);
            for (int i = 0; i < childCount; i++) {
                TSNode child = ts.ts_node_named_child(superifaces, i);
                String childType = ts.ts_node_type(child);
                String childText = TreeSitterHelpers.getNodeText(child, source, ts);
                logger.debug("[implements]   child[{}] type={} text='{}'", i, childType, childText);

                if ("type_list".equals(childType)) {
                    // type_list contains type_identifier children (e.g., "Foo, Bar")
                    int typeCount = ts.ts_node_named_child_count(child);
                    for (int j = 0; j < typeCount; j++) {
                        TSNode typeChild = ts.ts_node_named_child(child, j);
                        String ifaceName = extractSimpleTypeName(typeChild, source, ts);
                        logger.debug("[implements]   type_list[{}] → '{}'", j, ifaceName);
                        addImplementsEdge(classEntity, ifaceName, child, ctx);
                    }
                } else {
                    String ifaceName = extractSimpleTypeName(child, source, ts);
                    logger.debug("[implements]   extractSimpleTypeName → '{}'", ifaceName);
                    addImplementsEdge(classEntity, ifaceName, child, ctx);
                }
            }
        }
    }

    private void addImplementsEdge(com.codegraph.core.Node classEntity, String ifaceName,
                                   TSNode node, ExtractorContext ctx) {
        if (ifaceName != null && !ifaceName.isEmpty()) {
            TreeSitterNative ts = ctx.getTreeSitter();
            String ifaceId = buildExternalNodeId(ctx.getFilePath(), "INTERFACE", ifaceName);
            Map<String, Object> implMeta = new HashMap<>();
            implMeta.put("provenance", "tree-sitter");
            ctx.addEdge(classEntity.getId(), ifaceId, EdgeKind.IMPLEMENTS,
                ts.ts_node_start_point(node).row + 1,
                ts.ts_node_start_point(node).column + 1, null, implMeta);
            logger.debug("[implements] {} implements {} → edge source={} target={}",
                classEntity.getName(), ifaceName, classEntity.getId(), ifaceId);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * 从类型节点提取简单类型名（去掉泛型参数和点分前缀）。
     */
    private String extractSimpleTypeName(TSNode node, String source, TreeSitterNative ts) {
        if (ts.ts_node_is_null(node)) return null;
        String type = ts.ts_node_type(node);

        if ("type_identifier".equals(type)) {
            return TreeSitterHelpers.getNodeText(node, source, ts);
        }

        // tree-sitter-java: superclass field 返回 node type = "superclass",
        // 其内部 named child 才是 type_identifier
        if ("superclass".equals(type)) {
            int childCount = ts.ts_node_named_child_count(node);
            for (int i = 0; i < childCount; i++) {
                TSNode child = ts.ts_node_named_child(node, i);
                String result = extractSimpleTypeName(child, source, ts);
                if (result != null) return result;
            }
            return null;
        }

        // generic_type → type_identifier
        if ("generic_type".equals(type)) {
            int childCount = ts.ts_node_named_child_count(node);
            for (int i = 0; i < childCount; i++) {
                TSNode child = ts.ts_node_named_child(node, i);
                if ("type_identifier".equals(ts.ts_node_type(child))) {
                    return TreeSitterHelpers.getNodeText(child, source, ts);
                }
            }
        }

        // type_list (e.g. implements Foo, Bar) → recursively extract each type_identifier
        if ("type_list".equals(type)) {
            int childCount = ts.ts_node_named_child_count(node);
            for (int i = 0; i < childCount; i++) {
                TSNode child = ts.ts_node_named_child(node, i);
                String result = extractSimpleTypeName(child, source, ts);
                if (result != null) return result;
            }
            return null;
        }

        // scoped_identifier (e.g. java.util.List)
        if ("scoped_identifier".equals(type)) {
            return TreeSitterHelpers.getNodeText(node, source, ts);
        }

        return null;
    }

    /**
     * 为外部引用构建节点 ID（用于跨文件边）。
     */
    private String buildExternalNodeId(String filePath, String kind, String name) {
        return TreeSitterHelpers.generateNodeId(filePath, kind, name, 0);
    }

    // =========================================================================
    // Method invocation / CALLS edge generation
    // =========================================================================

    /**
     * 处理方法调用节点（method_invocation 或 super_method_invocation），
     * 提取调用信息并记录到上下文中，待后续解析为 CALLS 边。
     */
    private void processMethodInvocation(TSNode node, ExtractorContext ctx,
                                         LanguageExtractor extractor, boolean isSuper) {
        String source = ctx.getSource();
        TreeSitterNative ts = ctx.getTreeSitter();

        // 提取被调用方法名
        String calleeName = extractMethodNameFromInvocation(node, source, ts);

        if (calleeName == null || calleeName.isEmpty()) {
            logger.debug("[calls] processMethodInvocation: calleeName is empty, skipping");
            return;
        }

        // 获取调用位置
        TSPoint startPoint = ts.ts_node_start_point(node);
        int line = startPoint.row + 1;
        int column = startPoint.column + 1;

        // 判断是否为链式调用（如 foo.bar() 中的 bar()）
        boolean isChained = isChainedCall(node, ctx, extractor);

        if (isSuper) {
            ctx.addSuperCallReference(calleeName, line, column);
            logger.debug("[calls] super call '{}' at {}:{}", calleeName, line, column);
        } else {
            String receiverType = extractReceiverType(node, source, ts);
            ctx.addCallReference(calleeName, receiverType, line, column, isChained);
            logger.debug("[calls] method call '{}' receiver='{}' chained={} at {}:{}",
                calleeName, receiverType, isChained, line, column);
        }
    }

    /**
     * 从 method_invocation 节点提取方法名。
     * tree-sitter-java 结构: method_invocation → name: identifier
     */
    private String extractMethodNameFromInvocation(TSNode node, String source, TreeSitterNative ts) {
        // tree-sitter-java 使用 "name" 字段而不是 "method" 字段
        TSNode nameField = TreeSitterHelpers.getChildByField(node, "name", ts);
        if (!ts.ts_node_is_null(nameField)) {
            String text = TreeSitterHelpers.getNodeText(nameField, source, ts);
            if (text != null && !text.isEmpty()) {
                return text.trim();
            }
        }

        // 备用方案：遍历 named children 找第一个 identifier
        // method_invocation 结构: object (可选), name: identifier, arguments (可选)
        int childCount = ts.ts_node_named_child_count(node);
        for (int i = 0; i < childCount; i++) {
            TSNode child = ts.ts_node_named_child(node, i);
            String type = ts.ts_node_type(child);
            if ("identifier".equals(type)) {
                String text = TreeSitterHelpers.getNodeText(child, source, ts);
                if (text != null && !text.isEmpty()) {
                    return text.trim();
                }
            }
        }

        return null;
    }

    /**
     * 提取 method_invocation 的 receiver 类型。
     * 如 foo.bar() 中，receiverType 为 "foo"；this.foo() 中为 "this"。
     */
    private String extractReceiverType(TSNode node, String source, TreeSitterNative ts) {
        // method_invocation 有个名为 "object" 的字段，值为 receiver 表达式
        TSNode objectField = TreeSitterHelpers.getChildByField(node, "object", ts);
        if (!ts.ts_node_is_null(objectField)) {
            String type = ts.ts_node_type(objectField);
            if ("identifier".equals(type)) {
                return TreeSitterHelpers.getNodeText(objectField, source, ts);
            }
            if ("this".equals(type)) {
                return "this";
            }
            // 可能是链式调用如 a.b.c()，返回第一个标识符
            return extractFirstIdentifier(objectField, source, ts);
        }

        // 兼容旧版 tree-sitter-java：尝试找 field_access
        int childCount = ts.ts_node_named_child_count(node);
        for (int i = 0; i < childCount; i++) {
            TSNode child = ts.ts_node_named_child(node, i);
            String type = ts.ts_node_type(child);
            if ("field_access".equals(type)) {
                // field_access 结构: field_access → object: identifier + field: identifier
                TSNode obj = TreeSitterHelpers.getChildByField(child, "object", ts);
                if (!ts.ts_node_is_null(obj)) {
                    return TreeSitterHelpers.getNodeText(obj, source, ts);
                }
            }
        }

        return null;
    }

    /**
     * 递归提取第一个 identifier 文本（用于链式调用）。
     */
    private String extractFirstIdentifier(TSNode node, String source, TreeSitterNative ts) {
        String type = ts.ts_node_type(node);
        if ("identifier".equals(type)) {
            return TreeSitterHelpers.getNodeText(node, source, ts);
        }
        if ("field_access".equals(type)) {
            TSNode obj = TreeSitterHelpers.getChildByField(node, "object", ts);
            if (!ts.ts_node_is_null(obj)) {
                return extractFirstIdentifier(obj, source, ts);
            }
        }
        int childCount = ts.ts_node_named_child_count(node);
        for (int i = 0; i < childCount; i++) {
            TSNode child = ts.ts_node_named_child(node, i);
            String result = extractFirstIdentifier(child, source, ts);
            if (result != null) return result;
        }
        return null;
    }

    /**
     * 判断是否为链式调用。
     * 链式调用如 foo.bar()：method_invocation 的 parent 也是 method_invocation，
     * 且当前节点是 parent 的 object/receiver。
     */
    private boolean isChainedCall(TSNode node, ExtractorContext ctx, LanguageExtractor extractor) {
        TreeSitterNative ts = ctx.getTreeSitter();
        TSNode parent = ts.ts_node_parent(node);
        if (ts.ts_node_is_null(parent)) return false;

        String parentType = ts.ts_node_type(parent);
        // 检查是否是 method_invocation 且当前节点是其 receiver
        if (extractor.methodInvocationTypes().contains(parentType)) {
            TSNode objectField = TreeSitterHelpers.getChildByField(parent, "object", ts);
            if (!ts.ts_node_is_null(objectField)) {
                // 检查 objectField 是否与当前节点具有相同的字节范围
                return hasSameByteRange(node, objectField, ts);
            }
        }
        return false;
    }

    /**
     * 检查两个节点是否具有相同的字节范围（start 和 end byte 相同表示同一节点）。
     */
    private boolean hasSameByteRange(TSNode nodeA, TSNode nodeB, TreeSitterNative ts) {
        int startA = ts.ts_node_start_byte(nodeA);
        int endA = ts.ts_node_end_byte(nodeA);
        int startB = ts.ts_node_start_byte(nodeB);
        int endB = ts.ts_node_end_byte(nodeB);
        return startA == startB && endA == endB;
    }

    // =========================================================================
    // Heuristic call reference resolution
    // =========================================================================

    /**
     * 在 AST 遍历完成后，解析收集到的方法调用引用，生成 CALLS 边。
     * 使用启发式匹配：
     *   1. 在当前文件已提取的 METHOD 节点中精确匹配
     *   2. 构建 qualified name 并尝试匹配
     *   3. 对 this.X 和 super.X 进行特殊处理
     */
    public void resolvePendingReferences(ExtractorContext ctx) {
        List<com.codegraph.core.Node> methods = ctx.getNodes().stream()
            .filter(n -> n.getKind() == NodeKind.METHOD)
            .collect(java.util.stream.Collectors.toList());

        // 建立方法名到节点列表的索引（用于快速查找）
        java.util.Map<String, List<com.codegraph.core.Node>> methodNameIndex = new java.util.HashMap<>();
        for (com.codegraph.core.Node method : methods) {
            methodNameIndex.computeIfAbsent(method.getName(), k -> new java.util.ArrayList<>()).add(method);
        }

        logger.debug("[calls] === 启发式 CALLS 边解析开始 ===");
        logger.debug("[calls] 待解析调用引用: {} 个普通调用, {} 个 super 调用",
            ctx.getCallReferences().size(), ctx.getSuperCallReferences().size());
        logger.debug("[calls] 当前文件已知方法数量: {}", methods.size());

        // 打印已知方法列表（DEBUG 级别）
        if (logger.isDebugEnabled()) {
            for (com.codegraph.core.Node method : methods) {
                logger.debug("[calls]   方法: {} (qualifiedName={}, exported={})",
                    method.getName(), method.getQualifiedName(), method.isExported());
            }
        }

        int callsEdgesAdded = 0;

        // 解析普通方法调用
        for (ExtractorContext.CallReference ref : ctx.getCallReferences()) {
            logger.debug("[calls] ---- 解析调用: {} → {} (receiver='{}', chained={}, at {}:{})",
                ref.callerId, ref.calleeName, ref.receiverType, ref.isChained, ref.line, ref.column);

            String calleeId = resolveCalleeId(ref, methods, methodNameIndex, ctx);
            if (calleeId != null) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("provenance", "heuristic");
                ctx.addEdge(ref.callerId, calleeId, EdgeKind.CALLS, ref.line, ref.column, "heuristic", metadata);
                callsEdgesAdded++;
                logger.debug("[calls]   ✓ 解析成功: caller={} → callee={} [heuristic]",
                    ref.callerId, calleeId);
            } else {
                // 无法在当前文件内解析，记录为 UnresolvedRef 供后续 resolution 阶段处理
                ctx.getUnresolvedRefs().add(new com.codegraph.resolution.frameworks.UnresolvedRef(
                    ref.callerId, ref.calleeName, "calls",
                    ctx.getFilePath(), ref.line, ref.column));
                logger.debug("[calls]   ✗ 无法解析: {} → {} at {}:{}",
                    ref.callerId, ref.calleeName, ref.line, ref.column);
            }
        }

        // 解析 super 方法调用
        for (ExtractorContext.CallReference ref : ctx.getSuperCallReferences()) {
            logger.debug("[calls] ---- 解析 super 调用: {} → {} (at {}:{})",
                ref.callerId, ref.calleeName, ref.line, ref.column);

            String parentClassId = resolveSuperClassId(ctx);
            if (parentClassId != null) {
                logger.debug("[calls]   父类 ID: {}", parentClassId);
                // 构建父类中方法的全限定名
                String parentMethodQName = parentClassId + "." + ref.calleeName;
                logger.debug("[calls]   查找父类方法 qualifiedName: {}", parentMethodQName);
                String calleeId = findMethodByQualifiedName(parentMethodQName, methods);
                if (calleeId != null) {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("provenance", "heuristic");
                    ctx.addEdge(ref.callerId, calleeId, EdgeKind.CALLS, ref.line, ref.column, "heuristic", metadata);
                    callsEdgesAdded++;
                    logger.debug("[calls]   ✓ SUPER 调用解析成功: {} → {} [heuristic]",
                        ref.callerId, calleeId);
                } else {
                    logger.debug("[calls]   ✗ 父类中未找到方法: {}", parentMethodQName);
                }
            } else {
                logger.debug("[calls]   ✗ 无法获取父类 ID（当前类可能没有 EXTENDS 边）");
            }
        }

        logger.debug("[calls] === 启发式 CALLS 边解析完成: 生成 {} 条边 ===", callsEdgesAdded);
    }

    /**
     * 解析被调用方法的 ID。
     */
    private String resolveCalleeId(ExtractorContext.CallReference ref,
                                    List<com.codegraph.core.Node> methods,
                                    java.util.Map<String, List<com.codegraph.core.Node>> methodNameIndex,
                                    ExtractorContext ctx) {
        String calleeName = ref.calleeName;

        // 1. 处理 this.X 调用：在本类中查找
        if ("this".equals(ref.receiverType)) {
            logger.debug("[calls]   策略: this.X 调用，在本类中查找 '{}'", calleeName);
            String currentClassQName = getCurrentClassQName(ctx);
            logger.debug("[calls]   当前类 qualifiedName: {}", currentClassQName);
            if (currentClassQName != null) {
                String targetQName = currentClassQName + "." + calleeName;
                logger.debug("[calls]   目标 qualifiedName: {}", targetQName);
                for (com.codegraph.core.Node method : methods) {
                    if (calleeName.equals(method.getName())) {
                        logger.debug("[calls]   检查方法: {} (qualifiedName={})",
                            method.getName(), method.getQualifiedName());
                        // 在本类或父类中查找
                        if (method.getQualifiedName().equals(targetQName) ||
                            method.getQualifiedName().endsWith("." + calleeName)) {
                            logger.debug("[calls]   ✓ 匹配成功: {}", method.getId());
                            return method.getId();
                        }
                    }
                }
            }
            logger.debug("[calls]   ✗ 本类中未找到匹配方法");
        }

        // 2. 处理无 receiver 或简单方法名：在当前文件方法中查找
        else if (ref.receiverType == null || ref.receiverType.isEmpty() || ref.receiverType.equals(calleeName)) {
            logger.debug("[calls]   策略: 无 receiver 或简单方法名，查找同名方法");
            List<com.codegraph.core.Node> candidates = methodNameIndex.get(calleeName);
            if (candidates != null && !candidates.isEmpty()) {
                logger.debug("[calls]   找到 {} 个同名方法", candidates.size());
                // 如果只有一个匹配，直接返回
                if (candidates.size() == 1) {
                    com.codegraph.core.Node m = candidates.get(0);
                    logger.debug("[calls]   ✓ 唯一匹配: {} (qualifiedName={})", m.getId(), m.getQualifiedName());
                    return m.getId();
                }
                // 多个匹配时，返回第一个（最简单的启发式）
                com.codegraph.core.Node m = candidates.get(0);
                logger.debug("[calls]   ✓ 多匹配，选第一个: {} (qualifiedName={})", m.getId(), m.getQualifiedName());
                return m.getId();
            }
            logger.debug("[calls]   ✗ 未找到同名方法");
        }

        // 3. 处理 receiver.method() 形式
        else if (ref.receiverType != null && !ref.receiverType.isEmpty() && !ref.receiverType.equals(calleeName)) {
            logger.debug("[calls]   策略: receiver.method() 形式，receiver='{}'", ref.receiverType);
            List<com.codegraph.core.Node> candidates = methodNameIndex.get(calleeName);
            if (candidates != null) {
                logger.debug("[calls]   找到 {} 个同名方法", candidates.size());
                // 优先返回导出（public/protected）方法
                for (com.codegraph.core.Node method : candidates) {
                    if (method.isExported()) {
                        logger.debug("[calls]   ✓ 导出方法匹配: {} (qualifiedName={}, exported={})",
                            method.getId(), method.getQualifiedName(), method.isExported());
                        return method.getId();
                    }
                }
                // 否则返回第一个
                if (!candidates.isEmpty()) {
                    com.codegraph.core.Node m = candidates.get(0);
                    logger.debug("[calls]   ✓ 无导出方法，选第一个: {} (qualifiedName={})",
                        m.getId(), m.getQualifiedName());
                    return m.getId();
                }
            }
            logger.debug("[calls]   ✗ 未找到同名方法");
        }

        return null;
    }

    /**
     * 获取当前类的 qualified name。
     */
    private String getCurrentClassQName(ExtractorContext ctx) {
        // 从 packageName 和当前类名构建全限定名
        StringBuilder sb = new StringBuilder();
        String packageName = ctx.getPackageName();
        if (packageName != null && !packageName.isEmpty()) {
            sb.append(packageName);
            sb.append(".");
        }
        String className = ctx.getCurrentClassName();
        if (className != null) {
            sb.append(className);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * 解析父类 ID（通过 EXTENDS 边查找）。
     */
    private String resolveSuperClassId(ExtractorContext ctx) {
        String currentClassId = ctx.getCurrentClassId();
        if (currentClassId == null) return null;

        // 在 edges 中查找 EXTENDS 边
        for (com.codegraph.core.Edge edge : ctx.getEdges()) {
            if (EdgeKind.EXTENDS.equals(edge.getKind()) && edge.getSource().equals(currentClassId)) {
                return edge.getTarget();
            }
        }
        return null;
    }

    /**
     * 根据 qualified name 查找方法节点 ID。
     */
    private String findMethodByQualifiedName(String qName, List<com.codegraph.core.Node> methods) {
        for (com.codegraph.core.Node method : methods) {
            if (qName.equals(method.getQualifiedName())) {
                return method.getId();
            }
        }
        return null;
    }
}
