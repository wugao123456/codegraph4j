package com.codegraph.core.types;

/** 节点类型枚举 — 覆盖代码图谱中所有符号种类。
 *  <p>与 EdgeKind 保持一致的读写模式：
 *  <ul>
 *    <li>数据库存储使用小写值（通过 {@link #getValue()}），与 codegraph TypeScript 版统一</li>
 *    <li>从数据库读取时使用 {@link #fromValue(String)} 反向查找</li>
 *  </ul>
 */
public enum NodeKind {
    /** 源代码文件 */
    FILE("file"),
    /** 模块 / 包 */
    MODULE("module"),
    /** 类 */
    CLASS("class"),
    /** 结构体（C/C++/Go struct） */
    STRUCT("struct"),
    /** 接口 */
    INTERFACE("interface"),
    /** 特质（PHP/Rust/Scala trait） */
    TRAIT("trait"),
    /** 协议（Swift protocol） */
    PROTOCOL("protocol"),
    /** 函数 */
    FUNCTION("function"),
    /** 成员方法 / 构造函数 */
    METHOD("method"),
    /** 属性 */
    PROPERTY("property"),
    /** 字段 / 成员变量 */
    FIELD("field"),
    /** 局部变量 */
    VARIABLE("variable"),
    /** 常量 */
    CONSTANT("constant"),
    /** 枚举类型 */
    ENUM("enum"),
    /** 枚举成员 */
    ENUM_MEMBER("enum_member"),
    /** 类型别名（typedef / type alias） */
    TYPE_ALIAS("type_alias"),
    /** 命名空间 */
    NAMESPACE("namespace"),
    /** 方法/函数参数 */
    PARAMETER("parameter"),
    /** import / require 语句 */
    IMPORT("import"),
    /** export 语句 */
    EXPORT("export"),
    /** 框架路由（如 Spring @GetMapping） */
    ROUTE("route"),
    /** UI 组件（React/Vue 等） */
    COMPONENT("component");

    private final String value;

    NodeKind(String value) {
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
    public static NodeKind fromValue(String value) {
        for (NodeKind k : values()) {
            if (k.value.equals(value)) {
                return k;
            }
        }
        throw new IllegalArgumentException("Unknown NodeKind value: " + value);
    }
}
