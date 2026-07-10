package com.codegraph.core;

import com.codegraph.core.types.EdgeKind;
import com.codegraph.extraction.tree_sitter.ExtractorContext;

import java.util.Map;
import java.util.Objects;

/**
 * CodeGraph 中的关系边，代表两个符号节点之间的关系。
 *
 * <p><b>边类型（EdgeKind）：</b>
 * <ul>
 *   <li><b>CONTAINS</b>：包含关系（file→class, class→method, class→field）</li>
 *   <li><b>EXTENDS</b>：继承关系（子类→父类）</li>
 *   <li><b>IMPLEMENTS</b>：实现关系（类→接口）</li>
 *   <li><b>CALLS</b>：方法调用关系（调用方→被调用方）</li>
 *   <li><b>REFERENCES</b>：引用关系（方法→字段/枚举成员）</li>
 *   <li><b>IMPORTS</b>：导入关系（包→导入的类）</li>
 * </ul>
 *
 * <p><b>示例：</b>
 * <pre>
 * 对于源码：
 *   class Foo {
 *       void bar() { baz(); }
 *   }
 *
 * 生成的边：
 *   Edge{source='Foo', target='Foo.bar', kind=CONTAINS}
 *   Edge{source='Foo.bar', target='Foo.baz', kind=CALLS}
 * </pre>
 *
 * <p><b>图结构示意：</b>
 * <pre>
 *   FILE ──CONTAINS──&gt; MODULE ──CONTAINS──&gt; CLASS ──CONTAINS──&gt; METHOD
 *                                                   │           │
 *                                                   │ CONTAINS   │ CALLS
 *                                                   ▼           ▼
 *                                                FIELD     METHOD
 * </pre>
 */
public class Edge {

    /**
     * 源节点 ID（关系的发起方）。
     *
     * <p><b>示例：</b>
     * <ul>
     *   <li>CONTAINS 边：source = CLASS 节点 ID</li>
     *   <li>CALLS 边：source = 调用方 METHOD 节点 ID</li>
     *   <li>EXTENDS 边：source = 子类 CLASS 节点 ID</li>
     * </ul>
     *
     * <p><b>关联：</b>对应 {@link Node#getId()}。
     */
    private String source;

    /**
     * 目标节点 ID（关系的接收方）。
     *
     * <p><b>示例：</b>
     * <ul>
     *   <li>CONTAINS 边：target = FIELD/METHOD 节点 ID</li>
     *   <li>CALLS 边：target = 被调用方 METHOD 节点 ID</li>
     *   <li>EXTENDS 边：target = 父类 CLASS 节点 ID</li>
     * </ul>
     *
     * <p><b>关联：</b>对应 {@link Node#getId()}。
     */
    private String target;

    /**
     * 边的类型，定义两个节点之间的关系种类。
     *
     * <p><b>常用类型：</b>
     * <table>
     *   <tr><th>类型</th><th>含义</th><th>方向</th></tr>
     *   <tr><td>CONTAINS</td><td>包含</td><td>父→子</td></tr>
     *   <tr><td>EXTENDS</td><td>继承</td><td>子类→父类</td></tr>
     *   <tr><td>IMPLEMENTS</td><td>实现</td><td>类→接口</td></tr>
     *   <tr><td>CALLS</td><td>方法调用</td><td>调用方→被调用方</td></tr>
     *   <tr><td>REFERENCES</td><td>引用</td><td>使用方→被引用方</td></tr>
     *   <tr><td>IMPORTS</td><td>导入</td><td>包→导入类</td></tr>
     * </table>
     */
    private EdgeKind kind;

    /**
     * 边的元数据，存储额外的关系属性。
     *
     * <p><b>常用元数据：</b>
     * <ul>
     *   <li><b>provenance</b>：来源标记（"tree-sitter" 或 "heuristic"）</li>
     *   <li><b>valueRef</b>：是否为值引用（用于 REFERENCES 边）</li>
     * </ul>
     *
     * <p><b>示例：</b>
     * <pre>
     *   {"provenance": "heuristic", "valueRef": true}
     * </pre>
     *
     * <p><b>设计理由：</b>使用 Map 存储元数据，便于扩展，无需修改 Edge 类结构即可添加新属性。
     */
    private Map<String, Object> metadata;

    /**
     * 边在源码中的起始行号（1-based）。
     *
     * <p><b>用途：</b>定位关系的具体位置，支持跳转到源码中查看关系的定义处。
     *
     * <p><b>示例：</b>
     * <ul>
     *   <li>CALLS 边：记录方法调用所在的行号</li>
     *   <li>EXTENDS 边：记录 extends 关键字所在的行号</li>
     * </ul>
     */
    private int line;

    /**
     * 边在源码中的起始列号（1-based）。
     *
     * <p><b>用途：</b>精确定位关系的起始位置。
     */
    private int column;

    /**
     * 边的来源标记，标识关系是如何被提取的。
     *
     * <p><b>取值：</b>
     * <ul>
     *   <li><b>"tree-sitter"</b>：直接从 AST 中提取（如 CONTAINS、EXTENDS、IMPLEMENTS）</li>
     *   <li><b>"heuristic"</b>：通过启发式规则推断（如 CALLS、REFERENCES）</li>
     * </ul>
     *
     * <p><b>用途：</b>区分确定性关系和推断性关系，在后续处理中可给予不同权重。
     */
    private String provenance;

    /**
     * 默认构造函数。
     *
     * <p><b>用途：</b>反序列化（如 JSON、数据库查询）时使用。
     * 推荐使用 {@link ExtractorContext#addEdge} 创建边，
     * 该方法会自动填充位置信息和 provenance。
     */
    public Edge() {
    }

    /**
     * 获取源节点 ID。
     *
     * @return 源节点 ID
     */
    public String getSource() {
        return source;
    }

    /**
     * 设置源节点 ID。
     *
     * @param source 源节点 ID
     */
    public void setSource(String source) {
        this.source = source;
    }

    /**
     * 获取目标节点 ID。
     *
     * @return 目标节点 ID
     */
    public String getTarget() {
        return target;
    }

    /**
     * 设置目标节点 ID。
     *
     * @param target 目标节点 ID
     */
    public void setTarget(String target) {
        this.target = target;
    }

    /**
     * 获取边的类型。
     *
     * @return 边的类型
     */
    public EdgeKind getKind() {
        return kind;
    }

    /**
     * 设置边的类型。
     *
     * @param kind 边的类型
     */
    public void setKind(EdgeKind kind) {
        this.kind = kind;
    }

    /**
     * 获取边的元数据。
     *
     * @return 元数据 Map
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * 设置边的元数据。
     *
     * @param metadata 元数据 Map
     */
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    /**
     * 获取边在源码中的起始行号。
     *
     * @return 行号（1-based）
     */
    public int getLine() {
        return line;
    }

    /**
     * 设置边在源码中的起始行号。
     *
     * @param line 行号（1-based）
     */
    public void setLine(int line) {
        this.line = line;
    }

    /**
     * 获取边在源码中的起始列号。
     *
     * @return 列号（1-based）
     */
    public int getColumn() {
        return column;
    }

    /**
     * 设置边在源码中的起始列号。
     *
     * @param column 列号（1-based）
     */
    public void setColumn(int column) {
        this.column = column;
    }

    /**
     * 获取边的来源标记。
     *
     * @return 来源标记（"tree-sitter" 或 "heuristic"）
     */
    public String getProvenance() {
        return provenance;
    }

    /**
     * 设置边的来源标记。
     *
     * @param provenance 来源标记（"tree-sitter" 或 "heuristic"）
     */
    public void setProvenance(String provenance) {
        this.provenance = provenance;
    }

    /**
     * 判断两条边是否相等。
     *
     * <p><b>相等条件：</b>source、target、kind 三个字段完全相同。
     * <b>不比较的字段：</b>line、column、metadata、provenance。
     *
     * <p><b>设计理由：</b>在图去重场景中，相同的 source→target→kind 组合视为同一条边，
     * 即使它们出现在不同位置或有不同的元数据。
     *
     * @param o 待比较的对象
     * @return 如果两条边相等返回 true，否则返回 false
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Edge edge = (Edge) o;
        return Objects.equals(source, edge.source) &&
                Objects.equals(target, edge.target) &&
                Objects.equals(kind, edge.kind);
    }

    /**
     * 计算边的哈希码。
     *
     * <p><b>哈希组成：</b>基于 source、target、kind 三个字段计算。
     *
     * <p><b>与 equals 的一致性：</b>保证相同 source→target→kind 的边具有相同的哈希码，
     * 支持在 HashSet 和 HashMap 中正确去重和查找。
     *
     * @return 边的哈希码
     */
    @Override
    public int hashCode() {
        return Objects.hash(source, target, kind);
    }

    /**
     * 返回边的字符串表示。
     *
     * <p><b>格式：</b>Edge{source='xxx', target='xxx', kind=TYPE}
     *
     * <p><b>用途：</b>日志输出和调试时快速查看边的核心信息。
     *
     * @return 边的字符串表示
     */
    @Override
    public String toString() {
        return "Edge{" +
                "source='" + source + '\'' +
                ", target='" + target + '\'' +
                ", kind=" + kind +
                '}';
    }
}