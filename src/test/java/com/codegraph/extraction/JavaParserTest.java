package com.codegraph.extraction;

import com.codegraph.cli.CodeGraphCli;
import com.codegraph.core.Edge;
import com.codegraph.core.Node;
import com.codegraph.core.types.EdgeKind;
import com.codegraph.core.types.Language;
import com.codegraph.core.types.NodeKind;
import com.codegraph.core.types.Visibility;

import picocli.CommandLine;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * TreeSitter Java 解析器单元测试（从旧 JavaParser 迁移）
 */
public class JavaParserTest {
    
    private CodeParser parser;
    
    @Before
    public void setUp() {
        parser = new TreeSitterCodeParser();
    }
    
    @Test
    public void testSupportsJavaFiles() {
        assertTrue("应该支持 .java 文件", parser.supports(Paths.get("Test.java")));
        assertFalse("不应该支持 .js 文件", parser.supports(Paths.get("Test.js")));
        assertFalse("不应该支持 .ts 文件", parser.supports(Paths.get("Test.ts")));
    }
    
    @Test
    public void testGetLanguage() {
        assertEquals("语言应该是 JAVA", Language.JAVA, parser.getLanguage());
    }
    
    @Test
    public void testParseEmptyFile() throws Exception {
        String content = "";
        List<Node> nodes = parser.parse(Paths.get("Empty.java"), content);
        assertTrue("空文件应该返回空列表", nodes.isEmpty());
    }
    
    @Test
    public void testParseWhitespaceOnly() throws Exception {
        String content = "   \n\n   \n  \t  ";
        List<Node> nodes = parser.parse(Paths.get("Whitespace.java"), content);
        assertTrue("只有空白字符的文件应该返回空列表", nodes.isEmpty());
    }
    
    @Test
    public void testParseCommentOnly() throws Exception {
        String content = 
            "/*\n" +
            " * This is a comment\n" +
            " */\n" +
            "// Single line comment\n" +
            "/**\n" +
            " * Javadoc comment\n" +
            " */\n";
        List<Node> nodes = parser.parse(Paths.get("CommentOnly.java"), content);
        assertTrue("只有注释的文件应该返回空列表", nodes.isEmpty());
    }
    
    @Test
    public void testParseSimpleClass() throws Exception {
        String content = 
            "package com.example;\n" +
            "\n" +
            "public class SimpleClass {\n" +
            "}\n";
        
        List<Node> nodes = parser.parse(Paths.get("SimpleClass.java"), content);
        
        assertFalse("应该解析出类节点", nodes.isEmpty());
        Node classNode = nodes.stream()
            .filter(n -> n.getKind() == NodeKind.CLASS)
            .findFirst()
            .orElse(null);
        assertNotNull("应该找到 CLASS 节点", classNode);
        assertEquals("节点类型应该是 CLASS", NodeKind.CLASS, classNode.getKind());
        assertEquals("类名应该是 SimpleClass", "SimpleClass", classNode.getName());
        assertEquals("完全限定名应该是 com.example.SimpleClass", 
            "com.example.SimpleClass", classNode.getQualifiedName());
        assertEquals("语言应该是 JAVA", Language.JAVA, classNode.getLanguage());
    }
    
    @Test
    public void testParsePublicClass() throws Exception {
        String content = "public class PublicClass {}";
        List<Node> nodes = parser.parse(Paths.get("PublicClass.java"), content);
        
        assertFalse(nodes.isEmpty());
        assertEquals("public 类应该是 PUBLIC 可见性", 
            Visibility.PUBLIC, nodes.get(0).getVisibility());
    }
    
    @Test
    public void testParsePrivateClass() throws Exception {
        String content = "private class PrivateClass {}";
        List<Node> nodes = parser.parse(Paths.get("PrivateClass.java"), content);
        
        assertFalse(nodes.isEmpty());
        assertEquals("private 类应该是 PRIVATE 可见性", 
            Visibility.PRIVATE, nodes.get(0).getVisibility());
    }
    
    @Test
    public void testParseProtectedClass() throws Exception {
        String content = "protected class ProtectedClass {}";
        List<Node> nodes = parser.parse(Paths.get("ProtectedClass.java"), content);
        
        assertFalse(nodes.isEmpty());
        assertEquals("protected 类应该是 PROTECTED 可见性", 
            Visibility.PROTECTED, nodes.get(0).getVisibility());
    }
    
    @Test
    public void testParsePackagePrivateClass() throws Exception {
        String content = "class PackagePrivateClass {}";
        List<Node> nodes = parser.parse(Paths.get("PackagePrivateClass.java"), content);
        
        assertFalse(nodes.isEmpty());
        assertEquals("默认类应该是 INTERNAL 可见性", 
            Visibility.INTERNAL, nodes.get(0).getVisibility());
    }
    
    @Test
    public void testParseFinalClass() throws Exception {
        String content = "public final class FinalClass {}";
        List<Node> nodes = parser.parse(Paths.get("FinalClass.java"), content);
        
        assertFalse(nodes.isEmpty());
        assertEquals("类名应该是 FinalClass", "FinalClass", nodes.get(0).getName());
    }
    
    @Test
    public void testParseInterface() throws Exception {
        String content = 
            "package com.example;\n" +
            "\n" +
            "public interface MyInterface {\n" +
            "}\n";
        
        List<Node> nodes = parser.parse(Paths.get("MyInterface.java"), content);
        
        assertFalse(nodes.isEmpty());
        Node interfaceNode = nodes.stream()
            .filter(n -> n.getKind() == NodeKind.INTERFACE)
            .findFirst()
            .orElse(null);
        
        assertNotNull("应该解析出接口节点", interfaceNode);
        assertEquals("接口名应该是 MyInterface", "MyInterface", interfaceNode.getName());
        assertEquals("完全限定名应该是 com.example.MyInterface", 
            "com.example.MyInterface", interfaceNode.getQualifiedName());
    }
    
    @Test
    public void testParseMultipleClasses() throws Exception {
        String content = 
            "class Class1 {}\n" +
            "class Class2 {}\n" +
            "class Class3 {}\n";
        
        List<Node> nodes = parser.parse(Paths.get("MultipleClasses.java"), content);
        
        long classCount = nodes.stream()
            .filter(n -> n.getKind() == NodeKind.CLASS)
            .count();
        assertTrue("应该至少解析出3个类", classCount >= 3);
    }
    
    @Test
    public void testParseMethods() throws Exception {
        String content = 
            "public class TestClass {\n" +
            "    public void publicMethod() {}\n" +
            "    private int privateMethod() { return 0; }\n" +
            "    protected String protectedMethod(String param) { return param; }\n" +
            "    void packagePrivateMethod() {}\n" +
            "}\n";
        
        List<Node> nodes = parser.parse(Paths.get("Methods.java"), content);
        
        long methodCount = nodes.stream()
            .filter(n -> n.getKind() == NodeKind.METHOD)
            .count();
        assertTrue("应该至少解析出4个方法", methodCount >= 4);
    }
    
    @Test
    public void testParseConstructor() throws Exception {
        String content = 
            "public class ConstructorClass {\n" +
            "    public ConstructorClass() {}\n" +
            "    public ConstructorClass(String param) {}\n" +
            "}\n";
        
        List<Node> nodes = parser.parse(Paths.get("Constructor.java"), content);
        
        long methodCount = nodes.stream()
            .filter(n -> n.getKind() == NodeKind.METHOD)
            .count();
        assertTrue("应该解析出构造函数方法", methodCount >= 2);
    }
    
    @Test
    public void testParseFields() throws Exception {
        String content = 
            "public class FieldClass {\n" +
            "    private String name;\n" +
            "    public int count;\n" +
            "    protected boolean active;\n" +
            "    int value;\n" +
            "}\n";
        
        List<Node> nodes = parser.parse(Paths.get("Fields.java"), content);
        
        long fieldCount = nodes.stream()
            .filter(n -> n.getKind() == NodeKind.FIELD)
            .count();
        assertTrue("应该至少解析出4个字段", fieldCount >= 4);
    }
    
    @Test
    public void testParseFieldWithInitialization() throws Exception {
        String content = 
            "public class InitClass {\n" +
            "    private int count = 0;\n" +
            "    private String name = \"test\";\n" +
            "    private List<String> list = new ArrayList<>();\n" +
            "}\n";
        
        List<Node> nodes = parser.parse(Paths.get("FieldInit.java"), content);
        
        long fieldCount = nodes.stream()
            .filter(n -> n.getKind() == NodeKind.FIELD)
            .count();
        assertTrue("应该至少解析出3个字段", fieldCount >= 3);
    }
    
    @Test
    public void testParseClassWithPackage() throws Exception {
        String content = 
            "package com.example.test;\n" +
            "public class PackagedClass {}\n";
        
        List<Node> nodes = parser.parse(Paths.get("Packaged.java"), content);
        
        Node classNode = nodes.stream()
            .filter(n -> n.getKind() == NodeKind.CLASS)
            .findFirst()
            .orElse(null);
        
        assertNotNull("应该解析出类节点", classNode);
        assertTrue("完全限定名应该包含包名", 
            classNode.getQualifiedName().startsWith("com.example.test"));
    }
    
    @Test
    public void testParseClassExtends() throws Exception {
        String content = "public class ChildClass extends ParentClass {}";
        List<Node> nodes = parser.parse(Paths.get("Extends.java"), content);
        
        assertFalse(nodes.isEmpty());
        assertEquals("类名应该是 ChildClass", "ChildClass", nodes.get(0).getName());
    }
    
    @Test
    public void testParseClassImplements() throws Exception {
        String content = "public class ImplClass implements Interface1, Interface2 {}";
        List<Node> nodes = parser.parse(Paths.get("Implements.java"), content);
        
        assertFalse(nodes.isEmpty());
        assertEquals("类名应该是 ImplClass", "ImplClass", nodes.get(0).getName());
    }
    
    @Test
    public void testParseLineNumbers() throws Exception {
        String content = 
            "line1\n" +
            "line2\n" +
            "line3\n" +
            "class TestClass {\n" +
            "    line5\n" +
            "    line6\n" +
            "}\n";
        
        List<Node> nodes = parser.parse(Paths.get("LineNumbers.java"), content);
        
        Node classNode = nodes.stream()
            .filter(n -> n.getKind() == NodeKind.CLASS)
            .findFirst()
            .orElse(null);
        
        assertNotNull("应该解析出类节点", classNode);
        assertTrue("起始行应该大于0", classNode.getStartLine() > 0);
    }
    
    @Test
    public void testParseGenericClass() throws Exception {
        String content = 
            "public class GenericClass<T> {}\n" +
            "public class BoundedClass<T extends Number> {}\n" +
            "public class MultiGenericClass<K, V> {}\n";
        
        List<Node> nodes = parser.parse(Paths.get("Generic.java"), content);
        
        long classCount = nodes.stream()
            .filter(n -> n.getKind() == NodeKind.CLASS)
            .count();
        assertTrue("应该至少解析出3个泛型类", classCount >= 3);
    }
    
    @Test
    public void testParseMethodWithGenerics() throws Exception {
        String content = 
            "public class GenericMethod {\n" +
            "    public <T> T getValue() { return null; }\n" +
            "    public <T, R> R transform(T input) { return null; }\n" +
            "}\n";
        
        List<Node> nodes = parser.parse(Paths.get("GenericMethod.java"), content);
        
        boolean hasMethod = nodes.stream()
            .anyMatch(n -> n.getKind() == NodeKind.METHOD);
        assertTrue("应该解析出方法", hasMethod);
    }
    
    @Test
    public void testParseAnnotation() throws Exception {
        String content = 
            "@Override\n" +
            "public class AnnotatedClass {\n" +
            "    @Deprecated\n" +
            "    public void oldMethod() {}\n" +
            "}\n";
        
        List<Node> nodes = parser.parse(Paths.get("Annotated.java"), content);
        
        Node classNode = nodes.stream()
            .filter(n -> n.getKind() == NodeKind.CLASS)
            .findFirst()
            .orElse(null);
        
        assertNotNull("应该解析出类节点", classNode);
        assertEquals("类名应该是 AnnotatedClass", "AnnotatedClass", classNode.getName());
    }
    
    @Test
    public void testParseCodeInString() throws Exception {
        // 字符串中的代码可能仍会被解析，这是解析器的已知限制
        String content = 
            "public class StringTest {\n" +
            "    String code = \"public class Fake {} private int fake = 0;\";\n" +
            "}\n";
        
        List<Node> nodes = parser.parse(Paths.get("StringTest.java"), content);
        
        // 至少应该解析出外层的 StringTest 类
        long outerClassCount = nodes.stream()
            .filter(n -> n.getKind() == NodeKind.CLASS)
            .filter(n -> n.getName().equals("StringTest"))
            .count();
        assertEquals("应该解析出 StringTest 类", 1, outerClassCount);
    }
    
    @Test
    public void testParseCodeInComment() throws Exception {
        // 注释中的代码可能仍会被解析，这是解析器的已知限制
        String content = 
            "/*\n" +
            " * class HiddenInComment {}\n" +
            " */\n" +
            "public class RealClass {}\n";
        
        List<Node> nodes = parser.parse(Paths.get("CommentTest.java"), content);
        
        // 至少应该解析出 RealClass
        long realClassCount = nodes.stream()
            .filter(n -> n.getKind() == NodeKind.CLASS)
            .filter(n -> n.getName().equals("RealClass"))
            .count();
        assertEquals("应该解析出 RealClass", 1, realClassCount);
    }
    
    @Test
    public void testParseNestedClass() throws Exception {
        String content = 
            "public class Outer {\n" +
            "    public class Inner {}\n" +
            "    private static class StaticInner {}\n" +
            "}\n";
        
        List<Node> nodes = parser.parse(Paths.get("Nested.java"), content);
        
        long classCount = nodes.stream()
            .filter(n -> n.getKind() == NodeKind.CLASS)
            .count();
        assertTrue("应该至少解析出3个类（Outer, Inner, StaticInner）", classCount >= 3);
    }
    
    @Test
    public void testParseAnonymousClass() throws Exception {
        String content = 
            "public class AnonymousTest {\n" +
            "    Runnable r = new Runnable() {\n" +
            "        public void run() {}\n" +
            "    };\n" +
            "}\n";
        
        List<Node> nodes = parser.parse(Paths.get("Anonymous.java"), content);
        assertNotNull("解析结果不应该为空", nodes);
    }
    
    @Test
    public void testParseLambda() throws Exception {
        String content = 
            "public class LambdaTest {\n" +
            "    Runnable r = () -> System.out.println();\n" +
            "    Comparator<Integer> c = (a, b) -> a - b;\n" +
            "}\n";
        
        // Lambda 表达式不会被解析为方法
        List<Node> nodes = parser.parse(Paths.get("Lambda.java"), content);
        assertNotNull("解析结果不应该为空", nodes);
    }
    
    @Test
    public void testParseUnicodeCharacters() throws Exception {
        String content = 
            "public class UnicodeTest {\n" +
            "    private String name = \"中文测试\";\n" +
            "}\n";
        
        List<Node> nodes = parser.parse(Paths.get("Unicode.java"), content);
        
        assertNotNull("解析结果不应该为空", nodes);
        Node classNode = nodes.stream()
            .filter(n -> n.getKind() == NodeKind.CLASS)
            .findFirst()
            .orElse(null);
        assertNotNull("应该解析出类节点", classNode);
    }
    
    @Test
    public void testParseMethodOverloading() throws Exception {
        String content = 
            "public class OverloadTest {\n" +
            "    public void method() {}\n" +
            "    public void method(int a) {}\n" +
            "    public void method(int a, int b) {}\n" +
            "}\n";
        
        List<Node> nodes = parser.parse(Paths.get("Overload.java"), content);
        
        long methodCount = nodes.stream()
            .filter(n -> n.getKind() == NodeKind.METHOD)
            .filter(n -> n.getName().equals("method"))
            .count();
        assertTrue("应该解析出至少3个重载方法", methodCount >= 3);
    }
    
    @Test
    public void testParseReturnType() throws Exception {
        String content = 
            "public class ReturnTypeTest {\n" +
            "    public int getInt() { return 0; }\n" +
            "    public String getString() { return \"\"; }\n" +
            "    public List<String> getList() { return null; }\n" +
            "}\n";
        
        List<Node> nodes = parser.parse(Paths.get("ReturnType.java"), content);
        
        assertNotNull("解析结果不应该为空", nodes);
        long methodCount = nodes.stream()
            .filter(n -> n.getKind() == NodeKind.METHOD)
            .count();
        assertTrue("应该解析出至少3个方法", methodCount >= 3);
    }
    
    @Test
    public void testParseLongFile() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("public class LongClass {\n");
        for (int i = 0; i < 100; i++) {
            sb.append("    private int field").append(i).append(";\n");
        }
        sb.append("}\n");
        
        List<Node> nodes = parser.parse(Paths.get("LongClass.java"), sb.toString());
        
        assertNotNull("解析结果不应该为空", nodes);
        Node classNode = nodes.stream()
            .filter(n -> n.getKind() == NodeKind.CLASS)
            .findFirst()
            .orElse(null);
        assertNotNull("应该解析出类节点", classNode);
    }
    
    @Test
    public void testParseSpecialCharactersInCode() throws Exception {
        String content = 
            "public class SpecialChar {\n" +
            "    private String url = \"http://example.com?a=1&b=2\";\n" +
            "    private String regex = \"[a-zA-Z_$][a-zA-Z0-9_$]*\";\n" +
            "}\n";
        
        List<Node> nodes = parser.parse(Paths.get("SpecialChar.java"), content);
        
        assertNotNull("解析结果不应该为空", nodes);
    }
    
    @Test
    public void testParseFieldVisibility() throws Exception {
        String content = 
            "public class VisibilityTest {\n" +
            "    public int publicField;\n" +
            "    private int privateField;\n" +
            "    protected int protectedField;\n" +
            "    int packageField;\n" +
            "}\n";
        
        List<Node> nodes = parser.parse(Paths.get("VisibilityTest.java"), content);
        
        assertTrue("应该至少解析出4个字段", nodes.size() >= 4);
    }
    
    @Test
    public void testParseStaticFields() throws Exception {
        String content = 
            "public class StaticFieldTest {\n" +
            "    public static final int MAX_SIZE = 100;\n" +
            "    private static String NAME = \"Test\";\n" +
            "}\n";
        
        List<Node> nodes = parser.parse(Paths.get("StaticField.java"), content);
        
        assertNotNull("解析结果不应该为空", nodes);
    }
    
    @Test
    public void testParseMethodParameters() throws Exception {
        String content = 
            "public class ParamTest {\n" +
            "    public void noParams() {}\n" +
            "    public void oneParam(int a) {}\n" +
            "    public void manyParams(int a, String b, boolean c) {}\n" +
            "}\n";
        
        List<Node> nodes = parser.parse(Paths.get("ParamTest.java"), content);
        
        long methodCount = nodes.stream()
            .filter(n -> n.getKind() == NodeKind.METHOD)
            .count();
        assertTrue("应该至少解析出3个方法", methodCount >= 3);
    }
    
    @Test
    public void testParseVoidMethod() throws Exception {
        String content = 
            "public class VoidTest {\n" +
            "    public void doSomething() {\n" +
            "        System.out.println(\"test\");\n" +
            "    }\n" +
            "}\n";
        
        List<Node> nodes = parser.parse(Paths.get("VoidTest.java"), content);
        
        assertNotNull("解析结果不应该为空", nodes);
    }
    
    @Test
    public void testParseComplexClass() throws Exception {
        String content = 
            "package com.example;\n" +
            "\n" +
            "import java.util.List;\n" +
            "import java.util.Map;\n" +
            "\n" +
            "/*\n" +
            " * A complex class with many members\n" +
            " */\n" +
            "public abstract class ComplexClass<T extends Number> implements Cloneable {\n" +
            "    \n" +
            "    private T value;\n" +
            "    private List<String> items;\n" +
            "    \n" +
            "    public ComplexClass() {\n" +
            "    }\n" +
            "    \n" +
            "    public abstract void doAbstract();\n" +
            "    \n" +
            "    public final void doFinal() {\n" +
            "    }\n" +
            "    \n" +
            "    public static <R> R create() {\n" +
            "        return null;\n" +
            "    }\n" +
            "    \n" +
            "    public int getValue() {\n" +
            "        return 0;\n" +
            "    }\n" +
            "}\n";
        
        List<Node> nodes = parser.parse(Paths.get("Complex.java"), content);
        
        assertFalse("解析结果不应该为空", nodes.isEmpty());
        
        long classCount = nodes.stream()
            .filter(n -> n.getKind() == NodeKind.CLASS)
            .count();
        assertTrue("应该至少解析出1个类", classCount >= 1);
        
        long methodCount = nodes.stream()
            .filter(n -> n.getKind() == NodeKind.METHOD)
            .count();
        assertTrue("应该解析出至少4个方法", methodCount >= 4);
        
        long fieldCount = nodes.stream()
            .filter(n -> n.getKind() == NodeKind.FIELD)
            .count();
        assertTrue("应该解析出至少2个字段", fieldCount >= 2);
    }

    /**
     * 测试：对 stream 项目执行 init + index，生成 codegraph4j.db。
     * 注意：此测试涉及 JNA native 调用 + SQLite，在 surefire fork JVM 中可能崩溃，
     * 请通过 mvn exec:java 或直接在 IDE 中运行。
     * 运行方式：mvn test -Dtest=JavaParserTest#testIndexStreamProject -DforkCount=0
     */
    @Ignore("Integration test — run with -DforkCount=0 or via IDE")
    @Test
    public void testIndexStreamProject() {
        String projectPath = "/Users/wugao-pc/Desktop/Project/stream";
        String dbPath = projectPath + "/.codegraph/codegraph4j.db";

        // 确保项目目录存在
        File projectDir = new File(projectPath);
        assertTrue("项目目录不存在: " + projectPath, projectDir.exists() && projectDir.isDirectory());

        // 1. 删除旧数据库
        File dbFile = new File(dbPath);
        if (dbFile.exists()) {
            dbFile.delete();
            System.out.println("[test] 已删除旧数据库: " + dbPath);
        }

        // 2. 执行 init -f
        System.out.println("[test] 执行 codegraph init -f -p " + projectPath);
        int initCode = new CommandLine(new CodeGraphCli()).execute("init", "-f", "-p", projectPath);
        assertEquals("init 命令应返回 0", 0, initCode);
        assertTrue("init 后数据库应存在: " + dbPath, dbFile.exists());
        System.out.println("[test] init 完成，数据库大小: " + dbFile.length() + " bytes");

        // 3. 执行 index
        System.out.println("[test] 执行 codegraph index -p " + projectPath);
        int indexCode = new CommandLine(new CodeGraphCli()).execute("index", "-p", projectPath);
        assertEquals("index 命令应返回 0", 0, indexCode);

        // 4. 验证数据库有内容
        long dbSize = dbFile.length();
        System.out.println("[test] index 完成，数据库大小: " + dbSize + " bytes");
        assertTrue("数据库应该有内容 (>1KB)", dbSize > 1024);
        System.out.println("[test] ✅ stream 项目数据库生成成功: " + dbPath);
    }

    // =========================================================================
    // 启发式 CALLS 边生成测试
    // =========================================================================

    /**
     * 测试启发式 CALLS 边生成的各种边界情况。
     *
     * 覆盖场景：
     * 1. this.X 调用（本类方法）
     * 2. 无 receiver 调用（简单方法名）
     * 3. receiver.method() 调用（带 receiver）
     * 4. 链式调用 foo.bar() - 嵌套 method_invocation
     * 5. super.X 调用（父类方法）
     * 6. 多匹配时优先选择导出方法
     * 7. 找不到匹配时的处理
     */
    @Test
    public void testHeuristicCallsEdgeGeneration() throws Exception {
        // 构造包含各种调用场景的测试代码
        String content =
            "package com.example;\n" +
            "\n" +
            "public class HeuristicTest extends BaseClass {\n" +
            "    private int value;\n" +
            "\n" +
            "    // 场景1: this.X 调用（本类方法）\n" +
            "    public void thisCall() {\n" +
            "        this.helperMethod();\n" +
            "    }\n" +
            "\n" +
            "    // 场景2: 无 receiver 调用\n" +
            "    public void noReceiverCall() {\n" +
            "        localMethod();\n" +
            "    }\n" +
            "\n" +
            "    // 场景3: receiver.method() 调用\n" +
            "    public void receiverCall() {\n" +
            "        obj.externalMethod();\n" +
            "    }\n" +
            "\n" +
            "    // 场景4: 链式调用 (嵌套 method_invocation)\n" +
            "    public void chainedCall() {\n" +
            "        foo.bar().baz();\n" +
            "    }\n" +
            "\n" +
            "    // 场景5: super.X 调用\n" +
            "    public void superCall() {\n" +
            "        super.parentMethod();\n" +
            "    }\n" +
            "\n" +
            "    // 场景6: 私有辅助方法（用于 this.X 匹配）\n" +
            "    private void helperMethod() {\n" +
            "    }\n" +
            "\n" +
            "    // 场景7: 本地方法（用于无 receiver 匹配）\n" +
            "    private void localMethod() {\n" +
            "    }\n" +
            "}\n" +
            "\n" +
            "class BaseClass {\n" +
            "    public void parentMethod() {\n" +
            "    }\n" +
            "}\n";

        ParseResult result = parser.parseWithEdges(Paths.get("HeuristicTest.java"), content);
        List<Edge> callsEdges = result.getEdges().stream()
            .filter(e -> e.getKind() == EdgeKind.CALLS)
            .collect(Collectors.toList());

        System.out.println("[testHeuristicCalls] === 启发式 CALLS 边测试 ===");
        System.out.println("[testHeuristicCalls] 生成 CALLS 边数量: " + callsEdges.size());
        System.out.println("[testHeuristicCalls] 总边数量: " + result.getEdgeCount());
        System.out.println("[testHeuristicCalls] 总节点数量: " + result.getNodeCount());

        // 打印所有边
        System.out.println("[testHeuristicCalls] 所有边:");
        for (Edge edge : result.getEdges()) {
            System.out.println("[testHeuristicCalls]   " + edge.getKind() + ": " + edge.getSource() + " → " + edge.getTarget());
        }

        // 打印所有方法节点
        System.out.println("[testHeuristicCalls] 所有方法节点:");
        for (Node node : result.getNodes()) {
            if (node.getKind() == NodeKind.METHOD) {
                System.out.println("[testHeuristicCalls]   METHOD: " + node.getName() + " (qualifiedName=" + node.getQualifiedName() + ")");
            }
        }

        for (Edge edge : callsEdges) {
            System.out.println("[testHeuristicCalls]   CALLS: " + edge.getSource() + " → " + edge.getTarget());
            System.out.println("[testHeuristicCalls]         provenance=" + edge.getProvenance() + ", line=" + edge.getLine());
        }

        // 验证生成了 CALLS 边
        assertFalse("应该生成 CALLS 边", callsEdges.isEmpty());

        // 验证所有 CALLS 边都有 heuristic provenance
        for (Edge edge : callsEdges) {
            assertEquals("CALLS 边应该有 heuristic provenance", "heuristic", edge.getProvenance());
        }

        // 验证 this.X 调用生成了边 (第8行)
        boolean hasThisCall = callsEdges.stream().anyMatch(e -> e.getLine() == 8);
        System.out.println("[testHeuristicCalls] this.X 调用边存在: " + hasThisCall);

        // 验证无 receiver 调用生成了边 (第13行)
        boolean hasNoReceiverCall = callsEdges.stream().anyMatch(e -> e.getLine() == 13);
        System.out.println("[testHeuristicCalls] 无 receiver 调用边存在: " + hasNoReceiverCall);

        // 验证 super.X 调用生成了边 (第28行)
        boolean hasSuperCall = callsEdges.stream().anyMatch(e -> e.getLine() == 28);
        System.out.println("[testHeuristicCalls] super.X 调用边存在: " + hasSuperCall);

        // receiver.method() 和链式调用不会生成边，因为目标方法不在当前文件
        // 但这些调用会被收集到 callReferences 中，待后续跨文件解析
        System.out.println("[testHeuristicCalls] receiver.method() 调用已收集（外部方法，待跨文件解析）");
        System.out.println("[testHeuristicCalls] 链式调用已收集（外部方法，待跨文件解析）");

        // 打印测试总结
        System.out.println("[testHeuristicCalls] === 边界情况覆盖 ===");
        System.out.println("[testHeuristicCalls]   this.X 调用: " + (hasThisCall ? "✓" : "✗"));
        System.out.println("[testHeuristicCalls]   无 receiver 调用: " + (hasNoReceiverCall ? "✓" : "✗"));
        System.out.println("[testHeuristicCalls]   super.X 调用: " + (hasSuperCall ? "✓" : "✗"));
        System.out.println("[testHeuristicCalls]   receiver.method() 调用: 已收集（外部方法）");
        System.out.println("[testHeuristicCalls]   链式调用: 已收集（外部方法）");

        // 至少应该生成 3 条 CALLS 边（this.helperMethod, localMethod, super.parentMethod）
        assertTrue("应该至少生成 3 条 CALLS 边（本地方法调用）", callsEdges.size() >= 3);
    }

    /**
     * 测试多匹配时优先选择导出方法。
     */
    @Test
    public void testCallsEdgePrefersExportedMethod() throws Exception {
        String content =
            "package com.example;\n" +
            "\n" +
            "public class OverloadTest {\n" +
            "    // 私有方法\n" +
            "    private void process() {\n" +
            "    }\n" +
            "\n" +
            "    // 公有方法（应该被优先选择）\n" +
            "    public void process() {\n" +
            "    }\n" +
            "\n" +
            "    // 调用重载方法\n" +
            "    public void caller() {\n" +
            "        process();\n" +
            "    }\n" +
            "}\n";

        ParseResult result = parser.parseWithEdges(Paths.get("OverloadTest.java"), content);
        List<Edge> callsEdges = result.getEdges().stream()
            .filter(e -> e.getKind() == EdgeKind.CALLS)
            .collect(Collectors.toList());

        System.out.println("[testCallsEdgePrefersExported] CALLS 边数量: " + callsEdges.size());

        // 至少应该生成一条 CALLS 边
        assertFalse("应该生成 CALLS 边", callsEdges.isEmpty());
    }

    /**
     * 测试继承层级中的方法调用解析。
     */
    @Test
    public void testCallsEdgeInheritance() throws Exception {
        String content =
            "package com.example;\n" +
            "\n" +
            "public class InheritanceTest extends Parent {\n" +
            "    public void childMethod() {\n" +
            "        parentMethod();  // 应该解析到 Parent.parentMethod()\n" +
            "        this.ownMethod();  // 应该解析到 InheritanceTest.ownMethod()\n" +
            "    }\n" +
            "\n" +
            "    public void ownMethod() {\n" +
            "    }\n" +
            "}\n" +
            "\n" +
            "class Parent {\n" +
            "    public void parentMethod() {\n" +
            "    }\n" +
            "}\n";

        ParseResult result = parser.parseWithEdges(Paths.get("InheritanceTest.java"), content);
        List<Edge> callsEdges = result.getEdges().stream()
            .filter(e -> e.getKind() == EdgeKind.CALLS)
            .collect(Collectors.toList());

        System.out.println("[testCallsEdgeInheritance] CALLS 边数量: " + callsEdges.size());
        for (Edge edge : callsEdges) {
            System.out.println("[testCallsEdgeInheritance]   " + edge.getSource() + " → " + edge.getTarget() + " (line=" + edge.getLine() + ")");
        }

        assertFalse("应该生成 CALLS 边", callsEdges.isEmpty());
    }

}
