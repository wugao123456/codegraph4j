package com.codegraph.extraction.tree_sitter;

import com.codegraph.extraction.bridge.TSNode;
import com.codegraph.extraction.bridge.TreeSitterNative;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Tree-sitter 提取工具函数。
 */
public final class TreeSitterHelpers {

    private TreeSitterHelpers() {}

    /**
     * 需要穿透的包装节点类型。
     * 这些节点不产生实际的符号子节点，只是包装层。
     */
    public static final Set<String> DOCSTRING_WRAPPER_TYPES = Collections.unmodifiableSet(
        new HashSet<>(Collections.singletonList("block_comment"))
    );

    // =========================================================================
    // Node ID
    // =========================================================================

    /**
     * 生成节点 ID（SHA-256 hash, 前 32 字符 hex）。
     * 格式: kind:sha256(filePath:kind:name:line).substring(0,32)
     */
    public static String generateNodeId(String filePath, String kind, String name, int line) {
        String input = filePath + ":" + kind + ":" + name + ":" + line;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {  // 16 bytes = 32 hex chars
                sb.append(String.format("%02x", hash[i]));
            }
            return kind + ":" + sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available, this should never happen
            return kind + ":" + input.hashCode();
        }
    }

    // =========================================================================
    // Node text
    // =========================================================================

    /**
     * 获取节点在源文本中对应的字符串。
     * 注意：tree-sitter 返回的是 UTF-8 字节偏移量，需要转换为字符偏移量。
     */
    public static String getNodeText(TSNode node, String source, TreeSitterNative ts) {
        if (ts.ts_node_is_null(node)) return "";
        int byteStart = ts.ts_node_start_byte(node);
        int byteEnd = ts.ts_node_end_byte(node);
        if (byteEnd <= byteStart) return "";

        // 将 UTF-8 字节偏移量转换为字符偏移量
        int charStart = byteOffsetToCharOffset(source, byteStart);
        int charEnd = byteOffsetToCharOffset(source, byteEnd);

        if (charEnd <= charStart || charStart >= source.length()) return "";
        return source.substring(charStart, Math.min(charEnd, source.length()));
    }

    /**
     * 将 UTF-8 字节偏移量转换为字符串字符偏移量。
     */
    private static int byteOffsetToCharOffset(String source, int byteOffset) {
        int byteCount = 0;
        int charCount = 0;
        for (int i = 0; i < source.length(); i++) {
            if (byteCount >= byteOffset) {
                return charCount;
            }
            // 计算当前字符的 UTF-8 字节长度
            int codepoint = source.codePointAt(i);
            byteCount += charCount(codepoint);
            charCount++;
        }
        return charCount;
    }

    /**
     * 计算码点对应的 UTF-8 字节数。
     */
    private static int charCount(int codepoint) {
        if (codepoint <= 0x7F) {
            return 1; // ASCII
        } else if (codepoint <= 0x7FF) {
            return 2; // 2字节字符
        } else if (codepoint <= 0xFFFF) {
            return 3; // 3字节字符（大多数中日韩文字）
        } else {
            return 4; // 4字节字符（emoji等）
        }
    }

    // =========================================================================
    // Field access
    // =========================================================================

    /**
     * 按字段名获取子节点。
     */
    public static TSNode getChildByField(TSNode node, String fieldName, TreeSitterNative ts) {
        return ts.ts_node_child_by_field_name(node, fieldName, fieldName.length());
    }

    // =========================================================================
    // Named child search
    // =========================================================================

    /**
     * 按节点类型名查找命名子节点（不是 field name）。
     * tree-sitter-java 的 modifiers 就是 named child，不是 field。
     */
    public static TSNode findNamedChildByType(TSNode node, String childType, TreeSitterNative ts) {
        int childCount = ts.ts_node_named_child_count(node);
        for (int i = 0; i < childCount; i++) {
            TSNode child = ts.ts_node_named_child(node, i);
            if (childType.equals(ts.ts_node_type(child))) {
                return child;
            }
        }
        TSNode nullNode = new TSNode();
        nullNode.id = null;
        nullNode.tree = null;
        return nullNode;
    }

    // =========================================================================
    // Docstring
    // =========================================================================

    /**
     * 获取节点的前置文档注释。
     * 从节点的前一个兄弟节点收集注释。
     */
    public static String getPrecedingDocstring(TSNode node, String source, TreeSitterNative ts) {
        if (ts.ts_node_is_null(node)) return "";

        TSNode prev = ts.ts_node_prev_named_sibling(node);
        if (ts.ts_node_is_null(prev)) {
            // 也可能是父节点内的前导注释
            prev = ts.ts_node_prev_sibling(node);
        }

        // 收集连续的前导注释
        StringBuilder sb = new StringBuilder();
        while (!ts.ts_node_is_null(prev)) {
            String type = ts.ts_node_type(prev);
            if (type == null) break;

            if ("block_comment".equals(type) || "line_comment".equals(type)) {
                String text = getNodeText(prev, source, ts);
                if (sb.length() > 0) sb.insert(0, "\n");
                sb.insert(0, cleanCommentMarkers(text, type));
            } else {
                break; // 非注释节点，停止
            }

            prev = ts.ts_node_prev_sibling(prev);
        }

        return sb.toString().trim();
    }

    /**
     * 清理注释标记，提取纯文本内容。
     */
    public static String cleanCommentMarkers(String comment, String nodeType) {
        if (comment == null || comment.isEmpty()) return "";

        if ("block_comment".equals(nodeType)) {
            // /** ... */ 或 /* ... */
            if (comment.startsWith("/**") || comment.startsWith("/*")) {
                comment = comment.substring(comment.startsWith("/**") ? 3 : 2);
            }
            if (comment.endsWith("*/")) {
                comment = comment.substring(0, comment.length() - 2);
            }
            // 去掉每行开头的 *
            StringBuilder sb = new StringBuilder();
            for (String line : comment.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("*")) {
                    trimmed = trimmed.substring(1).trim();
                }
                if (sb.length() > 0) sb.append("\n");
                sb.append(trimmed);
            }
            return sb.toString().trim();
        }

        if ("line_comment".equals(nodeType)) {
            // // ...
            if (comment.startsWith("//")) {
                return comment.substring(2).trim();
            }
        }

        return comment.trim();
    }
}
