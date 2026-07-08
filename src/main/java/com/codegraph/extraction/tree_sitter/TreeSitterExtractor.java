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
import java.util.stream.Collectors;

/**
 * 基于 tree-sitter 的通用代码提取器。
 *
 * <p><b>核心流程：</b>
 * <ol>
 *   <li>创建 parser，设置语言（Java）</li>
 *   <li>解析源码为 AST（抽象语法树）</li>
 *   <li>深度优先遍历 AST，根据 LanguageExtractor 配置提取符号节点和关系边</li>
 *   <li>启发式解析方法调用引用，生成 CALLS 边</li>
 *   <li>解析标识符引用，生成 REFERENCES 边</li>
 *   <li>清理 parser/tree 资源</li>
 *   <li>返回 ParseResult（包含节点、边和未解析引用）</li>
 * </ol>
 *
 * <p><b>提取的符号类型：</b>
 * <ul>
 *   <li><b>类/接口/枚举</b>：CLASS, INTERFACE, ENUM</li>
 *   <li><b>成员</b>：METHOD, FIELD, ENUM_MEMBER</li>
 *   <li><b>模块</b>：MODULE（包）, IMPORT</li>
 * </ul>
 *
 * <p><b>生成的边类型：</b>
 * <ul>
 *   <li><b>CONTAINS</b>：父节点包含子节点（file→class, class→method, etc.）</li>
 *   <li><b>EXTENDS</b>：类继承关系</li>
 *   <li><b>IMPLEMENTS</b>：类实现接口关系</li>
 *   <li><b>CALLS</b>：方法调用关系</li>
 *   <li><b>REFERENCES</b>：字段/枚举成员引用</li>
 *   <li><b>IMPORTS</b>：包导入关系</li>
 * </ul>
 */
public class TreeSitterExtractor {

    private static final Logger logger = LoggerFactory.getLogger(TreeSitterExtractor.class);

    /**
     * 格式化字符串，用于在 DEBUG 日志中显示节点类型和位置信息。
     */
    private static final String NODE_INFO_FORMAT = "type=%s, namedChildren=%d, error=%s, missing=%s";

    /**
     * 格式化字符串，用于在 DEBUG 日志中显示节点创建信息。
     */
    private static final String NODE_CREATE_FORMAT = "创建 %s '%s' vis=%s static=%s abstract=%s";

    /**
     * 格式化字符串，用于显示解析统计信息。
     */
    private static final String PARSE_STATS_FORMAT = "nodes=%d  edges=%d";

    /**
     * 格式化字符串，用于显示节点类型统计。
     */
    private static final String NODE_TYPE_STATS_FORMAT = "class=%d, interface=%d, enum=%d, method=%d, field=%d, module=%d, import=%d";

    /**
     * 格式化字符串，用于显示边类型统计。
     */
    private static final String EDGE_TYPE_STATS_FORMAT = "contains=%d, calls=%d, extends=%d, implements=%d, imports=%d, references=%d";

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
     * <p><b>处理流程：</b>
     * <ol>
     *   <li>创建 tree-sitter parser 并设置 Java 语言</li>
     *   <li>解析源码生成 AST（抽象语法树）</li>
     *   <li>初始化提取上下文，创建文件节点</li>
     *   <li>深度优先遍历 AST，提取各类符号节点和关系边</li>
     *   <li>启发式解析方法调用，生成 CALLS 边</li>
     *   <li>解析标识符引用，生成 REFERENCES 边</li>
     *   <li>清理 parser/tree 资源</li>
     *   <li>构建并返回 ParseResult</li>
     * </ol>
     *
     * <p><b>示例：</b>
     * <pre>
     * 输入源码：
     *   package com.example;
     *   public class Foo {
     *       private String name;
     *       public void bar() { System.out.println(name); }
     *   }
     *
     * 输出节点：MODULE(com.example), CLASS(Foo), FIELD(name), METHOD(bar)
     * 输出边：CONTAINS(MODULE→CLASS), CONTAINS(CLASS→FIELD), CONTAINS(CLASS→METHOD),
     *         REFERENCES(METHOD→FIELD)
     * </pre>
     *
     * @param filePath  文件路径
     * @param source    源码内容
     * @param extractor 语言提取器配置（定义如何识别各类语法结构）
     * @return 解析结果，包含提取的节点、边和未解析的外部引用
     */
    public ParseResult extract(Path filePath, String source, LanguageExtractor extractor) {
        String filePathStr = filePath.toString();
        int sourceLen = source.length();
        long startTime = System.currentTimeMillis();

        logger.debug("[extract] === 开始解析: {} ({} chars) ===", filePathStr, sourceLen);

        // 1. 创建 parser 并解析源码
        Pointer parser = createParser();
        if (parser == null) {
            logger.error("[extract] 创建 parser 失败");
            return new ParseResult();
        }

        Pointer tree = parseSource(parser, source);
        if (tree == null) {
            logger.error("[extract] 解析源码失败");
            ts.ts_parser_delete(parser);
            return new ParseResult();
        }

        TSNode rootNode = ts.ts_tree_root_node(tree);
        if (ts.ts_node_is_null(rootNode)) {
            logger.error("[extract] 根节点为空");
            cleanupResources(parser, tree);
            return new ParseResult();
        }

        logRootNodeInfo(rootNode, filePathStr);

        // 2. 遍历 AST 提取符号
        ExtractorContext ctx = createExtractorContext(filePathStr, source);
        try {
            visitNode(rootNode, ctx, extractor);
        } catch (Exception e) {
            logger.error("[extract] AST 遍历时发生错误: {}: {}", filePathStr, e.getMessage(), e);
        }
        ctx.popScope();

        // 3. 解析引用，生成边
        resolvePendingReferences(ctx);
        resolveIdentifierReferences(ctx);

        // 4. 清理资源
        cleanupResources(parser, tree);

        // 5. 构建结果并记录统计
        ParseResult result = buildParseResult(ctx);
        logExtractionStats(result, filePathStr, System.currentTimeMillis() - startTime);

        return result;
    }

    /**
     * 创建并初始化 tree-sitter parser。
     *
     * <p><b>步骤：</b>
     * <ol>
     *   <li>获取 Java language 指针</li>
     *   <li>创建 parser 实例</li>
     *   <li>设置 parser 的语言为 Java</li>
     * </ol>
     *
     * @return parser 指针，如果创建失败返回 null
     */
    private Pointer createParser() {
        Pointer language = TreeSitterLibrary.getJavaLanguage();
        if (language == null) {
            logger.error("[parser] Java language 指针为空");
            return null;
        }
        logger.trace("[parser] language pointer: {}", language);

        Pointer parser = ts.ts_parser_new();
        if (parser == null) {
            logger.error("[parser] 创建 parser 失败");
            return null;
        }
        logger.trace("[parser] parser 创建成功: {}", parser);

        boolean langOk = ts.ts_parser_set_language(parser, language) != 0;
        if (!langOk) {
            logger.error("[parser] 设置语言失败");
            ts.ts_parser_delete(parser);
            return null;
        }
        logger.trace("[parser] 语言设置成功");

        return parser;
    }

    /**
     * 使用 parser 解析源码，生成 AST。
     *
     * <p><b>注意：</b>使用 UTF-8 字节长度而非 Java 字符数，因为 tree-sitter
     * 底层使用字节偏移量。
     *
     * @param parser 已初始化的 parser
     * @param source 源码内容
     * @return AST tree 指针，如果解析失败返回 null
     */
    private Pointer parseSource(Pointer parser, String source) {
        byte[] sourceBytes = source.getBytes(StandardCharsets.UTF_8);
        int byteLen = sourceBytes.length;
        logger.trace("[parse] 解析 {} 字符 ({} UTF-8 字节)...", source.length(), byteLen);

        Pointer tree = ts.ts_parser_parse_string(parser, null, source, byteLen);
        if (tree == null) {
            logger.error("[parse] 解析返回空 tree");
            return null;
        }
        logger.trace("[parse] tree 创建成功: {}", tree);

        return tree;
    }

    /**
     * 创建提取上下文并初始化文件节点。
     *
     * <p><b>文件节点的作用：</b>
     * 文件节点作为整个提取过程的根作用域，后续所有顶层节点（包、类、接口等）
     * 会自动与文件节点建立 CONTAINS 边，形成完整的包含关系树。
     *
     * @param filePathStr 文件路径字符串
     * @param source 源码内容
     * @return 已初始化的提取上下文
     */
    private ExtractorContext createExtractorContext(String filePathStr, String source) {
        ExtractorContext ctx = new ExtractorContext(filePathStr, source, ts);

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

        logger.trace("[ctx] 文件节点创建: id={}, path={}", fileNodeId, filePathStr);

        return ctx;
    }

    /**
     * 清理 parser 和 tree 资源。
     *
     * <p><b>资源管理注意：</b>tree-sitter 使用 native 内存，必须显式释放，
     * 否则会造成内存泄漏。
     *
     * @param parser parser 指针
     * @param tree tree 指针
     */
    private void cleanupResources(Pointer parser, Pointer tree) {
        ts.ts_tree_delete(tree);
        ts.ts_parser_delete(parser);
        logger.trace("[cleanup] parser & tree 资源已释放");
    }

    /**
     * 记录根节点信息到日志。
     *
     * @param rootNode 根节点
     * @param filePathStr 文件路径
     */
    private void logRootNodeInfo(TSNode rootNode, String filePathStr) {
        String rootType = ts.ts_node_type(rootNode);
        int rootNamedChildren = ts.ts_node_named_child_count(rootNode);
        boolean hasError = ts.ts_node_has_error(rootNode);
        logger.debug("[extract] 根节点信息: {}", String.format(NODE_INFO_FORMAT,
            rootType, rootNamedChildren, hasError, ts.ts_node_is_missing(rootNode)));
    }

    /**
     * 构建 ParseResult，包含节点、边和未解析引用。
     *
     * @param ctx 提取上下文
     * @return ParseResult
     */
    private ParseResult buildParseResult(ExtractorContext ctx) {
        ParseResult result = new ParseResult(ctx.getNodes(), ctx.getEdges());
        result.getUnresolvedRefs().addAll(ctx.getUnresolvedRefs());
        return result;
    }

    /**
     * 记录提取统计信息到日志。
     *
     * <p><b>统计维度：</b>
     * <ul>
     *   <li>总节点数和边数</li>
     *   <li>按类型统计节点（class, interface, enum, method, field, module, import）</li>
     *   <li>按类型统计边（contains, calls, extends, implements, imports, references）</li>
     * </ul>
     *
     * @param result 解析结果
     * @param filePathStr 文件路径
     * @param elapsed 耗时（毫秒）
     */
    private void logExtractionStats(ParseResult result, String filePathStr, long elapsed) {
        Map<NodeKind, Long> nodeTypeCounts = result.getNodes().stream()
            .collect(Collectors.groupingBy(com.codegraph.core.Node::getKind, Collectors.counting()));

        Map<EdgeKind, Long> edgeTypeCounts = result.getEdges().stream()
            .collect(Collectors.groupingBy(com.codegraph.core.Edge::getKind, Collectors.counting()));

        logger.debug("[extract] === 解析完成: {} ({}ms) ===", filePathStr, elapsed);
        logger.debug("[extract]   nodes={}  edges={}", result.getNodeCount(), result.getEdgeCount());
        logger.debug("[extract]   按类型: {}", String.format(NODE_TYPE_STATS_FORMAT,
            nodeTypeCounts.getOrDefault(NodeKind.CLASS, 0L),
            nodeTypeCounts.getOrDefault(NodeKind.INTERFACE, 0L),
            nodeTypeCounts.getOrDefault(NodeKind.ENUM, 0L),
            nodeTypeCounts.getOrDefault(NodeKind.METHOD, 0L),
            nodeTypeCounts.getOrDefault(NodeKind.FIELD, 0L),
            nodeTypeCounts.getOrDefault(NodeKind.MODULE, 0L),
            nodeTypeCounts.getOrDefault(NodeKind.IMPORT, 0L)));
        logger.debug("[extract]   按边: {}", String.format(EDGE_TYPE_STATS_FORMAT,
            edgeTypeCounts.getOrDefault(EdgeKind.CONTAINS, 0L),
            edgeTypeCounts.getOrDefault(EdgeKind.CALLS, 0L),
            edgeTypeCounts.getOrDefault(EdgeKind.EXTENDS, 0L),
            edgeTypeCounts.getOrDefault(EdgeKind.IMPLEMENTS, 0L),
            edgeTypeCounts.getOrDefault(EdgeKind.IMPORTS, 0L),
            edgeTypeCounts.getOrDefault(EdgeKind.REFERENCES, 0L)));
    }

    // =========================================================================
    // AST traversal
    // =========================================================================

    /**
     * 深度优先遍历 AST 节点，根据节点类型调用相应的处理方法。
     *
     * <p><b>遍历策略：</b>
     * <ul>
     *   <li>按优先级顺序检查节点类型</li>
     *   <li>每个节点只被处理一次（visited 标志）</li>
     *   <li>如果节点被特定处理器处理，则不再递归遍历其子节点</li>
     *   <li>如果节点未被处理（visited=false），递归遍历所有命名子节点</li>
     * </ul>
     *
     * <p><b>支持的节点类型及处理优先级：</b>
     * <ol>
     *   <li><b>类/接口/枚举</b>：class_types, interface_types, enum_types</li>
     *   <li><b>方法/构造器</b>：method_types</li>
     *   <li><b>字段</b>：field_types</li>
     *   <li><b>包声明</b>：package_types</li>
     *   <li><b>导入声明</b>：import_types</li>
     *   <li><b>枚举常量</b>：enum_member_types</li>
     *   <li><b>方法调用</b>：method_invocation_types</li>
     *   <li><b>super 调用</b>：super_method_types</li>
     *   <li><b>对象创建</b>：object_creation_expression</li>
     *   <li><b>标识符引用</b>：identifier（仅在方法体内）</li>
     * </ol>
     *
     * <p><b>示例：</b>
     * 对于 AST 节点 `class Foo { void bar() {} }`：
     * <ul>
     *   <li>visitNode 遇到 class 类型 → 调用 visitClass</li>
     *   <li>visitClass 遍历 class body 的子节点</li>
     *   <li>遇到 method 类型 → 调用 visitMethod</li>
     *   <li>visitMethod 遍历 method body 的子节点</li>
     * </ul>
     *
     * @param node 当前 AST 节点
     * @param ctx 提取上下文
     * @param extractor 语言提取器配置
     */
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

        visited = handleTypeDeclaration(node, ctx, extractor, depth, visited);
        visited = handleMemberDeclaration(node, ctx, extractor, source, depth, visited);
        visited = handlePackageAndImport(node, ctx, extractor, source, depth, visited);
        visited = handleExpressions(node, ctx, extractor, source, depth, visited);

        if (!visited) {
            for (int i = 0; i < namedChildCount; i++) {
                TSNode child = ts.ts_node_named_child(node, i);
                visitNode(child, ctx, extractor);
            }
        }
    }

    /**
     * 处理类型声明节点：类、接口、枚举。
     *
     * @param node 当前 AST 节点
     * @param ctx 提取上下文
     * @param extractor 语言提取器配置
     * @param depth 当前深度
     * @param visited 是否已被处理
     * @return true 如果节点已被处理
     */
    private boolean handleTypeDeclaration(TSNode node, ExtractorContext ctx,
            LanguageExtractor extractor, int depth, boolean visited) {
        String nodeType = ts.ts_node_type(node);

        if (!visited && extractor.classTypes().contains(nodeType)) {
            logger.trace("[visit] {}→ visitClass (kind=CLASS)", depthPrefix(depth));
            visitClass(node, ctx, extractor, NodeKind.CLASS);
            return true;
        }

        if (!visited && extractor.interfaceTypes().contains(nodeType)) {
            logger.trace("[visit] {}→ visitClass (kind=INTERFACE)", depthPrefix(depth));
            visitClass(node, ctx, extractor, NodeKind.INTERFACE);
            return true;
        }

        if (!visited && extractor.enumTypes().contains(nodeType)) {
            logger.trace("[visit] {}→ visitEnum", depthPrefix(depth));
            visitEnum(node, ctx, extractor);
            return true;
        }

        return visited;
    }

    /**
     * 处理成员声明节点：方法、字段、枚举常量。
     *
     * @param node 当前 AST 节点
     * @param ctx 提取上下文
     * @param extractor 语言提取器配置
     * @param source 源码内容
     * @param depth 当前深度
     * @param visited 是否已被处理
     * @return true 如果节点已被处理
     */
    private boolean handleMemberDeclaration(TSNode node, ExtractorContext ctx,
            LanguageExtractor extractor, String source, int depth, boolean visited) {
        String nodeType = ts.ts_node_type(node);

        if (!visited && extractor.methodTypes().contains(nodeType)) {
            TSNode nameNode = TreeSitterHelpers.getChildByField(node, extractor.nameField(), ts);
            String methodPreview = ts.ts_node_is_null(nameNode)
                ? "?" : TreeSitterHelpers.getNodeText(nameNode, source, ts);
            logger.trace("[visit] {}→ visitMethod '{}' (type={})", depthPrefix(depth), methodPreview, nodeType);
            visitMethod(node, ctx, extractor, nodeType);
            return true;
        }

        if (!visited && extractor.fieldTypes().contains(nodeType)) {
            logger.trace("[visit] {}→ visitField", depthPrefix(depth));
            visitField(node, ctx, extractor);
            return true;
        }

        if (!visited && extractor.enumMemberTypes().contains(nodeType)) {
            logger.trace("[visit] {}→ visitEnumConstant", depthPrefix(depth));
            visitEnumConstant(node, ctx, extractor);
            return true;
        }

        return visited;
    }

    /**
     * 处理包声明和导入声明节点。
     *
     * @param node 当前 AST 节点
     * @param ctx 提取上下文
     * @param extractor 语言提取器配置
     * @param source 源码内容
     * @param depth 当前深度
     * @param visited 是否已被处理
     * @return true 如果节点已被处理
     */
    private boolean handlePackageAndImport(TSNode node, ExtractorContext ctx,
            LanguageExtractor extractor, String source, int depth, boolean visited) {
        String nodeType = ts.ts_node_type(node);

        if (!visited && extractor.packageTypes().contains(nodeType)) {
            String pkgName = extractor.extractPackage(node, source);
            logger.trace("[visit] {}→ package '{}'", depthPrefix(depth), pkgName);
            if (pkgName != null && !pkgName.isEmpty()) {
                ctx.setPackageName(pkgName);
                String moduleNodeId = ctx.addPackageOrImportNode(NodeKind.MODULE, pkgName, node);
                ctx.setModuleNodeId(moduleNodeId);
                logger.debug("[visit] {}  package set: {}, moduleNodeId={}", depthPrefix(depth), pkgName, moduleNodeId);
            }
            return true;
        }

        if (!visited && extractor.importTypes().contains(nodeType)) {
            ImportInfo importInfo = extractor.extractImport(node, source);
            if (importInfo != null && importInfo.getModuleName() != null
                && !importInfo.getModuleName().isEmpty()) {
                logger.trace("[visit] {}→ import '{}'", depthPrefix(depth), importInfo.getModuleName());
                String importNodeId = ctx.addPackageOrImportNode(NodeKind.IMPORT, importInfo.getModuleName(), node);

                String moduleId = ctx.getModuleNodeId();
                if (moduleId != null) {
                    TSPoint startPoint = ts.ts_node_start_point(node);
                    Map<String, Object> importMeta = new HashMap<>();
                    importMeta.put("provenance", "tree-sitter");
                    ctx.addEdge(moduleId, importNodeId, EdgeKind.IMPORTS,
                        startPoint.row + 1, startPoint.column + 1, "tree-sitter", importMeta);
                    ctx.addEdge(moduleId, importNodeId, EdgeKind.CONTAINS,
                        startPoint.row + 1, startPoint.column + 1);
                    logger.debug("[visit] {}  added imports + contains: module={} → import={}",
                        depthPrefix(depth), moduleId, importNodeId);
                }
            } else {
                logger.trace("[visit] {}→ import (skipped, empty)", depthPrefix(depth));
            }
            return true;
        }

        return visited;
    }

    /**
     * 处理表达式节点：方法调用、super 调用、对象创建、标识符引用。
     *
     * @param node 当前 AST 节点
     * @param ctx 提取上下文
     * @param extractor 语言提取器配置
     * @param source 源码内容
     * @param depth 当前深度
     * @param visited 是否已被处理
     * @return true 如果节点已被处理
     */
    private boolean handleExpressions(TSNode node, ExtractorContext ctx,
            LanguageExtractor extractor, String source, int depth, boolean visited) {
        String nodeType = ts.ts_node_type(node);

        if (!visited && extractor.methodInvocationTypes().contains(nodeType)) {
            logger.trace("[visit] {}→ processMethodInvocation '{}'", depthPrefix(depth), nodeType);
            processMethodInvocation(node, ctx, extractor, false);
            return true;
        }

        if (!visited && extractor.superMethodTypes().contains(nodeType)) {
            logger.trace("[visit] {}→ processMethodInvocation (super) '{}'", depthPrefix(depth), nodeType);
            processMethodInvocation(node, ctx, extractor, true);
            return true;
        }

        if (!visited && "object_creation_expression".equals(nodeType)) {
            logger.trace("[visit] {}→ processObjectCreation '{}'", depthPrefix(depth), nodeType);
            processObjectCreation(node, ctx, ts);
            return true;
        }

        if (!visited && "identifier".equals(nodeType) && ctx.getCurrentMethodId() != null) {
            String identName = TreeSitterHelpers.getNodeText(node, source, ts);
            if (identName != null && !identName.isEmpty()) {
                TSPoint startPoint = ts.ts_node_start_point(node);
                ctx.addIdentifierRef(identName, startPoint.row + 1, startPoint.column + 1);
            }
            return true;
        }

        return visited;
    }

    /**
     * 生成缩进前缀，配合 scopeDepth 可视化 AST 层级。
     *
     * <p>每级深度生成 2 个空格的缩进，用于在日志中区分嵌套层级。
     *
     * @param depth 当前深度（0 表示顶级）
     * @return 缩进字符串
     */
    private static String depthPrefix(int depth) {
        if (depth <= 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        return sb.toString();
    }

    // =========================================================================
    // Type declaration: Class / Interface / Enum
    // =========================================================================

    /**
     * 处理类或接口声明节点。
     *
     * <p><b>处理流程：</b>
     * <ol>
     *   <li>提取类名/接口名</li>
     *   <li>提取文档注释（Javadoc）</li>
     *   <li>提取可见性和修饰符（static, abstract）</li>
     *   <li>创建节点并推入作用域</li>
     *   <li>处理 EXTENDS 边（类继承）</li>
     *   <li>处理 IMPLEMENTS 边（接口实现）</li>
     *   <li>遍历 body 子节点，提取成员（方法、字段等）</li>
     *   <li>调用 Lombok 合成成员</li>
     *   <li>退出作用域</li>
     * </ol>
     *
     * <p><b>示例：</b>
     * <pre>
     * 输入源码：
     *   public class Foo extends Bar implements Baz {
     *       private String name;
     *   }
     *
     * 输出：
     *   - 节点：CLASS(Foo)
     *   - 边：EXTENDS(Foo→Bar), IMPLEMENTS(Foo→Baz), CONTAINS(Foo→name)
     * </pre>
     *
     * @param node AST 节点
     * @param ctx 提取上下文
     * @param extractor 语言提取器配置
     * @param kind 节点类型（CLASS 或 INTERFACE）
     */
    private void visitClass(TSNode node, ExtractorContext ctx, LanguageExtractor extractor, NodeKind kind) {
        String source = ctx.getSource();
        TreeSitterNative ts = ctx.getTreeSitter();
        int depth = ctx.getScopeDepth();

        // 获取类名
        TSNode nameNode = TreeSitterHelpers.getChildByField(node, extractor.nameField(), ts);
        String className = extractTypeName(nameNode, source, ts, depth, "class");
        if (className == null) return;

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

        // 处理 EXTENDS（仅类和接口支持）
        processExtendsForType(node, classNode, ctx, extractor, depth);

        // 处理 IMPLEMENTS
        processImplementsForType(node, classNode, ctx, extractor, depth);

        // 遍历 body 中的成员
        int nodesBefore = ctx.getNodes().size();
        visitBodyMembers(node, ctx, extractor, depth);

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

    /**
     * 处理枚举声明节点。
     *
     * <p><b>处理流程：</b>
     * <ol>
     *   <li>提取枚举名</li>
     *   <li>提取可见性和修饰符</li>
     *   <li>创建节点并推入作用域</li>
     *   <li>处理 IMPLEMENTS 边（枚举可实现接口）</li>
     *   <li>遍历 body 子节点，提取枚举常量和方法</li>
     *   <li>退出作用域</li>
     * </ol>
     *
     * <p><b>示例：</b>
     * <pre>
     * 输入源码：
     *   public enum Color { RED, GREEN, BLUE }
     *
     * 输出：
     *   - 节点：ENUM(Color), ENUM_MEMBER(RED), ENUM_MEMBER(GREEN), ENUM_MEMBER(BLUE)
     *   - 边：CONTAINS(ENUM→ENUM_MEMBER) × 3
     * </pre>
     *
     * @param node AST 节点
     * @param ctx 提取上下文
     * @param extractor 语言提取器配置
     */
    private void visitEnum(TSNode node, ExtractorContext ctx, LanguageExtractor extractor) {
        String source = ctx.getSource();
        TreeSitterNative ts = ctx.getTreeSitter();
        int depth = ctx.getScopeDepth();

        // 获取枚举名
        TSNode nameNode = TreeSitterHelpers.getChildByField(node, extractor.nameField(), ts);
        String enumName = extractTypeName(nameNode, source, ts, depth, "enum");
        if (enumName == null) return;

        // 获取可见性和修饰符
        Visibility visibility = extractor.getVisibility(node, ctx);
        boolean isStatic = extractor.isStatic(node, ctx);
        boolean isAbstract = extractor.isAbstract(node, ctx);

        logger.debug("[enum] {}创建 ENUM '{}' vis={}", depthPrefix(depth), enumName, visibility);

        // 创建节点
        com.codegraph.core.Node enumNode = ctx.createNode(
            NodeKind.ENUM, enumName, node,
            visibility, isStatic, isAbstract,
            null, null, null
        );
        logger.trace("[enum] {}  nodeId={}", depthPrefix(depth), enumNode.getId());

        // 进入枚举作用域
        ctx.pushScope(enumName, enumNode.getId());

        // 处理接口实现（枚举可实现接口）
        processImplementsForType(node, enumNode, ctx, extractor, depth);

        // 遍历 body 中的成员
        int nodesBefore = ctx.getNodes().size();
        visitBodyMembers(node, ctx, extractor, depth);

        int bodyNodesAdded = ctx.getNodes().size() - nodesBefore;
        logger.debug("[enum] {}完成: '{}' body中提取了 {} 个成员", depthPrefix(depth), enumName, bodyNodesAdded);

        // 离开枚举作用域
        ctx.popScope();
    }

    /**
     * 从名称节点提取类型名称（类名、接口名、枚举名）。
     *
     * <p><b>提取逻辑：</b>
     * <ol>
     *   <li>检查名称节点是否为空</li>
     *   <li>从节点提取文本内容</li>
     *   <li>检查文本是否为空</li>
     *   <li>返回类型名称或 null（如果提取失败）</li>
     * </ol>
     *
     * @param nameNode 名称节点
     * @param source 源码内容
     * @param ts tree-sitter 原生接口
     * @param depth 当前深度
     * @param typeLabel 类型标签（用于日志，如 "class", "enum"）
     * @return 类型名称，如果提取失败返回 null
     */
    private String extractTypeName(TSNode nameNode, String source, TreeSitterNative ts, int depth, String typeLabel) {
        if (ts.ts_node_is_null(nameNode)) {
            logger.trace("[{}] {}nameNode is null, skipping", typeLabel, depthPrefix(depth));
            return null;
        }
        String typeName = TreeSitterHelpers.getNodeText(nameNode, source, ts);
        if (typeName == null || typeName.isEmpty()) {
            logger.trace("[{}] {}name is empty, skipping", typeLabel, depthPrefix(depth));
            return null;
        }
        return typeName;
    }

    /**
     * 处理类型声明的 EXTENDS 边（类继承）。
     *
     * @param node AST 节点
     * @param typeEntity 类型实体节点
     * @param ctx 提取上下文
     * @param extractor 语言提取器配置
     * @param depth 当前深度
     */
    private void processExtendsForType(TSNode node, com.codegraph.core.Node typeEntity,
                                       ExtractorContext ctx, LanguageExtractor extractor, int depth) {
        int edgesBefore = ctx.getEdges().size();
        processExtends(node, typeEntity, ctx, extractor);
        int extendsAdded = ctx.getEdges().size() - edgesBefore;
        if (extendsAdded > 0) {
            logger.trace("[class] {}  added {} EXTENDS edge(s)", depthPrefix(depth), extendsAdded);
        }
    }

    /**
     * 处理类型声明的 IMPLEMENTS 边（接口实现）。
     *
     * @param node AST 节点
     * @param typeEntity 类型实体节点
     * @param ctx 提取上下文
     * @param extractor 语言提取器配置
     * @param depth 当前深度
     */
    private void processImplementsForType(TSNode node, com.codegraph.core.Node typeEntity,
                                          ExtractorContext ctx, LanguageExtractor extractor, int depth) {
        int edgesBefore = ctx.getEdges().size();
        processImplements(node, typeEntity, ctx, extractor);
        int implementsAdded = ctx.getEdges().size() - edgesBefore;
        if (implementsAdded > 0) {
            logger.trace("[{}] {}  added {} IMPLEMENTS edge(s)",
                typeEntity.getKind(), depthPrefix(depth), implementsAdded);
        }
    }

    /**
     * 遍历类型体（class body / enum body）中的成员节点。
     *
     * <p><b>遍历逻辑：</b>
     * <ol>
     *   <li>获取 body 字段节点</li>
     *   <li>如果 body 不为空，遍历其所有命名子节点</li>
     *   <li>对每个子节点递归调用 visitNode</li>
     * </ol>
     *
     * @param node 类型声明节点
     * @param ctx 提取上下文
     * @param extractor 语言提取器配置
     * @param depth 当前深度
     */
    private void visitBodyMembers(TSNode node, ExtractorContext ctx, LanguageExtractor extractor, int depth) {
        TreeSitterNative ts = ctx.getTreeSitter();
        TSNode body = TreeSitterHelpers.getChildByField(node, extractor.bodyField(), ts);
        if (!ts.ts_node_is_null(body)) {
            int childCount = ts.ts_node_named_child_count(body);
            logger.trace("[body] {}  body has {} named children", depthPrefix(depth), childCount);
            for (int i = 0; i < childCount; i++) {
                TSNode child = ts.ts_node_named_child(body, i);
                visitNode(child, ctx, extractor);
            }
        } else {
            logger.trace("[body] {}  no body field found", depthPrefix(depth));
        }
    }

    // =========================================================================
    // Method / Constructor
    // =========================================================================

    /**
     * 处理方法或构造器声明节点。
     *
     * <p><b>处理流程：</b>
     * <ol>
     *   <li>提取方法名（构造器命名为 "&lt;init&gt;"）</li>
     *   <li>提取可见性和修饰符（static, abstract）</li>
     *   <li>提取方法签名（参数列表）</li>
     *   <li>提取返回类型（仅非构造器）</li>
     *   <li>提取文档注释</li>
     *   <li>创建方法节点</li>
     *   <li>进入方法上下文（用于收集 CALLS 边）</li>
     *   <li>遍历方法体，收集调用引用</li>
     *   <li>退出方法上下文</li>
     * </ol>
     *
     * <p><b>示例：</b>
     * <pre>
     * 输入源码：
     *   public void foo(String name) { bar(); }
     *
     * 输出：
     *   - 节点：METHOD(foo)
     *     - signature: "void foo(String name)"
     *     - returnType: "void"
     *     - visibility: PUBLIC
     *   - 边：CONTAINS(CLASS→METHOD)
     *   - 调用引用：bar()（待 resolvePendingReferences 解析）
     * </pre>
     *
     * @param node AST 节点
     * @param ctx 提取上下文
     * @param extractor 语言提取器配置
     * @param nodeType 节点类型（"method_declaration" 或 "constructor_declaration"）
     */
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

    /**
     * 处理字段声明节点。
     *
     * <p><b>处理流程：</b>
     * <ol>
     *   <li>遍历字段声明的所有子节点</li>
     *   <li>识别 variable_declarator 类型的子节点（一个字段声明可能包含多个变量）</li>
     *   <li>提取每个字段的名称、可见性和修饰符</li>
     *   <li>提取文档注释（共享同一声明的注释）</li>
     *   <li>为每个变量创建 FIELD 节点</li>
     * </ol>
     *
     * <p><b>多变量声明支持：</b>
     * <pre>
     * 输入源码：
     *   private int a, b, c;  // 单个声明，三个变量
     *
     * 输出：
     *   - 节点：FIELD(a), FIELD(b), FIELD(c)
     *   - 边：CONTAINS(CLASS→FIELD) × 3
     * </pre>
     *
     * @param node AST 节点
     * @param ctx 提取上下文
     * @param extractor 语言提取器配置
     */
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

    /**
     * 处理枚举常量节点。
     *
     * <p><b>处理流程：</b>
     * <ol>
     *   <li>尝试从 name 字段提取常量名</li>
     *   <li>如果 name 字段为空，直接从节点文本提取</li>
     *   <li>创建 ENUM_MEMBER 节点（默认 PUBLIC, STATIC）</li>
     * </ol>
     *
     * <p><b>枚举常量特性：</b>
     * <ul>
     *   <li>默认可见性：PUBLIC</li>
     *   <li>默认修饰符：static（枚举常量是类级别的）</li>
     *   <li>无法为抽象（abstract）</li>
     * </ul>
     *
     * <p><b>示例：</b>
     * <pre>
     * 输入源码：
     *   enum Color { RED, GREEN, BLUE }
     *
     * 输出：
     *   - 节点：ENUM_MEMBER(RED), ENUM_MEMBER(GREEN), ENUM_MEMBER(BLUE)
     *   - 边：CONTAINS(ENUM→ENUM_MEMBER) × 3
     * </pre>
     *
     * @param node AST 节点
     * @param ctx 提取上下文
     * @param extractor 语言提取器配置
     */
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

    /**
     * 处理类继承关系，生成 EXTENDS 边。
     *
     * <p><b>处理流程：</b>
     * <ol>
     *   <li>获取 superclass 字段节点</li>
     *   <li>提取父类名称（支持泛型类型）</li>
     *   <li>构建父类的外部节点 ID</li>
     *   <li>创建 EXTENDS 边（provenance=tree-sitter）</li>
     * </ol>
     *
     * <p><b>示例：</b>
     * <pre>
     * 输入源码：
     *   class Foo extends Bar&lt;String&gt; {}
     *
     * 输出：
     *   - 边：EXTENDS(Foo → Bar) （泛型参数被忽略）
     * </pre>
     *
     * @param classNode 类声明 AST 节点
     * @param classEntity 类实体节点
     * @param ctx 提取上下文
     * @param extractor 语言提取器配置
     */
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

    /**
     * 处理接口实现关系，生成 IMPLEMENTS 边。
     *
     * <p><b>处理流程：</b>
     * <ol>
     *   <li>获取 interfaces 字段节点</li>
     *   <li>遍历接口列表（支持 type_list 格式：implements Foo, Bar）</li>
     *   <li>提取每个接口名称</li>
     *   <li>为每个接口创建 IMPLEMENTS 边</li>
     * </ol>
     *
     * <p><b>示例：</b>
     * <pre>
     * 输入源码：
     *   class Foo implements Bar, Baz {}
     *
     * 输出：
     *   - 边：IMPLEMENTS(Foo → Bar), IMPLEMENTS(Foo → Baz)
     * </pre>
     *
     * @param classNode 类/枚举声明 AST 节点
     * @param classEntity 类/枚举实体节点
     * @param ctx 提取上下文
     * @param extractor 语言提取器配置
     */
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
     *
     * <p><b>支持的节点类型：</b>
     * <ol>
     *   <li><b>type_identifier</b>：直接返回节点文本</li>
     *   <li><b>superclass</b>：递归查找内部的 type_identifier</li>
     *   <li><b>generic_type</b>：提取泛型的基础类型名（如 List&lt;String&gt; → List）</li>
     *   <li><b>type_list</b>：递归提取第一个类型标识符</li>
     *   <li><b>scoped_identifier</b>：返回完整的限定名（如 java.util.List）</li>
     * </ol>
     *
     * <p><b>示例：</b>
     * <table>
     *   <tr><th>输入节点类型</th><th>源码</th><th>输出</th></tr>
     *   <tr><td>type_identifier</td><td>Foo</td><td>Foo</td></tr>
     *   <tr><td>generic_type</td><td>List&lt;String&gt;</td><td>List</td></tr>
     *   <tr><td>scoped_identifier</td><td>java.util.Map</td><td>java.util.Map</td></tr>
     * </table>
     *
     * @param node AST 节点
     * @param source 源码内容
     * @param ts tree-sitter 原生接口
     * @return 简单类型名称，如果提取失败返回 null
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
    // Object creation expression (new ClassName()) → instantiates
    // =========================================================================

    /**
     * 处理 object_creation_expression（new ClassName()），
     * 提取被实例化的类名，记录为未解析引用供 resolution 阶段创建 instantiates 边。
     */
    private void processObjectCreation(TSNode node, ExtractorContext ctx, TreeSitterNative ts) {
        String source = ctx.getSource();

        // 提取被实例化的类型名: object_creation_expression → type: type_identifier
        TSNode typeNode = TreeSitterHelpers.getChildByField(node, "type", ts);
        if (ts.ts_node_is_null(typeNode)) {
            // 备用：遍历 named children 找 type_identifier
            int childCount = ts.ts_node_named_child_count(node);
            for (int i = 0; i < childCount; i++) {
                TSNode child = ts.ts_node_named_child(node, i);
                if ("type_identifier".equals(ts.ts_node_type(child))
                    || "generic_type".equals(ts.ts_node_type(child))) {
                    typeNode = child;
                    break;
                }
            }
        }

        if (ts.ts_node_is_null(typeNode)) {
            logger.debug("[instantiates] processObjectCreation: no type node found");
            return;
        }

        String typeName = TreeSitterHelpers.getNodeText(typeNode, source, ts);
        if (typeName == null || typeName.isEmpty()) return;

        TSPoint startPoint = ts.ts_node_start_point(node);
        int line = startPoint.row + 1;
        int column = startPoint.column + 1;

        String callerId = ctx.getCurrentMethodId();
        if (callerId != null) {
            ctx.getUnresolvedRefs().add(new com.codegraph.resolution.frameworks.UnresolvedRef(
                callerId, typeName, "instantiates",
                ctx.getFilePath(), line, column));
            logger.debug("[instantiates] new {}() at {}:{}, caller={}", typeName, line, column, callerId);
        }
    }

    // =========================================================================
    // Heuristic call reference resolution
    // =========================================================================

    /**
     * 在 AST 遍历完成后，解析收集到的方法调用引用，生成 CALLS 边。
     *
     * <p><b>启发式匹配策略：</b>
     * <ol>
     *   <li><b>this.X 调用</b>：在本类中查找方法</li>
     *   <li><b>无 receiver 调用</b>：查找同名方法，多个匹配时选第一个</li>
     *   <li><b>receiver.method() 调用</b>：优先匹配导出方法，否则选第一个</li>
     *   <li><b>super.X 调用</b>：在父类中查找方法</li>
     * </ol>
     *
     * <p><b>未解析引用处理：</b>无法在当前文件内解析的调用会记录为 UnresolvedRef，
     * 供后续跨文件 resolution 阶段处理。
     */
    public void resolvePendingReferences(ExtractorContext ctx) {
        List<com.codegraph.core.Node> methods = ctx.getNodes().stream()
            .filter(n -> n.getKind() == NodeKind.METHOD)
            .collect(java.util.stream.Collectors.toList());

        java.util.Map<String, List<com.codegraph.core.Node>> methodNameIndex = buildNameIndex(methods);

        logger.debug("[calls] === 启发式 CALLS 边解析开始 ===");
        logger.debug("[calls] 待解析调用引用: {} 个普通调用, {} 个 super 调用",
            ctx.getCallReferences().size(), ctx.getSuperCallReferences().size());
        logger.debug("[calls] 当前文件已知方法数量: {}", methods.size());

        if (logger.isDebugEnabled()) {
            for (com.codegraph.core.Node method : methods) {
                logger.debug("[calls]   方法: {} (qualifiedName={}, exported={})",
                    method.getName(), method.getQualifiedName(), method.isExported());
            }
        }

        int callsEdgesAdded = 0;

        // 解析普通方法调用
        callsEdgesAdded += resolveRegularCallReferences(ctx, methods, methodNameIndex);

        // 解析 super 方法调用
        callsEdgesAdded += resolveSuperCallReferences(ctx, methods);

        logger.debug("[calls] === 启发式 CALLS 边解析完成: 生成 {} 条边 ===", callsEdgesAdded);
    }

    /**
     * 解析普通方法调用引用。
     *
     * @param ctx 提取上下文
     * @param methods 方法节点列表
     * @param methodNameIndex 方法名索引
     * @return 成功生成的 CALLS 边数量
     */
    private int resolveRegularCallReferences(ExtractorContext ctx,
            List<com.codegraph.core.Node> methods,
            java.util.Map<String, List<com.codegraph.core.Node>> methodNameIndex) {
        int edgesAdded = 0;
        for (ExtractorContext.CallReference ref : ctx.getCallReferences()) {
            logger.debug("[calls] ---- 解析调用: {} → {} (receiver='{}', chained={}, at {}:{})",
                ref.callerId, ref.calleeName, ref.receiverType, ref.isChained, ref.line, ref.column);

            String calleeId = resolveCalleeId(ref, methods, methodNameIndex, ctx);
            if (calleeId != null) {
                addHeuristicEdge(ctx, ref.callerId, calleeId, EdgeKind.CALLS,
                    ref.line, ref.column);
                edgesAdded++;
                logger.debug("[calls]   ✓ 解析成功: caller={} → callee={} [heuristic]",
                    ref.callerId, calleeId);
            } else {
                recordUnresolvedRef(ctx, ref.callerId, ref.calleeName, "calls",
                    ref.line, ref.column);
                logger.debug("[calls]   ✗ 无法解析: {} → {} at {}:{}",
                    ref.callerId, ref.calleeName, ref.line, ref.column);
            }
        }
        return edgesAdded;
    }

    /**
     * 解析 super 方法调用引用。
     *
     * @param ctx 提取上下文
     * @param methods 方法节点列表
     * @return 成功生成的 CALLS 边数量
     */
    private int resolveSuperCallReferences(ExtractorContext ctx,
            List<com.codegraph.core.Node> methods) {
        int edgesAdded = 0;
        for (ExtractorContext.CallReference ref : ctx.getSuperCallReferences()) {
            logger.debug("[calls] ---- 解析 super 调用: {} → {} (at {}:{})",
                ref.callerId, ref.calleeName, ref.line, ref.column);

            String parentClassId = resolveSuperClassId(ctx);
            if (parentClassId != null) {
                logger.debug("[calls]   父类 ID: {}", parentClassId);
                String parentMethodQName = parentClassId + "." + ref.calleeName;
                logger.debug("[calls]   查找父类方法 qualifiedName: {}", parentMethodQName);
                String calleeId = findMethodByQualifiedName(parentMethodQName, methods);
                if (calleeId != null) {
                    addHeuristicEdge(ctx, ref.callerId, calleeId, EdgeKind.CALLS,
                        ref.line, ref.column);
                    edgesAdded++;
                    logger.debug("[calls]   ✓ SUPER 调用解析成功: {} → {} [heuristic]",
                        ref.callerId, calleeId);
                } else {
                    logger.debug("[calls]   ✗ 父类中未找到方法: {}", parentMethodQName);
                }
            } else {
                logger.debug("[calls]   ✗ 无法获取父类 ID（当前类可能没有 EXTENDS 边）");
            }
        }
        return edgesAdded;
    }

    // =========================================================================
    // Identifier reference resolution → references edges
    // =========================================================================

    /**
     * 解析在 AST 遍历期间收集到的标识符引用，匹配同文件中的
     * FIELD 和 ENUM_MEMBER 节点，生成 REFERENCES 边（valueRef）。
     *
     * <p><b>匹配策略：</b>
     * <ol>
     *   <li>按标识符名称在同文件的 FIELD 和 ENUM_MEMBER 节点中查找</li>
     *   <li>去重处理：同一方法对同一目标的多次引用只生成一条边</li>
     *   <li>生成的边标记为 valueRef（值引用），provenance=heuristic</li>
     * </ol>
     *
     * <p><b>示例：</b>
     * <pre>
     * 源码：
     *   class Foo {
     *       private int x;
     *       void bar() { System.out.println(x); }
     *   }
     *
     * 生成边：
     *   REFERENCES(Foo.bar → Foo.x) （valueRef=true）
     * </pre>
     */
    private void resolveIdentifierReferences(ExtractorContext ctx) {
        List<ExtractorContext.IdentifierRef> idRefs = ctx.getIdentifierRefs();
        if (idRefs.isEmpty()) return;

        // 收集同文件中的 FIELD、ENUM_MEMBER 节点并建立索引
        java.util.List<com.codegraph.core.Node> fieldAndEnumNodes = ctx.getNodes().stream()
            .filter(n -> n.getKind() == NodeKind.FIELD || n.getKind() == NodeKind.ENUM_MEMBER)
            .collect(java.util.stream.Collectors.toList());

        java.util.Map<String, List<com.codegraph.core.Node>> fieldIndex = buildNameIndex(fieldAndEnumNodes);

        if (fieldIndex.isEmpty()) return;

        logger.debug("[references] === 标识符引用解析开始 ===");
        logger.debug("[references] 待解析标识符引用: {} 个, 同文件 FIELD/ENUM_MEMBER: {} 个",
            idRefs.size(),
            fieldIndex.values().stream().mapToInt(List::size).sum());

        java.util.Set<String> addedEdges = new java.util.HashSet<>();
        int refEdgesAdded = 0;

        for (ExtractorContext.IdentifierRef ref : idRefs) {
            List<com.codegraph.core.Node> candidates = fieldIndex.get(ref.identifierName);
            if (candidates == null || candidates.isEmpty()) continue;

            for (com.codegraph.core.Node target : candidates) {
                String edgeKey = ref.methodId + "|" + target.getId();
                if (addedEdges.contains(edgeKey)) continue;
                addedEdges.add(edgeKey);

                addValueReferenceEdge(ctx, ref.methodId, target.getId(),
                    ref.line, ref.column, ref.identifierName);
                refEdgesAdded++;
                logger.debug("[references]   {} → {} (field={})",
                    ref.methodId, target.getId(), ref.identifierName);
            }
        }

        logger.debug("[references] === 标识符引用解析完成: 生成 {} 条 references 边 ===", refEdgesAdded);
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

    // =========================================================================
    // Common utility methods for reference resolution
    // =========================================================================

    /**
     * 构建节点名称到节点列表的索引，用于快速查找同名节点。
     *
     * <p><b>索引结构：</b>
     * <pre>
     *   Map<String, List<Node>>
     *   ├── "foo" → [Node("Foo.bar"), Node("Baz.foo")]
     *   ├── "bar" → [Node("Foo.bar")]
     *   └── ...
     * </pre>
     *
     * @param nodes 节点列表
     * @return 名称索引 Map
     */
    private java.util.Map<String, List<com.codegraph.core.Node>> buildNameIndex(
            List<com.codegraph.core.Node> nodes) {
        java.util.Map<String, List<com.codegraph.core.Node>> index = new java.util.HashMap<>();
        for (com.codegraph.core.Node node : nodes) {
            index.computeIfAbsent(node.getName(), k -> new java.util.ArrayList<>()).add(node);
        }
        return index;
    }

    /**
     * 添加启发式边（provenance=heuristic）。
     *
     * <p><b>元数据：</b>设置 provenance 为 "heuristic"，表示该边是通过启发式规则推断的。
     *
     * @param ctx 提取上下文
     * @param sourceId 源节点 ID
     * @param targetId 目标节点 ID
     * @param edgeKind 边类型
     * @param line 行号
     * @param column 列号
     */
    private void addHeuristicEdge(ExtractorContext ctx, String sourceId, String targetId,
                                  EdgeKind edgeKind, int line, int column) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("provenance", "heuristic");
        ctx.addEdge(sourceId, targetId, edgeKind, line, column, "heuristic", metadata);
    }

    /**
     * 添加值引用边（REFERENCES 类型，valueRef=true）。
     *
     * <p><b>元数据：</b>
     * <ul>
     *   <li>valueRef: true — 表示这是值引用（如字段访问）</li>
     *   <li>provenance: heuristic — 表示该边是通过启发式规则推断的</li>
     * </ul>
     *
     * @param ctx 提取上下文
     * @param sourceId 源节点 ID（通常是方法）
     * @param targetId 目标节点 ID（通常是字段或枚举成员）
     * @param line 行号
     * @param column 列号
     * @param fieldName 字段名称（用于日志）
     */
    private void addValueReferenceEdge(ExtractorContext ctx, String sourceId, String targetId,
                                       int line, int column, String fieldName) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("valueRef", true);
        metadata.put("provenance", "heuristic");
        ctx.addEdge(sourceId, targetId, EdgeKind.REFERENCES, line, column, "heuristic", metadata);
    }

    /**
     * 记录未解析引用，供后续跨文件 resolution 阶段处理。
     *
     * <p><b>未解析引用的场景：</b>
     * <ul>
     *   <li>调用的方法在当前文件中不存在</li>
     *   <li>引用的字段/枚举成员在当前文件中不存在</li>
     *   <li>需要跨文件或跨模块解析的引用</li>
     * </ul>
     *
     * @param ctx 提取上下文
     * @param callerId 调用者 ID
     * @param targetName 目标名称
     * @param refType 引用类型（"calls", "references", "instantiates"）
     * @param line 行号
     * @param column 列号
     */
    private void recordUnresolvedRef(ExtractorContext ctx, String callerId, String targetName,
                                     String refType, int line, int column) {
        ctx.getUnresolvedRefs().add(new com.codegraph.resolution.frameworks.UnresolvedRef(
            callerId, targetName, refType,
            ctx.getFilePath(), line, column));
    }
}
