package com.codegraph.core;

import com.codegraph.core.types.Language;
import com.codegraph.core.types.NodeKind;
import com.codegraph.core.types.Visibility;

import java.util.List;
import java.util.Objects;

/**
 * CodeGraph 中的符号节点，代表源码中的一个编程元素（类、方法、字段等）。
 *
 * <p><b>节点类型（NodeKind）：</b>
 * <ul>
 *   <li><b>FILE</b>：文件节点，作为整个文件的根节点</li>
 *   <li><b>MODULE</b>：模块/包节点（如 Java 的 package）</li>
 *   <li><b>CLASS</b>：类节点</li>
 *   <li><b>INTERFACE</b>：接口节点</li>
 *   <li><b>ENUM</b>：枚举节点</li>
 *   <li><b>METHOD</b>：方法节点</li>
 *   <li><b>FIELD</b>：字段节点</li>
 *   <li><b>ENUM_MEMBER</b>：枚举常量节点</li>
 *   <li><b>IMPORT</b>：导入声明节点</li>
 * </ul>
 *
 * <p><b>示例：</b>
 * <pre>
 * 对于源码：
 *   package com.example;
 *   public class Foo {
 *       private String name;
 *       public void bar(String arg) { }
 *   }
 *
 * 生成的节点：
 *   Node{id='...', kind=MODULE, name='com.example', qualifiedName='com.example'}
 *   Node{id='...', kind=CLASS, name='Foo', qualifiedName='com.example.Foo'}
 *   Node{id='...', kind=FIELD, name='name', qualifiedName='com.example.Foo.name', visibility=PRIVATE}
 *   Node{id='...', kind=METHOD, name='bar', qualifiedName='com.example.Foo.bar', signature='bar(String arg)', returnType='void'}
 * </pre>
 */
public class Node {

    /**
     * 节点唯一标识符。
     *
     * <p><b>ID 生成规则：</b>由文件路径、节点类型、名称和起始位置哈希组成，
     * 确保同一项目内唯一。格式示例：
     * <pre>
     *   /path/to/File.java::CLASS::Foo::123456789
     * </pre>
     *
     * <p><b>用途：</b>用于边（Edge）的 source/target 引用，以及数据库存储和查询。
     */
    private String id;

    /**
     * 节点类型，区分类、方法、字段等不同符号类型。
     *
     * <p><b>常用类型：</b>CLASS, INTERFACE, ENUM, METHOD, FIELD, MODULE, IMPORT
     */
    private NodeKind kind;

    /**
     * 节点名称（简单名称）。
     *
     * <p><b>示例：</b>
     * <ul>
     *   <li>类："Foo"</li>
     *   <li>方法："bar"</li>
     *   <li>字段："name"</li>
     *   <li>包："com.example"</li>
     * </ul>
     */
    private String name;

    /**
     * 节点的全限定名（Qualified Name）。
     *
     * <p><b>示例：</b>
     * <ul>
     *   <li>类："com.example.Foo"</li>
     *   <li>方法："com.example.Foo.bar"</li>
     *   <li>字段："com.example.Foo.name"</li>
     * </ul>
     *
     * <p><b>用途：</b>用于跨文件引用解析（如方法调用、字段引用）。
     */
    private String qualifiedName;

    /**
     * 节点所在的文件路径。
     *
     * <p><b>格式：</b>绝对路径或项目相对路径。
     * <p><b>用途：</b>关联节点与源码文件，支持跳转到源码位置。
     */
    private String filePath;

    /**
     * 节点所属的编程语言。
     *
     * <p><b>支持的语言：</b>JAVA（当前仅支持 Java）。
     */
    private Language language;

    /**
     * 节点在源码中的起始行号（1-based）。
     *
     * <p><b>用途：</b>定位节点在源码中的位置，支持代码导航。
     */
    private int startLine;

    /**
     * 节点在源码中的结束行号（1-based）。
     *
     * <p><b>用途：</b>确定节点的代码范围，用于代码高亮和摘要显示。
     */
    private int endLine;

    /**
     * 节点在源码中的起始列号（1-based）。
     *
     * <p><b>用途：</b>精确定位节点起始位置。
     */
    private int startColumn;

    /**
     * 节点在源码中的结束列号（1-based）。
     *
     * <p><b>用途：</b>精确定位节点结束位置。
     */
    private int endColumn;

    /**
     * 节点的文档注释（Docstring/Javadoc）。
     *
     * <p><b>示例：</b>
     * <pre>
     *   "/**
     *    * This is a class.
     *    * @param name the name
     *    *\/"
     * </pre>
     *
     * <p><b>用途：</b>用于代码搜索和文档生成。
     */
    private String docstring;

    /**
     * 方法的签名（包含参数列表）。
     *
     * <p><b>示例：</b>
     * <ul>
     *   <li>"bar(String arg)"</li>
     *   <li>"getUserById(int id)"</li>
     *   <li>"&lt;init&gt;(String name, int age)"</li>（构造器）
     * </ul>
     *
     * <p><b>用途：</b>区分同名但参数不同的方法（方法重载）。
     */
    private String signature;

    /**
     * 节点的可见性修饰符。
     *
     * <p><b>可见性等级：</b>
     * <ul>
     *   <li>PUBLIC - 公共可见</li>
     *   <li>PRIVATE - 私有</li>
     *   <li>PROTECTED - 受保护</li>
     *   <li>INTERNAL - 包级别（Java 默认可见性）</li>
     * </ul>
     */
    private Visibility visibility;

    /**
     * 是否为导出节点（可被其他模块访问）。
     *
     * <p><b>判断规则：</b>
     * <ul>
     *   <li>Java：public/protected 成员为 exported</li>
     *   <li>其他语言：根据语言特性判断</li>
     * </ul>
     *
     * <p><b>用途：</b>在引用解析时优先匹配导出的方法。
     */
    private boolean isExported;

    /**
     * 是否为异步方法（如 Java 的 CompletableFuture，或其他语言的 async/await）。
     *
     * <p><b>当前状态：</b>Java 解析器暂未设置此属性，预留字段。
     */
    private boolean isAsync;

    /**
     * 是否为静态成员。
     *
     * <p><b>适用节点类型：</b>METHOD, FIELD, CLASS（内部类）。
     * <p><b>示例：</b>
     * <pre>
     *   public static void main(String[] args) { ... }
     *   private static int counter;
     * </pre>
     */
    private boolean isStatic;

    /**
     * 是否为抽象成员。
     *
     * <p><b>适用节点类型：</b>CLASS, INTERFACE, METHOD。
     * <p><b>示例：</b>
     * <pre>
     *   public abstract class Base { ... }
     *   public abstract void doSomething();
     * </pre>
     */
    private boolean isAbstract;

    /**
     * 装饰器/注解列表。
     *
     * <p><b>示例：</b>
     * <pre>
     *   ["@Override", "@Deprecated", "@lombok.Getter"]
     * </pre>
     *
     * <p><b>用途：</b>Lombok 注解处理、框架注解识别（如 Spring 的 @Component）。
     */
    private List<String> decorators;

    /**
     * 泛型类型参数列表。
     *
     * <p><b>示例：</b>
     * <pre>
     *   对于 class Foo&lt;T extends Serializable&gt;：
     *   typeParameters = ["T"]
     * </pre>
     *
     * <p><b>当前状态：</b>预留字段，暂未填充。
     */
    private List<String> typeParameters;

    /**
     * 方法的返回类型。
     *
     * <p><b>示例：</b>
     * <ul>
     *   <li>"void"</li>
     *   <li>"String"</li>
     *   <li>"List&lt;User&gt;"</li>
     * </ul>
     *
     * <p><b>特殊情况：</b>构造器的返回类型为 null。
     */
    private String returnType;

    /**
     * 节点最后更新时间戳（毫秒级）。
     *
     * <p><b>用途：</b>增量更新时判断节点是否需要重新解析。
     */
    private long updatedAt;

    /**
     * 默认构造函数。
     *
     * <p><b>用途：</b>反序列化（如 JSON、数据库查询）时使用。
     * 推荐使用 {@link ExtractorContext#createNode} 创建节点，
     * 该方法会自动填充 ID、qualifiedName 等字段。
     */
    public Node() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public NodeKind getKind() {
        return kind;
    }

    public void setKind(NodeKind kind) {
        this.kind = kind;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getQualifiedName() {
        return qualifiedName;
    }

    public void setQualifiedName(String qualifiedName) {
        this.qualifiedName = qualifiedName;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Language getLanguage() {
        return language;
    }

    public void setLanguage(Language language) {
        this.language = language;
    }

    public int getStartLine() {
        return startLine;
    }

    public void setStartLine(int startLine) {
        this.startLine = startLine;
    }

    public int getEndLine() {
        return endLine;
    }

    public void setEndLine(int endLine) {
        this.endLine = endLine;
    }

    public int getStartColumn() {
        return startColumn;
    }

    public void setStartColumn(int startColumn) {
        this.startColumn = startColumn;
    }

    public int getEndColumn() {
        return endColumn;
    }

    public void setEndColumn(int endColumn) {
        this.endColumn = endColumn;
    }

    public String getDocstring() {
        return docstring;
    }

    public void setDocstring(String docstring) {
        this.docstring = docstring;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public Visibility getVisibility() {
        return visibility;
    }

    public void setVisibility(Visibility visibility) {
        this.visibility = visibility;
    }

    public boolean isExported() {
        return isExported;
    }

    public void setExported(boolean exported) {
        isExported = exported;
    }

    public boolean isAsync() {
        return isAsync;
    }

    public void setAsync(boolean async) {
        isAsync = async;
    }

    public boolean isStatic() {
        return isStatic;
    }

    public void setStatic(boolean staticFlag) {
        isStatic = staticFlag;
    }

    public boolean isAbstract() {
        return isAbstract;
    }

    public void setAbstract(boolean abstractFlag) {
        isAbstract = abstractFlag;
    }

    public List<String> getDecorators() {
        return decorators;
    }

    public void setDecorators(List<String> decorators) {
        this.decorators = decorators;
    }

    public List<String> getTypeParameters() {
        return typeParameters;
    }

    public void setTypeParameters(List<String> typeParameters) {
        this.typeParameters = typeParameters;
    }

    public String getReturnType() {
        return returnType;
    }

    public void setReturnType(String returnType) {
        this.returnType = returnType;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * 判断两个节点是否相等。
     *
     * <p><b>相等规则：</b>仅比较节点 ID，因为 ID 是节点的唯一标识符。
     * 两个节点只要 ID 相同，即使其他属性不同，也被认为是同一个节点。
     *
     * <p><b>设计理由：</b>在 CodeGraph 中，节点 ID 由文件路径、类型、名称和位置共同决定，
     * 确保同一符号在同一位置只有一个唯一 ID。因此比较 ID 即可判断节点是否相同。
     *
     * @param o 待比较的对象
     * @return true 如果两个节点的 ID 相同
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Node node = (Node) o;
        return Objects.equals(id, node.id);
    }

    /**
     * 计算节点的哈希码。
     *
     * <p><b>哈希规则：</b>仅基于节点 ID 计算哈希码，与 {@link #equals(Object)} 保持一致。
     *
     * <p><b>用途：</b>用于将节点放入 HashSet 或 HashMap 时的高效查找。
     *
     * @return 基于 ID 的哈希码
     */
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    /**
     * 返回节点的字符串表示。
     *
     * <p><b>输出格式：</b>
     * <pre>
     *   Node{id='xxx', kind=CLASS, name='Foo', qualifiedName='com.example.Foo', filePath='...', language=JAVA}
     * </pre>
     *
     * <p><b>包含字段：</b>id, kind, name, qualifiedName, filePath, language
     * <p><b>不包含字段：</b>位置信息、修饰符、签名等（避免输出过长）。
     *
     * <p><b>用途：</b>日志输出、调试和测试断言。
     *
     * @return 节点的字符串表示
     */
    @Override
    public String toString() {
        return "Node{" +
                "id='" + id + '\'' +
                ", kind=" + kind +
                ", name='" + name + '\'' +
                ", qualifiedName='" + qualifiedName + '\'' +
                ", filePath='" + filePath + '\'' +
                ", language=" + language +
                '}';
    }
}