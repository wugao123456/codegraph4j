package com.codegraph.core.types;

public enum EdgeKind {
    CONTAINS("contains"),
    CALLS("calls"),
    IMPORTS("imports"),
    EXPORTS("exports"),
    EXTENDS("extends"),
    IMPLEMENTS("implements"),
    REFERENCES("references"),
    TYPE_OF("type_of"),
    RETURNS("returns"),
    INSTANTIATES("instantiates"),
    OVERRIDES("overrides"),
    DECORATES("decorates");

    private final String value;

    EdgeKind(String value) {
        this.value = value;
    }

    /**
     * 返回小写字符串值，用于数据库存储和序列化。
     * 与 codegraph TypeScript 版保持一致。
     */
    public String getValue() {
        return value;
    }

    /**
     * 从小写字符串值反向查找枚举。
     */
    public static EdgeKind fromValue(String value) {
        for (EdgeKind k : values()) {
            if (k.value.equals(value)) {
                return k;
            }
        }
        throw new IllegalArgumentException("Unknown EdgeKind value: " + value);
    }
}