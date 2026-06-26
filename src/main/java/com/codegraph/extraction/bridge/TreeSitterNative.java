package com.codegraph.extraction.bridge;

import com.sun.jna.Library;
import com.sun.jna.Pointer;

/**
 * tree-sitter C API 的 JNA 映射接口。
 *
 * 映射关系:
 *   TSParser *  → Pointer
 *   TSTree *    → Pointer
 *   TSLanguage * → Pointer
 *   TSNode      → TSNode (Structure.ByValue)
 *   bool        → boolean (JNA 自动转换 C int→boolean)
 *   uint32_t    → int (Java int = 32-bit signed, 与 uint32_t 位宽相同)
 */
public interface TreeSitterNative extends Library {

    // ---- Parser lifecycle ----
    Pointer ts_parser_new();
    void ts_parser_delete(Pointer parser);
    boolean ts_parser_set_language(Pointer parser, Pointer language);

    // ---- Parsing ----
    Pointer ts_parser_parse_string(Pointer parser, Pointer oldTree, String source, int sourceLength);

    // ---- Tree lifecycle ----
    void ts_tree_delete(Pointer tree);
    TSNode ts_tree_root_node(Pointer tree);

    // ---- Node queries ----
    String ts_node_type(TSNode node);
    int ts_node_start_byte(TSNode node);
    int ts_node_end_byte(TSNode node);
    TSPoint ts_node_start_point(TSNode node);
    TSPoint ts_node_end_point(TSNode node);

    // ---- Node traversal ----
    int ts_node_child_count(TSNode node);
    TSNode ts_node_child(TSNode node, int index);
    int ts_node_named_child_count(TSNode node);
    TSNode ts_node_named_child(TSNode node, int index);
    TSNode ts_node_parent(TSNode node);
    TSNode ts_node_child_by_field_name(TSNode node, String fieldName, int fieldNameLength);

    // ---- Node text ----
    String ts_node_string(TSNode node);

    // ---- Node predicates ----
    boolean ts_node_is_null(TSNode node);
    boolean ts_node_is_named(TSNode node);
    boolean ts_node_is_missing(TSNode node);
    boolean ts_node_is_error(TSNode node);
    boolean ts_node_has_error(TSNode node);

    // ---- Sibling traversal ----
    TSNode ts_node_next_sibling(TSNode node);
    TSNode ts_node_prev_sibling(TSNode node);
    TSNode ts_node_next_named_sibling(TSNode node);
    TSNode ts_node_prev_named_sibling(TSNode node);
}
