package com.codegraph.parser.tree_sitter;

import com.codegraph.core.types.EdgeKind;
import com.codegraph.core.types.NodeKind;
import com.codegraph.core.types.Visibility;
import com.codegraph.parser.ParseResult;
import com.codegraph.parser.bridge.*;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

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

        boolean langOk = ts.ts_parser_set_language(parser, language);
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
        try {
            visitNode(rootNode, ctx, extractor);
        } catch (Exception e) {
            logger.error("[extract] Error during AST traversal for {}: {}", filePathStr, e.getMessage(), e);
        }

        // 5. 清理
        ts.ts_tree_delete(tree);
        ts.ts_parser_delete(parser);
        logger.trace("[extract] parser & tree cleaned up");

        ParseResult result = new ParseResult(ctx.getNodes(), ctx.getEdges());
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
        ctx.pushScope(classNode.getId());

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

        ctx.pushScope(enumNode.getId());

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

        ctx.createNode(
            NodeKind.METHOD, methodName, node,
            visibility, isStatic, isAbstract,
            signature, returnType, docstring
        );

        logger.debug("[method] {}创建 {} '{}' sig={} ret={} vis={} static={} abstract={}",
            depthPrefix(depth), isConstructor ? "constructor" : "method",
            methodName, signature, returnType, visibility, isStatic, isAbstract);
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
        if (!ts.ts_node_is_null(superclass)) {
            String superName = extractSimpleTypeName(superclass, source, ts);
            if (superName != null && !superName.isEmpty()) {
                String superId = buildExternalNodeId(ctx.getFilePath(), "CLASS", superName);
                ctx.addEdge(classEntity.getId(), superId, EdgeKind.EXTENDS,
                    ts.ts_node_start_point(superclass).row + 1,
                    ts.ts_node_start_point(superclass).column + 1);
                logger.trace("[extends] {} extends {} → edge source={} target={}",
                    classEntity.getName(), superName, classEntity.getId(), superId);
            } else {
                logger.trace("[extends] {} has superclass node but empty name", classEntity.getName());
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

        TSNode superifaces = TreeSitterHelpers.getChildByField(classNode, "superinterfaces", ts);
        if (!ts.ts_node_is_null(superifaces)) {
            // superinterfaces → type_list → type_identifier ...
            int childCount = ts.ts_node_named_child_count(superifaces);
            for (int i = 0; i < childCount; i++) {
                TSNode child = ts.ts_node_named_child(superifaces, i);
                String ifaceName = extractSimpleTypeName(child, source, ts);
                if (ifaceName != null && !ifaceName.isEmpty()) {
                    String ifaceId = buildExternalNodeId(ctx.getFilePath(), "INTERFACE", ifaceName);
                    ctx.addEdge(classEntity.getId(), ifaceId, EdgeKind.IMPLEMENTS,
                        ts.ts_node_start_point(child).row + 1,
                        ts.ts_node_start_point(child).column + 1);
                    logger.trace("[implements] {} implements {} → edge source={} target={}",
                        classEntity.getName(), ifaceName, classEntity.getId(), ifaceId);
                }
            }
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

        // scoped_identifier (e.g. java.util.List)
        if ("scoped_identifier".equals(type)) {
            return TreeSitterHelpers.getNodeText(node, source, ts);
        }

        // type_list → delegate to children
        return null;
    }

    /**
     * 为外部引用构建节点 ID（用于跨文件边）。
     */
    private String buildExternalNodeId(String filePath, String kind, String name) {
        return TreeSitterHelpers.generateNodeId(filePath, kind, name, 0);
    }
}
