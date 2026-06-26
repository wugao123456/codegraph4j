package com.codegraph.extraction.bridge;

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
 */
@Structure.FieldOrder({"context0", "context1", "context2", "context3", "id", "tree"})
public class TSNode extends Structure implements Structure.ByValue {

    public int context0;
    public int context1;
    public int context2;
    public int context3;
    public Pointer id;
    public Pointer tree;

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("context0", "context1", "context2", "context3", "id", "tree");
    }
}
