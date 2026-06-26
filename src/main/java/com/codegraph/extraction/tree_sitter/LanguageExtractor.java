package com.codegraph.extraction.tree_sitter;

import com.codegraph.core.types.Visibility;
import com.codegraph.extraction.bridge.TSNode;

import java.util.Collections;
import java.util.Set;

/**
 * 语言提取器接口。
 *
 * 每种语言实现此接口来配置:
 *   - 节点类型映射（哪些 tree-sitter node type 对应哪些符号类型）
 *   - 提取钩子（签名、可见性、修饰符等）
 *   - 包/导入提取
 *   - 合成成员（如 Lombok）
 */
public interface LanguageExtractor {

    // =========================================================================
    // Node type -> symbol kind mappings
    // =========================================================================

    /** 类声明节点类型 */
    Set<String> classTypes();

    /** 方法/构造函数节点类型 */
    Set<String> methodTypes();

    /** 接口声明节点类型 */
    Set<String> interfaceTypes();

    /** 枚举声明节点类型 */
    default Set<String> enumTypes() { return Collections.emptySet(); }

    /** 枚举成员节点类型 */
    default Set<String> enumMemberTypes() { return Collections.emptySet(); }

    /** 字段声明节点类型 */
    default Set<String> fieldTypes() { return Collections.emptySet(); }

    /** 导入语句节点类型 */
    default Set<String> importTypes() { return Collections.emptySet(); }

    /** 包声明节点类型 */
    default Set<String> packageTypes() { return Collections.emptySet(); }

    // =========================================================================
    // Field names (tree-sitter child_by_field_name keys)
    // =========================================================================

    /** "name" 字段名 */
    default String nameField() { return "name"; }

    /** "body" 字段名 */
    default String bodyField() { return "body"; }

    /** "parameters" 字段名 */
    default String paramsField() { return "parameters"; }

    /** "type" 字段名（返回类型） */
    default String returnField() { return "type"; }

    /** "modifiers" 字段名 */
    default String modifiersField() { return "modifiers"; }

    // =========================================================================
    // Extraction hooks
    // =========================================================================

    /**
     * 提取方法/构造函数签名。
     * @param node     方法/构造函数节点
     * @param source   源代码全文
     * @return 签名字符串（如 "greet(String)"）
     */
    String getSignature(TSNode node, String source);

    /**
     * 提取可见性修饰符。
     */
    Visibility getVisibility(TSNode node, ExtractorContext ctx);

    /**
     * 是否 static。
     */
    boolean isStatic(TSNode node, ExtractorContext ctx);

    /**
     * 是否 abstract。
     */
    boolean isAbstract(TSNode node, ExtractorContext ctx);

    // =========================================================================
    // Package & Import
    // =========================================================================

    /**
     * 从 package 节点提取包名。
     */
    String extractPackage(TSNode node, String source);

    /**
     * 从 import 节点提取导入信息。
     */
    ImportInfo extractImport(TSNode node, String source);

    // =========================================================================
    // Member synthesis (e.g. Lombok)
    // =========================================================================

    /**
     * 合成为类节点生成的成员（Lombok 注解等）。
     * 默认不做任何事。
     */
    default void synthesizeMembers(TSNode classNode, ExtractorContext ctx) {}
}
