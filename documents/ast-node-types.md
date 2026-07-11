# CodeGraph4J AST 节点类型解释文档

本文档解释 codegraph4j 中使用的所有 tree-sitter-java AST 节点类型，包括节点含义、Java 代码示例和生成的图元素类型。

---

## 一、符号定义节点（生成 Node）

### 1. class_declaration

**含义**：类声明节点

**示例**：
```java
public class UserService {
    private String name;
}
```

**生成的图元素**：`Node{kind=CLASS, name='UserService', qualifiedName='com.example.UserService'}`

---

### 2. interface_declaration

**含义**：接口声明节点

**示例**：
```java
public interface UserRepository {
    User findById(Long id);
}
```

**生成的图元素**：`Node{kind=INTERFACE, name='UserRepository'}`

---

### 3. annotation_type_declaration

**含义**：注解类型声明节点

**示例**：
```java
public @interface Component {
    String value() default "";
}
```

**生成的图元素**：`Node{kind=INTERFACE, name='Component'}`（注解也是一种特殊的接口）

---

### 4. enum_declaration

**含义**：枚举声明节点

**示例**：
```java
public enum Color {
    RED, GREEN, BLUE
}
```

**生成的图元素**：`Node{kind=ENUM, name='Color'}`

---

### 5. enum_constant

**含义**：枚举常量节点

**示例**：
```java
public enum Color {
    RED,    // → enum_constant
    GREEN,  // → enum_constant
    BLUE    // → enum_constant
}
```

**生成的图元素**：`Node{kind=ENUM_MEMBER, name='RED'}`

---

### 6. method_declaration

**含义**：方法声明节点

**示例**：
```java
public User findById(Long id) {
    return userRepository.findById(id);
}
```

**生成的图元素**：`Node{kind=METHOD, name='findById', signature='findById(Long id)', returnType='User'}`

---

### 7. constructor_declaration

**含义**：构造器声明节点

**示例**：
```java
public UserService(UserRepository repo) {
    this.repo = repo;
}
```

**生成的图元素**：`Node{kind=METHOD, name='UserService', signature='UserService(UserRepository repo)', returnType='void'}`

---

### 8. field_declaration

**含义**：字段声明节点

**示例**：
```java
private UserRepository userRepository;
private String name = "default";
```

**生成的图元素**：`Node{kind=FIELD, name='userRepository', visibility=PRIVATE}`

---

### 9. import_declaration

**含义**：导入声明节点

**示例**：
```java
import java.util.List;
import com.example.User;
import java.util.*;
```

**生成的图元素**：`Node{kind=IMPORT, name='java.util.List'}`

---

### 10. package_declaration

**含义**：包声明节点

**示例**：
```java
package com.example.service;
```

**生成的图元素**：`Node{kind=MODULE, name='com.example.service'}`

---

## 二、关系边节点（生成 Edge）

### 11. method_invocation

**含义**：方法调用表达式

**示例**：
```java
userRepository.findById(id);
System.out.println("hello");
obj.method();
```

**生成的图元素**：`Edge{source=当前方法节点, target=被调用方法节点, kind=CALLS}`

---

### 12. super_method_invocation

**含义**：super 方法调用

**示例**：
```java
super.foo();
super();  // 调用父类构造器
```

**生成的图元素**：`Edge{source=当前方法节点, target=父类方法节点, kind=CALLS}`

---

### 13. field_access

**含义**：字段访问表达式

**示例**：
```java
this.name = "Alice";
return user.name;
obj.field = value;
```

**生成的图元素**：`Edge{source=当前方法节点, target=被访问字段节点, kind=REFERENCES}`

---

## 三、内部辅助节点（不直接生成图元素）

### 14. expression_statement

**含义**：表达式语句——表达式作为独立语句执行的包装节点

**示例**：
```java
userRepository.findById(id);  // expression_statement → method_invocation
count++;                      // expression_statement → update_expression
```

**处理方式**：不直接处理，递归到子表达式节点

---

### 15. scoped_identifier

**含义**：带作用域的标识符（点分名称）

**示例**：
```java
java.util.List   // scoped_identifier → identifier(java) → scoped_identifier(util.List)
com.example.User // scoped_identifier → identifier(com) → scoped_identifier(example.User)
```

**用途**：提取包名、类名等全限定名

---

### 16. type_identifier / generic_type / array_type

**含义**：类型标识符

**示例**：
```java
String                  // type_identifier
List<String>            // generic_type
int[]                   // array_type
Map<String, User>[]     // array_type → generic_type
```

**用途**：提取字段类型、方法返回类型

---

### 17. object_creation_expression

**含义**：对象创建表达式（new 语句）

**示例**：
```java
new User("Alice");
new HashMap<String, Integer>();
```

**生成的图元素**：`Edge{source=当前方法节点, target=构造器节点, kind=INSTANTIATES}`

---

## 四、类型映射总览

| tree-sitter-java 类型 | codegraph NodeKind | codegraph EdgeKind |
|---------------------|-------------------|-------------------|
| `class_declaration` | CLASS | - |
| `interface_declaration` | INTERFACE | - |
| `annotation_type_declaration` | INTERFACE | - |
| `enum_declaration` | ENUM | - |
| `enum_constant` | ENUM_MEMBER | - |
| `method_declaration` | METHOD | - |
| `constructor_declaration` | METHOD | - |
| `field_declaration` | FIELD | - |
| `import_declaration` | IMPORT | - |
| `package_declaration` | MODULE | - |
| `method_invocation` | - | CALLS |
| `super_method_invocation` | - | CALLS |
| `field_access` | - | REFERENCES |
| `object_creation_expression` | - | INSTANTIATES |

---

## 五、AST 结构示例

对于以下 Java 代码：

```java
package com.example;

import java.util.List;

public class UserService {
    private UserRepository repo;
    
    public User findById(Long id) {
        return repo.findById(id);
    }
}
```

对应的 AST 结构：

```
program
├── package_declaration → MODULE("com.example")
├── import_declaration → IMPORT("java.util.List")
└── class_declaration → CLASS("UserService")
    └── body
        ├── field_declaration → FIELD("repo")
        └── method_declaration → METHOD("findById")
            └── body
                └── expression_statement
                    └── method_invocation → CALLS(repo.findById)
```

---

## 六、处理流程

`TreeSitterExtractor.visitNode()` 的处理逻辑：

1. **按优先级匹配节点类型**
2. **匹配到类型 → 调用对应处理器 → 生成 Node/Edge → `visited=true`**
3. **未匹配到 → 递归遍历子节点**

```java
visited = handleTypeDeclaration(node, ctx, extractor, depth, visited);  // class/interface/enum
visited = handleMemberDeclaration(node, ctx, extractor, source, depth, visited);  // method/field
visited = handlePackageAndImport(node, ctx, extractor, source, depth, visited);  // package/import
visited = handleExpressions(node, ctx, extractor, source, depth, visited);  // invocation/access

if (!visited) {
    // 递归遍历子节点（如 expression_statement → method_invocation）
    for (int i = 0; i < namedChildCount; i++) {
        visitNode(ts.ts_node_named_child(node, i), ctx, extractor);
    }
}
```

---

## 七、相关代码位置

| 文件 | 用途 |
|------|------|
| `JavaExtractor.java` | 定义 AST 类型到图元素类型的映射 |
| `TreeSitterExtractor.java` | 遍历 AST 并提取节点和边 |
| `Node.java` / `Edge.java` | 定义图节点和边的数据结构 |
| `NodeKind.java` / `EdgeKind.java` | 定义图元素类型枚举 |
