# 计划：添加 OpenFeign 和 Dubbo 远程调用框架解析器

## 概述

参考 SpringResolver 的实现模式，为 codegraph4j 新增 `OpenFeignResolver` 和 `DubboResolver`，使远程调用关系能够在知识图谱中被索引、关联和查询。

## 当前状态分析

### 已有架构（参考 SpringResolver）

| 组件                          | 路径                                                                                 | 职责                                           |
| --------------------------- | ---------------------------------------------------------------------------------- | -------------------------------------------- |
| `FrameworkResolver` 接口      | `src/main/java/com/codegraph/resolution/frameworks/FrameworkResolver.java`         | 定义 detect/extract/resolve/postExtract 四个核心方法 |
| `FrameworkRegistry`         | `src/main/java/com/codegraph/resolution/frameworks/FrameworkRegistry.java`         | 管理所有解析器，`registerDefaults()` 中注册             |
| `FrameworkExtractionResult` | `src/main/java/com/codegraph/resolution/frameworks/FrameworkExtractionResult.java` | 承载提取的节点 + 未解析引用                              |
| `UnresolvedRef`             | `src/main/java/com/codegraph/resolution/frameworks/UnresolvedRef.java`             | 待解析引用模型                                      |
| `ResolutionContext`         | `src/main/java/com/codegraph/resolution/ResolutionContext.java`                    | 提供 DB 查询和文件读取 API                            |
| `NodeKind`                  | `src/main/java/com/codegraph/core/types/NodeKind.java`                             | 节点类型枚举（已有 ROUTE、INTERFACE、METHOD）            |
| `EdgeKind`                  | `src/main/java/com/codegraph/core/types/EdgeKind.java`                             | 边类型枚举（已有 CALLS、REFERENCES）                   |

### 现有 SpringResolver 模式

1. **detect()**：检查 pom.xml 依赖 + 扫描文件注解
2. **extract()**：正则匹配注解 → 生成 ROUTE/CONSTANT 节点
3. **resolve()**：按命名约定查找目标节点

### 当前索引流程（IndexCommand）

```
阶段一：AST 提取 → nodes(CALLS边) 写入 DB
阶段二：框架提取 → framework nodes + resolve references → REFERENCES边 写入 DB
阶段三：postExtract() → 额外的合成节点
```

***

## 目标

使得被 `@FeignClient` 和 `@DubboReference` 标记的远程调用在 codegraph 中：

1. **能被检测到**：项目使用了 OpenFeign/Dubbo
2. **远程服务能被索引为节点**：FeignClient 接口的方法作为 ROUTE 节点，DubboService 类作为 ROUTE 节点
3. **远程调用关系能被关联**：本地方法 -> 远程服务方法之间生成 CALLS 边（provenance 标记框架来源）
4. **能被遍历查询**：通过图遍历能发现跨服务调用链

***

## 实施方案

### 一、新增 `OpenFeignResolver`

**文件**：`src/main/java/com/codegraph/resolution/frameworks/OpenFeignResolver.java`

#### 检测逻辑 (detect)

* 策略 1：检查 `pom.xml` 中是否包含 `spring-cloud-starter-openfeign` 或 `spring-cloud-openfeign`

* 策略 2：扫描前 100 个 `.java` 文件，查找 `@FeignClient` 注解

#### 提取逻辑 (extract)

扫描每个 `.java` 文件内容，分两步提取：

**步骤 A——提取 Feign 接口的远程端点（ROUTE 节点）**

定位 `@FeignClient` 注解修饰的接口：

* 正则匹配：`@FeignClient\s*\(\s*(?:name\s*=\s*)?\"([^\"]+)\"` 等

* 提取 `name`（服务名）、`url`、`path`（基础路径前缀）

* 在此接口范围内，提取每个方法上的 `@RequestMapping` / `@GetMapping` / `@PostMapping` 等注解

* 为每个方法生成一个 **ROUTE 节点**：

  * `name`：`"FEIGN {httpMethod} {fullPath}"`（结合 `@FeignClient.path` 前缀 + 方法路径）

  * `qualifiedName`：`"{interfaceQualifiedName}:{httpMethod} {fullPath}"`

  * `decorators`：添加 `@FeignClient(name="xxx")` 标记

  * `signature`：记录方法的 Java 签名（参数、返回类型）

**步骤 B——标记字段注入点，建立调用关联系**

扫描类文件，查找注入 Feign 客户端的字段：

* 正则匹配 `@Autowired` 或构造函数注入，字段类型名以 `Client` 结尾的接口

* 通过 `import` 语句或全限定名确认该字段类型确实是被 `@FeignClient` 标记的接口

* 生成 **UnresolvedRef**：从当前类的 METHOD 节点 → 引用目标为 Feign 接口的 METHOD 节点

#### 解析逻辑 (resolve)

* 接收一个 UnresolvedRef，refName 是 Feign 接口的方法全限定名

* 通过 `context.getNodesByQualifiedName()` 查找对应的 Feign 方法 ROUTE 节点

* 返回目标节点 ID

***

### 二、新增 `DubboResolver`

**文件**：`src/main/java/com/codegraph/resolution/frameworks/DubboResolver.java`

#### 检测逻辑 (detect)

* 策略 1：检查 `pom.xml` 中是否包含 `dubbo`（`org.apache.dubbo` / `com.alibaba.dubbo`）

* 策略 2：扫描 `.java` 文件，查找 `@DubboReference`、`@DubboService`、`@Service`（dubbo）注解

* 策略 3：检查 Spring XML 配置（`dubbo:reference` / `dubbo:service`）

#### 提取逻辑 (extract)

**步骤 A——提取 Dubbo 服务提供者（ROUTE 节点）**

* 正则匹配 `@DubboService`（或 `@Service(interfaceClass = ...)`）注解的类

* 读取类实现的接口全限定名（从 `implements` 语句提取）

* 为每个被暴露的方法生成 **ROUTE 节点**：

  * `name`：`"DUBBO {interfaceName}.{methodName}"`

  * `qualifiedName`：`"{filePath}:DUBBO {interfaceName}.{methodName}"`

  * `decorators`：添加 `@DubboService` 标记

**步骤 B——提取 Dubbo 远程引用点**

* 正则匹配 `@DubboReference`（或 `@Reference`）注解的字段

* 获取字段类型（接口名）

* 生成 **UnresolvedRef**：从当前文件的 METHOD 节点 → 引用 Dubbo 服务接口的方法

#### 解析逻辑 (resolve)

* 通过字段类型接口名，查找实现该接口且被 `@DubboService` 标记的类

* 通过 `context.getNodesByName(simpleName)` 查找 Dubbo 服务接口的方法 ROUTE 节点

***

### 三、注册到 FrameworkRegistry

**文件**：`src/main/java/com/codegraph/resolution/frameworks/FrameworkRegistry.java`

在 `registerDefaults()` 方法中添加：

```java
// 远程调用框架
register(new OpenFeignResolver());
register(new DubboResolver());
```

***

### 四、可选：增强 NodeKind（如果需要区分远程服务）

当前 `NodeKind.ROUTE` 可用于远程端点，但如果需要更细粒度的区分（如区分 HTTP 路由和 RPC 路由），可以：

* 方案 A（推荐，改动最小）：复用 `NodeKind.ROUTE`，在 `decorators` 中通过 `@FeignClient` / `@DubboService` 标记区分

* 方案 B：新增 `NodeKind.REMOTE_SERVICE` 等新类型

本计划采用 **方案 A**，保持改动最小。

***

## 涉及文件清单

| 文件                                                                         | 操作     | 说明                           |
| -------------------------------------------------------------------------- | ------ | ---------------------------- |
| `src/main/java/com/codegraph/resolution/frameworks/OpenFeignResolver.java` | **新建** | OpenFeign 框架解析器              |
| `src/main/java/com/codegraph/resolution/frameworks/DubboResolver.java`     | **新建** | Dubbo 框架解析器                  |
| `src/main/java/com/codegraph/resolution/frameworks/FrameworkRegistry.java` | **修改** | registerDefaults() 中注册两个新解析器 |

***

## 假设与决策

1. **复用现有节点/边类型**：ROUTE 节点 + CALLS 边 + provenance 标记，不新增枚举值
2. **正则解析方案**：与 SpringResolver 一致，使用正则匹配注解，不依赖完整的 AST 二次遍历
3. **解析分阶段**：extract 阶段只提取节点定义，postExtract 阶段（预留）可做跨文件的调用关联分析
4. **不增加 Maven 依赖**：不使用 OpenFeign/Dubbo 的 SDK，纯文本正则解析

***

## 验证方式

1. **单元检测**：在有 `@FeignClient` 接口的测试项目中运行 `detect()`，确认返回 true
2. **节点提取**：运行 `index` 后查询数据库，确认生成了 FEIGN/DUBBO 前缀的 ROUTE 节点
3. **边关联**：确认本地 Service 调用 FeignClient 的地方生成了 CALLS 边（provenance=framework:openfeign）
4. **图遍历**：使用 `traverse` 命令查询调用链，确认跨服务调用可被发现

