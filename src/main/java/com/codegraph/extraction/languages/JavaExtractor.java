package com.codegraph.extraction.languages;

import com.codegraph.core.types.Visibility;
import com.codegraph.extraction.bridge.TSNode;
import com.codegraph.extraction.bridge.TreeSitterLibrary;
import com.codegraph.extraction.bridge.TreeSitterNative;
import com.codegraph.extraction.tree_sitter.*;

import java.util.*;

/**
 * Java 语言提取配置。
 *
 * 定义 tree-sitter-java AST 节点类型到 codegraph 符号类型的映射，
 * 以及签名、可见性、修饰符等提取钩子。
 */
public class JavaExtractor implements LanguageExtractor {

    // =========================================================================
    // Lombok annotation names
    // =========================================================================

    private static final Set<String> LOMBOK_GETTER_ANNOTATIONS = new HashSet<>(Arrays.asList(
        "Getter", "lombok.Getter", "Data", "lombok.Data",
        "Value", "lombok.Value"
    ));

    private static final Set<String> LOMBOK_SETTER_ANNOTATIONS = new HashSet<>(Arrays.asList(
        "Setter", "lombok.Setter", "Data", "lombok.Data",
        "Value", "lombok.Value"
    ));

    private static final Set<String> LOMBOK_BUILDER_ANNOTATIONS = new HashSet<>(Arrays.asList(
        "Builder", "lombok.Builder", "SuperBuilder", "lombok.SuperBuilder"
    ));

    @Override
    public Set<String> classTypes() {
        return new HashSet<>(Collections.singletonList("class_declaration"));
    }

    @Override
    public Set<String> methodTypes() {
        return new HashSet<>(Arrays.asList("method_declaration", "constructor_declaration"));
    }

    @Override
    public Set<String> interfaceTypes() {
        return new HashSet<>(Arrays.asList("interface_declaration", "annotation_type_declaration"));
    }

    @Override
    public Set<String> enumTypes() {
        return new HashSet<>(Collections.singletonList("enum_declaration"));
    }

    @Override
    public Set<String> enumMemberTypes() {
        return new HashSet<>(Collections.singletonList("enum_constant"));
    }

    @Override
    public Set<String> fieldTypes() {
        return new HashSet<>(Collections.singletonList("field_declaration"));
    }

    @Override
    public Set<String> importTypes() {
        return new HashSet<>(Collections.singletonList("import_declaration"));
    }

    @Override
    public Set<String> packageTypes() {
        return new HashSet<>(Collections.singletonList("package_declaration"));
    }

    @Override
    public Set<String> methodInvocationTypes() {
        return new HashSet<>(Collections.singletonList("method_invocation"));
    }

    @Override
    public Set<String> superMethodTypes() {
        return new HashSet<>(Collections.singletonList("super_method_invocation"));
    }

    @Override
    public Set<String> fieldAccessTypes() {
        return new HashSet<>(Collections.singletonList("field_access"));
    }

    @Override
    public String nameField() { return "name"; }

    @Override
    public String bodyField() { return "body"; }

    @Override
    public String paramsField() { return "parameters"; }

    @Override
    public String returnField() { return "type"; }

    // =========================================================================
    // Extraction hooks
    // =========================================================================

    @Override
    public String getSignature(TSNode node, String source) {
        TreeSitterNative ts = TreeSitterLibrary.getTreeSitter();

        // 获取方法名
        TSNode nameNode = TreeSitterHelpers.getChildByField(node, nameField(), ts);
        String methodName = ts.ts_node_is_null(nameNode) ? "" : TreeSitterHelpers.getNodeText(nameNode, source, ts);

        // 获取参数
        TSNode paramsNode = TreeSitterHelpers.getChildByField(node, paramsField(), ts);
        String params = ts.ts_node_is_null(paramsNode) ? "" : TreeSitterHelpers.getNodeText(paramsNode, source, ts);

        return methodName + (params.isEmpty() ? "()" : params);
    }

    @Override
    public Visibility getVisibility(TSNode node, ExtractorContext ctx) {
        String modText = getModifiersText(node, ctx);
        if (modText == null) {
            return Visibility.INTERNAL; // package-private
        }
        if (modText.contains("public")) return Visibility.PUBLIC;
        if (modText.contains("private")) return Visibility.PRIVATE;
        if (modText.contains("protected")) return Visibility.PROTECTED;
        return Visibility.INTERNAL; // package-private
    }

    @Override
    public boolean isStatic(TSNode node, ExtractorContext ctx) {
        String modText = getModifiersText(node, ctx);
        return modText != null && modText.contains("static");
    }

    @Override
    public boolean isAbstract(TSNode node, ExtractorContext ctx) {
        String modText = getModifiersText(node, ctx);
        return modText != null && modText.contains("abstract");
    }

    /**
     * tree-sitter-java 中 modifiers 是 named child（不是 field），
     * 需要通过类型名查找。
     */
    private String getModifiersText(TSNode node, ExtractorContext ctx) {
        TreeSitterNative ts = ctx.getTreeSitter();
        TSNode modifiers = TreeSitterHelpers.findNamedChildByType(node, "modifiers", ts);
        if (ts.ts_node_is_null(modifiers)) return null;
        return TreeSitterHelpers.getNodeText(modifiers, ctx.getSource(), ts);
    }

    // =========================================================================
    // Package & Import
    // =========================================================================

    @Override
    public String extractPackage(TSNode node, String source) {
        // package_declaration → scoped_identifier (dotted name)
        TreeSitterNative ts = TreeSitterLibrary.getTreeSitter();
        return extractDottedName(node, source, ts);
    }

    @Override
    public ImportInfo extractImport(TSNode node, String source) {
        // import_declaration → scoped_identifier (dotted name)
        TreeSitterNative ts = TreeSitterLibrary.getTreeSitter();
        String moduleName = extractDottedName(node, source, ts);
        return new ImportInfo(moduleName, moduleName);
    }

    /**
     * 从节点中提取点分名称（如 com.example.Hello）。
     * 遍历节点的 named children，拼接 identifier 文本。
     */
    private String extractDottedName(TSNode node, String source, TreeSitterNative ts) {
        // 找到 scoped_identifier 子节点
        int childCount = ts.ts_node_named_child_count(node);
        for (int i = 0; i < childCount; i++) {
            TSNode child = ts.ts_node_named_child(node, i);
            String type = ts.ts_node_type(child);
            if ("scoped_identifier".equals(type)) {
                return collectIdentifiers(child, source, ts);
            }
        }
        return "";
    }

    private String collectIdentifiers(TSNode node, String source, TreeSitterNative ts) {
        StringBuilder sb = new StringBuilder();
        int childCount = ts.ts_node_named_child_count(node);
        for (int i = 0; i < childCount; i++) {
            TSNode child = ts.ts_node_named_child(node, i);
            String type = ts.ts_node_type(child);
            if ("identifier".equals(type)) {
                if (sb.length() > 0) sb.append(".");
                sb.append(TreeSitterHelpers.getNodeText(child, source, ts));
            } else if ("scoped_identifier".equals(type)) {
                // nested scoped_identifier (e.g. java.util.List)
                String nested = collectIdentifiers(child, source, ts);
                if (sb.length() > 0) sb.append(".");
                sb.append(nested);
            }
        }
        return sb.toString();
    }

    // =========================================================================
    // Lombok member synthesis
    // =========================================================================

    @Override
    public void synthesizeMembers(TSNode classNode, ExtractorContext ctx) {
        TreeSitterNative ts = ctx.getTreeSitter();
        String source = ctx.getSource();

        // 收集类上的 Lombok 注解
        TSNode body = TreeSitterHelpers.getChildByField(classNode, bodyField(), ts);
        if (ts.ts_node_is_null(body)) return;

        boolean hasGetter = false, hasSetter = false;
        boolean hasBuilder = false;

        // 检查类级别的注解
        List<TSNode> modifiers = getModifierList(classNode, ts);
        for (TSNode mod : modifiers) {
            String modText = TreeSitterHelpers.getNodeText(mod, source, ts).trim();
            if (modText.startsWith("@")) {
                String annoName = extractAnnotationName(modText);
                if (LOMBOK_GETTER_ANNOTATIONS.contains(annoName)) hasGetter = true;
                if (LOMBOK_SETTER_ANNOTATIONS.contains(annoName)) hasSetter = true;
                if (LOMBOK_BUILDER_ANNOTATIONS.contains(annoName)) hasBuilder = true;
            }
        }

        // 如果没有任何 Lombok 注解，跳过
        if (!hasGetter && !hasSetter && !hasBuilder) return;

        // 遍历类体中的字段
        int bodyChildCount = ts.ts_node_named_child_count(body);
        for (int i = 0; i < bodyChildCount; i++) {
            TSNode child = ts.ts_node_named_child(body, i);
            String type = ts.ts_node_type(child);

            if ("field_declaration".equals(type)) {
                String fieldName = extractFieldName(child, source, ts);
                String fieldType = extractFieldType(child, source, ts);
                if (fieldName == null || fieldName.isEmpty()) continue;

                // 检查字段级别注解
                boolean fieldHasGetter = hasGetter;
                boolean fieldHasSetter = hasSetter;
                List<TSNode> fieldMods = getModifierList(child, ts);
                for (TSNode fm : fieldMods) {
                    String fmText = TreeSitterHelpers.getNodeText(fm, source, ts).trim();
                    if (fmText.startsWith("@")) {
                        String annoName = extractAnnotationName(fmText);
                        if (LOMBOK_GETTER_ANNOTATIONS.contains(annoName)) fieldHasGetter = true;
                        if (LOMBOK_SETTER_ANNOTATIONS.contains(annoName)) fieldHasSetter = true;
                    }
                }

                if (fieldHasGetter) {
                    String getterName = lombokGetterName(fieldName, "boolean".equals(fieldType));
                    ctx.createNode(
                        com.codegraph.core.types.NodeKind.METHOD,
                        getterName, child,
                        Visibility.PUBLIC, false, false,
                        getterName + "()", fieldType,
                        "Synthesized by Lombok @Getter"
                    );
                }

                if (fieldHasSetter) {
                    String setterName = lombokSetterName(fieldName);
                    ctx.createNode(
                        com.codegraph.core.types.NodeKind.METHOD,
                        setterName, child,
                        Visibility.PUBLIC, false, false,
                        setterName + "(" + fieldType + ")", "void",
                        "Synthesized by Lombok @Setter"
                    );
                }
            }
        }
    }

    private String extractFieldName(TSNode fieldNode, String source, TreeSitterNative ts) {
        // field_declaration → variable_declarator → identifier
        int childCount = ts.ts_node_named_child_count(fieldNode);
        for (int i = 0; i < childCount; i++) {
            TSNode child = ts.ts_node_named_child(fieldNode, i);
            if ("variable_declarator".equals(ts.ts_node_type(child))) {
                TSNode nameNode = TreeSitterHelpers.getChildByField(child, nameField(), ts);
                if (!ts.ts_node_is_null(nameNode)) {
                    return TreeSitterHelpers.getNodeText(nameNode, source, ts);
                }
            }
        }
        return null;
    }

    private String extractFieldType(TSNode fieldNode, String source, TreeSitterNative ts) {
        // field_declaration → type_identifier / generic_type / array_type
        int childCount = ts.ts_node_named_child_count(fieldNode);
        for (int i = 0; i < childCount; i++) {
            TSNode child = ts.ts_node_named_child(fieldNode, i);
            String type = ts.ts_node_type(child);
            if ("type_identifier".equals(type) || "generic_type".equals(type)
                || "array_type".equals(type) || "integral_type".equals(type)
                || "floating_point_type".equals(type) || "boolean_type".equals(type)) {
                return TreeSitterHelpers.getNodeText(child, source, ts);
            }
        }
        return "Object";
    }

    private List<TSNode> getModifierList(TSNode node, TreeSitterNative ts) {
        // tree-sitter-java 中 modifiers 是 named child，不是 field name，
        // 使用 findNamedChildByType 而非 getChildByField 避免 C 库崩溃
        TSNode modifiers = TreeSitterHelpers.findNamedChildByType(node, "modifiers", ts);
        if (ts.ts_node_is_null(modifiers)) return Collections.emptyList();

        List<TSNode> result = new ArrayList<>();
        int childCount = ts.ts_node_named_child_count(modifiers);
        for (int i = 0; i < childCount; i++) {
            result.add(ts.ts_node_named_child(modifiers, i));
        }
        return result;
    }

    private String extractAnnotationName(String annotationText) {
        // @Getter → Getter, @lombok.Getter → lombok.Getter
        if (annotationText.startsWith("@")) {
            annotationText = annotationText.substring(1);
        }
        // 去掉括号部分 @Getter(value = ...)
        int parenIdx = annotationText.indexOf('(');
        if (parenIdx > 0) {
            annotationText = annotationText.substring(0, parenIdx);
        }
        return annotationText.trim();
    }

    private static String lombokGetterName(String fieldName, boolean isBoolean) {
        if (isBoolean && fieldName.startsWith("is")) {
            // isXxx → isXxx() 保持不变
            return "is" + capitalize(fieldName.substring(2));
        }
        return "get" + capitalize(fieldName);
    }

    private static String lombokSetterName(String fieldName) {
        return "set" + capitalize(fieldName);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
