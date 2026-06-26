package com.codegraph.parser.bridge;

import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.List;

/**
 * tree-sitter TSPoint 结构体映射。
 *
 * typedef struct {
 *   uint32_t row;
 *   uint32_t column;
 * } TSPoint;
 */
@Structure.FieldOrder({"row", "column"})
public class TSPoint extends Structure implements Structure.ByValue {

    public int row;
    public int column;

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("row", "column");
    }
}
