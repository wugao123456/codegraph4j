package com.codegraph.parser.bridge;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.List;

/**
 * tree-sitter TSNode 结构体映射（by-value 传递）
 *
 * tree-sitter 0.26.x 定义:
 *   typedef struct {
 *     uint32_t context[4];  // 16 bytes (offset 0)
 *     const void *id;       //  8 bytes (offset 16)
 *     const TSTree *tree;   //  8 bytes (offset 24)
 *   } TSNode;               // 32 bytes total
 *
 * ARM64 注意: 32 字节结构体在 ARM64 AAPCS 中通过寄存器传递（2 个 Q 寄存器或 4 个 X 寄存器）。
 * Structure.ByValue 在此 ABI 下可能不稳定，因此使用 useDirectFieldAccess + 显式 padding 字段确保对齐。
 */
@Structure.FieldOrder({"context0", "context1", "context2", "context3", "id", "tree"})
public class TSNode extends Structure implements Structure.ByValue {

    /** Prevent JNA from auto-generating getter/setter methods — direct field access only */
    public static final boolean useDirectFieldAccess = true;

    public int context0;
    public int context1;
    public int context2;
    public int context3;
    public Pointer id;
    public Pointer tree;

    public TSNode() {
        super();
        // ARM64 上使用 GNUC 对齐（与 macOS clang 一致）
        setAlignType(ALIGN_GNUC);
    }

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("context0", "context1", "context2", "context3", "id", "tree");
    }
}
