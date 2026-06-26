package com.codegraph.core.types;

/** 节点类型枚举 — 覆盖代码图谱中所有符号种类 */
public enum NodeKind {
    /** 源代码文件 */
    FILE,
    /** 模块 / 包 */
    MODULE,
    /** 类 */
    CLASS,
    /** 结构体（C/C++/Go struct） */
    STRUCT,
    /** 接口 */
    INTERFACE,
    /** 特质（PHP/Rust/Scala trait） */
    TRAIT,
    /** 协议（Swift protocol） */
    PROTOCOL,
    /** 函数 */
    FUNCTION,
    /** 成员方法 / 构造函数 */
    METHOD,
    /** 属性 */
    PROPERTY,
    /** 字段 / 成员变量 */
    FIELD,
    /** 局部变量 */
    VARIABLE,
    /** 常量 */
    CONSTANT,
    /** 枚举类型 */
    ENUM,
    /** 枚举成员 */
    ENUM_MEMBER,
    /** 类型别名（typedef / type alias） */
    TYPE_ALIAS,
    /** 命名空间 */
    NAMESPACE,
    /** 方法/函数参数 */
    PARAMETER,
    /** import / require 语句 */
    IMPORT,
    /** export 语句 */
    EXPORT,
    /** 框架路由（如 Spring @GetMapping） */
    ROUTE,
    /** UI 组件（React/Vue 等） */
    COMPONENT
}
