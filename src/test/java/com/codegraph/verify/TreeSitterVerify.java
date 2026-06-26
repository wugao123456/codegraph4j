package com.codegraph.verify;

import com.sun.jna.*;
import com.sun.jna.ptr.LongByReference;

import java.util.Arrays;
import java.util.List;

/**
 * JNA + tree-sitter C 库 可行性验证脚本（v2 - 正确的结构体映射）
 */
@SuppressWarnings("unused")
public class TreeSitterVerify {

    // =========================================================================
    // TSNode 结构体（tree-sitter 0.26.x）
    //
    //   typedef struct {
    //     uint32_t context[4];    // 4 x 4bytes = 16 bytes  (offset 0)
    //     const void *id;         //  8 bytes (pointer)     (offset 16)
    //     const TSTree *tree;     //  8 bytes (pointer)     (offset 24)
    //   } TSNode;
    //   Total: 32 bytes
    // =========================================================================
    @Structure.FieldOrder({"context0", "context1", "context2", "context3", "id", "tree"})
    public static class TSNode extends Structure implements Structure.ByValue {
        public int context0;
        public int context1;
        public int context2;
        public int context3;
        public Pointer id;
        public Pointer tree;
    }

    // TSPoint
    @Structure.FieldOrder({"row", "column"})
    public static class TSPoint extends Structure implements Structure.ByValue {
        public int row;
        public int column;
    }

    // =========================================================================
    // tree-sitter C API JNA 映射
    // =========================================================================
    public interface TreeSitterC extends Library {
        // 解析器生命周期 — 返回/接收 TSParser *
        Pointer ts_parser_new();
        void ts_parser_delete(Pointer parser);

        // set_language 返回 bool(实际是bool类型 → int)
        @SuppressWarnings("UnusedReturnValue")
        int ts_parser_set_language(Pointer parser, Pointer language);

        // 解析 — 返回 TSTree *
        Pointer ts_parser_parse_string(Pointer parser, Pointer oldTree, String source, int sourceLen);

        // TSTree 操作
        void ts_tree_delete(Pointer tree);
        TSNode ts_tree_root_node(Pointer tree);  // 返回值类型 TSNode

        // TSNode 查询（全部返回值类型或传值类型 TSNode）
        String ts_node_type(TSNode node);
        int ts_node_start_byte(TSNode node);
        int ts_node_end_byte(TSNode node);
        String ts_node_string(TSNode node);
        int ts_node_child_count(TSNode node);
        TSNode ts_node_child(TSNode node, int index);
        int ts_node_named_child_count(TSNode node);
        TSNode ts_node_named_child(TSNode node, int index);
        TSNode ts_node_child_by_field_name(TSNode node, String fieldName, int fieldNameLength);

        // 辅助
        boolean ts_node_is_null(TSNode node);
        TSPoint ts_node_start_point(TSNode node);
        TSPoint ts_node_end_point(TSNode node);
    }

    // =========================================================================
    // tree-sitter-java 语法库
    // =========================================================================
    public interface TreeSitterJava extends Library {
        Pointer tree_sitter_java();
    }

    // =========================================================================
    // AST 打印
    // =========================================================================
    static void dumpAst(TreeSitterC ts, TSNode node, String source, int indent) {
        if (ts.ts_node_is_null(node)) return;
        String type = ts.ts_node_type(node);
        if (type == null || type.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < indent; i++) sb.append("  ");
        sb.append(type);

        int namedChildCount = ts.ts_node_named_child_count(node);
        if (namedChildCount == 0) {
            int start = ts.ts_node_start_byte(node);
            int end = ts.ts_node_end_byte(node);
            if (end > start && end - start < 80) {
                String text = source.substring(start, end)
                    .replace("\n", "\\n").replace("\r", "");
                sb.append("  \"").append(text).append("\"");
            }
        }

        System.out.println(sb.toString());

        for (int i = 0; i < namedChildCount; i++) {
            TSNode child = ts.ts_node_named_child(node, i);
            dumpAst(ts, child, source, indent + 1);
        }
    }

    // =========================================================================
    // 递归统计节点类型
    // =========================================================================
    static void countNodes(TreeSitterC ts, TSNode node, List<String> wantedTypes,
                           int[] classCount, int[] methodCount, int[] fieldCount,
                           int[] interfaceCount, int[] importCount, int[] packageCount,
                           int depth) {
        if (ts.ts_node_is_null(node)) return;

        // 统计当前节点
        if (depth > 0) { // 跳过 root program 本身
            String type = ts.ts_node_type(node);
            if (type != null) {
                switch (type) {
                    case "class_declaration":     classCount[0]++;     break;
                    case "method_declaration":
                    case "constructor_declaration": methodCount[0]++;   break;
                    case "field_declaration":     fieldCount[0]++;      break;
                    case "interface_declaration": interfaceCount[0]++;  break;
                    case "import_declaration":    importCount[0]++;     break;
                    case "package_declaration":   packageCount[0]++;    break;
                }
            }
        }

        // 递归遍历子节点
        int childCount = ts.ts_node_named_child_count(node);
        for (int i = 0; i < childCount; i++) {
            TSNode child = ts.ts_node_named_child(node, i);
            countNodes(ts, child, wantedTypes, classCount, methodCount, fieldCount,
                interfaceCount, importCount, packageCount, depth + 1);
        }
    }

    // =========================================================================
    // Main
    // =========================================================================
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  JNA + tree-sitter 可行性验证 v2");
        System.out.println("========================================\n");

        // Step 1: 动态库路径
        String homebrewsLib = "/opt/homebrew/lib";
        String javaLib = homebrewsLib + "/libtree-sitter-java.dylib";
        String tsLib = homebrewsLib + "/libtree-sitter.dylib";
        if (!new java.io.File(javaLib).exists()) {
            homebrewsLib = "/usr/local/lib";
            javaLib = homebrewsLib + "/libtree-sitter-java.dylib";
            tsLib = homebrewsLib + "/libtree-sitter.dylib";
        }

        System.out.println("[1] 加载动态库...");
        TreeSitterJava tsJava;
        TreeSitterC ts;
        try {
            tsJava = Native.load(javaLib, TreeSitterJava.class);
            System.out.println("    ✅ " + javaLib);
        } catch (UnsatisfiedLinkError e) {
            System.out.println("    ❌ " + javaLib + ": " + e.getMessage());
            System.exit(1);
            return;
        }
        try {
            ts = Native.load(tsLib, TreeSitterC.class);
            System.out.println("    ✅ " + tsLib);
        } catch (UnsatisfiedLinkError e) {
            System.out.println("    ❌ " + tsLib + ": " + e.getMessage());
            System.exit(1);
            return;
        }

        // Step 2: language 指针
        System.out.println("\n[2] 获取 Java language 指针...");
        Pointer javaLang = tsJava.tree_sitter_java();
        if (javaLang == null) {
            System.out.println("    ❌ tree_sitter_java() 返回 null");
            System.exit(1);
        }
        System.out.printf("    ✅ pointer: %s\n", javaLang);

        // Step 3: Parser
        System.out.println("\n[3] 创建 Parser + 设置语言...");
        Pointer parser = ts.ts_parser_new();
        if (parser == null) {
            System.out.println("    ❌ ts_parser_new() 返回 null");
            System.exit(1);
        }
        int setResult = ts.ts_parser_set_language(parser, javaLang);
        System.out.printf("    ✅ parser=%s, set_language=%d\n", parser, setResult);

        // Step 4: 解析
        String testCode =
            "package com.example;\n" +
            "\n" +
            "import java.util.List;\n" +
            "\n" +
            "/**\n" +
            " * A simple test class.\n" +
            " */\n" +
            "public class Hello extends BaseClass implements Runnable {\n" +
            "    private String name;\n" +
            "\n" +
            "    public Hello(String name) {\n" +
            "        this.name = name;\n" +
            "    }\n" +
            "\n" +
            "    public void greet() {\n" +
            "        System.out.println(\"Hello, \" + name);\n" +
            "    }\n" +
            "}\n";

        System.out.println("\n[4] 解析测试代码...");
        Pointer tree = ts.ts_parser_parse_string(parser, null, testCode, testCode.length());
        if (tree == null) {
            System.out.println("    ❌ parse 返回 null");
            ts.ts_parser_delete(parser);
            System.exit(1);
        }
        System.out.printf("    ✅ tree=%s\n", tree);

        // Step 5: 遍历 AST
        System.out.println("\n[5] AST 遍历:");
        TSNode root = ts.ts_tree_root_node(tree);
        if (ts.ts_node_is_null(root)) {
            System.out.println("    ❌ root node is null");
            ts.ts_tree_delete(tree);
            ts.ts_parser_delete(parser);
            System.exit(1);
        }
        String rootType = ts.ts_node_type(root);
        int namedChildCount = ts.ts_node_named_child_count(root);
        System.out.printf("    root: %s, named children: %d\n\n", rootType, namedChildCount);

        dumpAst(ts, root, testCode, 0);

        // Step 6: 递归统计所有节点类型（验证深层子树遍历能力）
        System.out.println("\n[6] 递归统计关键节点...");
        int[] counts = new int[8]; // class, method, field, interface, import, package, enum, other
        int[] methodCount = {0}, fieldCount = {0};
        int[] classCount = {0}, interfaceCount = {0}, importCount = {0}, packageCount = {0};

        List<String> wantedTypes = Arrays.asList(
            "class_declaration", "method_declaration", "constructor_declaration",
            "field_declaration", "interface_declaration", "import_declaration",
            "package_declaration", "enum_declaration"
        );

        // 递归遍历
        countNodes(ts, root, wantedTypes, classCount, methodCount, fieldCount,
            interfaceCount, importCount, packageCount, 0);

        System.out.printf("    class_declaration:       %d\n", classCount[0]);
        System.out.printf("    method_declaration:      %d\n", methodCount[0]);
        System.out.printf("    field_declaration:       %d\n", fieldCount[0]);
        System.out.printf("    interface_declaration:   %d\n", interfaceCount[0]);
        System.out.printf("    import_declaration:      %d\n", importCount[0]);
        System.out.printf("    package_declaration:     %d\n", packageCount[0]);

        // Step 7: 测试 extends/implements 字段访问
        if (classCount[0] > 0) {
            System.out.println("\n[7] 测试字段访问（superclass / superinterfaces）...");
            // 找到 class_declaration 节点
            for (int i = 0; i < namedChildCount; i++) {
                TSNode child = ts.ts_node_named_child(root, i);
                if ("class_declaration".equals(ts.ts_node_type(child))) {
                    TSNode superclass = ts.ts_node_child_by_field_name(child, "superclass", 10);
                    TSNode superifaces = ts.ts_node_child_by_field_name(child, "superinterfaces", 15);
                    if (!ts.ts_node_is_null(superclass)) {
                        System.out.printf("    ✅ superclass:  byte %d-%d = \"%s\"\n",
                            ts.ts_node_start_byte(superclass),
                            ts.ts_node_end_byte(superclass),
                            testCode.substring(ts.ts_node_start_byte(superclass), ts.ts_node_end_byte(superclass)));
                    }
                    if (!ts.ts_node_is_null(superifaces)) {
                        System.out.printf("    ✅ superinterfaces: byte %d-%d = \"%s\"\n",
                            ts.ts_node_start_byte(superifaces),
                            ts.ts_node_end_byte(superifaces),
                            testCode.substring(ts.ts_node_start_byte(superifaces), ts.ts_node_end_byte(superifaces)));
                    }
                    break;
                }
            }
        }

        // Step 8: 清理
        ts.ts_tree_delete(tree);
        ts.ts_parser_delete(parser);

        // 汇总
        System.out.println("\n========================================");
        System.out.println("  验证结果");
        System.out.println("========================================");
        System.out.printf("  package:    %d\n", packageCount[0]);
        System.out.printf("  import:     %d\n", importCount[0]);
        System.out.printf("  class:      %d\n", classCount[0]);
        System.out.printf("  interface:  %d\n", interfaceCount[0]);
        System.out.printf("  method:     %d\n", methodCount[0]);
        System.out.printf("  field:      %d\n", fieldCount[0]);
        System.out.println("----------------------------------------");
        boolean pass = classCount[0] == 1 && methodCount[0] == 2 && fieldCount[0] == 1
            && packageCount[0] == 1 && importCount[0] == 1;
        System.out.printf("  %s\n",
            pass ? "🎉 全部通过！JNA + tree-sitter 链路可行！" : "❌ 验证失败，请检查输出");
        System.out.println("========================================");
        System.exit(pass ? 0 : 1);
    }
}
