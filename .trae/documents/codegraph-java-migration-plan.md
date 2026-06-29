# CodeGraph Java 版本迁移技术选型与重构方案

## 一、项目概述

### 1.1 原项目分析（Node.js 版本）

**项目定位**: 本地优先的代码智能系统，为 AI 编程助手（Claude Code、Cursor 等）提供语义代码知识图谱。

**核心功能**:
- 代码解析与知识图谱构建（支持 20+ 种语言）
- 符号搜索、调用图分析、影响半径分析
- MCP (Model Context Protocol) 服务器，为 AI agents 提供工具
- 文件监视与自动同步
- 框架特定的路由解析（Spring、Django、Express 等）

**技术栈**:
- TypeScript/JavaScript
- Tree-sitter WASM（代码解析）
- SQLite + FTS5（数据存储）
- Node.js Worker Threads（并行解析）
- Commander.js（CLI）

### 1.2 Java 版本目标

保持核心功能不变，使用 Java 技术栈重新实现，提供：
- 更好的 JVM 生态集成
- 更稳定的长时运行服务（Daemon 模式）
- 更强的跨平台兼容性
- 更好的 IDE 集成潜力

---

## 二、技术选型

### 2.1 核心框架与语言

| 层面 | Node.js 版本 | Java 版本候选 | 推荐方案 | 理由 |
|------|-------------|--------------|---------|------|
| **语言** | TypeScript | Java 8 / Java 11 / Java 17 | **Java 8** | 广泛部署，企业级兼容性，用户指定 |
| **构建工具** | npm/tsc | Maven / Gradle | **Maven** | JDK 8 兼容性好，XML 配置简单 |
| **CLI 框架** | Commander.js | Picocli / JCommander | **Picocli** | ANSI 颜色支持好，注解驱动，JDK 8 兼容 |

### 2.2 代码解析层

| 功能 | Node.js 版本 | Java 版本候选 | 推荐方案 | 理由 |
|------|-------------|--------------|---------|------|
| **解析引擎** | Tree-sitter WASM | Tree-sitter JNI / JavaParser / Eclipse JDT | **Tree-sitter JNI** | 保持多语言支持一致性，JNI 性能优于 WASM |
| **并行解析** | Worker Threads | ForkJoinPool / ExecutorService / CompletableFuture | **ForkJoinPool** | JDK 8 并行流支持，适合批量文件解析 |
| **语言支持** | WASM grammars | Tree-sitter grammars (native) | **Tree-sitter grammars** | 与 Node.js 版本共享 grammar 定义 |

**Tree-sitter JNI 方案详解**:
- 使用 [tree-sitter-java-bindings](https://github.com/tree-sitter/tree-sitter-java) 或自行封装 JNI
- 预编译各语言的 grammar 动态库（.so/.dll）
- 优点：保持 Node.js 版本的多语言支持能力
- 缺点：需要处理跨平台动态库加载

**备选方案：JavaParser + Eclipse JDT**:
- 仅支持 Java 语言，放弃多语言支持
- 优点：纯 Java 实现，无 JNI 复杂性
- 缺点：功能范围大幅缩减

**JDK 8 并发方案详解**:
- 使用 `ForkJoinPool.commonPool()` 或自定义 ForkJoinPool
- 并行流 `files.parallelStream().map(...)` 处理文件解析
- CompletableFuture 用于异步任务编排
- 线程池大小根据 CPU 核数动态调整

### 2.3 数据存储层

| 功能 | Node.js 版本 | Java 版本候选 | 推荐方案 | 理由 |
|------|-------------|--------------|---------|------|
| **数据库** | SQLite (node:sqlite) | SQLite JDBC / H2 / PostgreSQL | **SQLite JDBC** | 保持数据格式兼容，本地优先 |
| **全文搜索** | FTS5 | SQLite FTS5 / Lucene | **SQLite FTS5** | 与 Node.js 版本数据兼容 |
| **ORM** | 手写 SQL | JDBC / jOOQ / Hibernate | **jOOQ** | 类型安全 SQL，接近原生性能 |
| **连接管理** | 单连接 + WAL | HikariCP / 单连接 | **单连接 + WAL** | 保持与 Node.js 版本一致的并发模型 |

**SQLite JDBC 方案详解**:
- 使用 [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc)（Xerial）
- 支持 WAL 模式、FTS5
- 可复用 Node.js 版本的 schema.sql
- 跨平台支持（Windows/macOS/Linux）

### 2.4 MCP 服务器层

| 功能 | Node.js 版本 | Java 版本候选 | 推荐方案 | 理由 |
|------|-------------|--------------|---------|------|
| **MCP 协议** | 自实现 | 自实现 / MCP SDK | **自实现** | MCP 协议简单，自实现更灵活 |
| **传输层** | stdio / Unix socket | stdio / Unix socket (JNI) / TCP | **stdio + TCP** | stdio 为主要模式，TCP 用于 daemon |
| **JSON 处理** | JSON.stringify | Jackson / Gson | **Jackson** | 性能最优，生态成熟 |
| **Daemon 模式** | spawn + socket | 自实现 / Spring Boot | **自实现** | 保持轻量，避免 Spring Boot 重依赖 |

**MCP 协议实现要点**:
- JSON-RPC 2.0 格式
- stdio 传输（标准输入输出）
- 工具定义与注册
- 会话管理

### 2.5 文件监视层

| 功能 | Node.js 版本 | Java 版本候选 | 推荐方案 | 理由 |
|------|-------------|--------------|---------|------|
| **文件监视** | FSEvents / inotify | WatchService / Apache Commons IO | **Java WatchService** | JDK 内置，跨平台 |
| **Git 集成** | git CLI | JGit / git CLI | **JGit** | 纯 Java，无需外部依赖 |
| **忽略规则** | ignore 库 | 自实现 / PathMatcher | **自实现 (gitignore 解析)** | 复刻 Node.js 版本的 ignore 逻辑 |

### 2.6 并发与异步

| 功能 | Node.js 版本 | Java 版本候选 | 推荐方案 | 理由 |
|------|-------------|--------------|---------|------|
| **异步模型** | async/await | CompletableFuture / ExecutorService | **CompletableFuture** | JDK 8 支持，链式调用，接近 async/await 语义 |
| **并行处理** | Worker Threads | ForkJoinPool / parallelStream | **ForkJoinPool + parallelStream** | JDK 8 并行流，适合批量任务 |
| **锁机制** | Mutex (自实现) | ReentrantLock / StampedLock | **ReentrantLock** | JDK 8 支持，简单可靠 |
| **缓存** | LRU Cache (自实现) | Guava Cache / Caffeine | **Guava Cache** | JDK 8 兼容，功能丰富 |

**JDK 8 异步编程要点**:
- CompletableFuture 用于异步任务编排（替代 async/await）
- `CompletableFuture.supplyAsync()` + ExecutorService 处理异步任务
- `CompletableFuture.thenApply()` / `thenCompose()` 链式调用
- ForkJoinPool 用于并行流和批量任务处理

### 2.7 测试框架

| 功能 | Node.js 版本 | Java 版本候选 | 推荐方案 | 理由 |
|------|-------------|--------------|---------|------|
| **测试框架** | Vitest | JUnit 5 / TestNG | **JUnit 5** | 生态标准，参数化测试支持好 |
| **断言库** | 内置 | AssertJ / Hamcrest | **AssertJ** | 流式断言，可读性强 |
| **Mock** | 内置 | Mockito | **Mockito** | 标准选择 |

---

## 三、架构重构方案

### 3.1 模块划分

保持 Node.js 版本的模块划分，适配 Java 包结构：

```
com.codegraph/
├── core/                    # 核心类 (CodeGraph, Node, Edge, types)
│   ├── CodeGraph.java
│   ├── Node.java
│   ├── Edge.java
│   ├── FileRecord.java
│   └── types/
│       ├── NodeKind.java
│       ├── EdgeKind.java
│       ├── Language.java
│       └── ...
├── extraction/              # 代码提取
│   ├── ExtractionOrchestrator.java
│   ├── TreeSitterParser.java
│   ├── LanguageExtractor.java
│   └── languages/
│       ├── JavaExtractor.java
│       ├── PythonExtractor.java
│       ├── TypeScriptExtractor.java
│       └── ...
├── db/                      # 数据库层
│   ├── DatabaseConnection.java
│   ├── QueryBuilder.java
│   ├── SchemaManager.java
│   └── migrations/
├── resolution/              # 引用解析
│   ├── ReferenceResolver.java
│   ├── ImportResolver.java
│   ├── NameMatcher.java
│   └── frameworks/
│       ├── SpringResolver.java
│       ├── DjangoResolver.java
│       └── ...
├── graph/                   # 图查询
│   ├── GraphTraverser.java
│   ├── GraphQueryManager.java
│   └── traversal/
├── context/                 # 上下文构建
│   ├── ContextBuilder.java
│   ├── Formatter.java
│   └── markers/
├── mcp/                     # MCP 服务器
│   ├── MCPServer.java
│   ├── MCPSession.java
│   ├── MCPEngine.java
│   ├── tools/
│   │   ├── ExploreTool.java
│   │   ├── SearchTool.java
│   │   └── ...
│   └── transport/
│       ├── StdioTransport.java
│       └── TcpTransport.java
├── sync/                    # 文件同步
│   ├── FileWatcher.java
│   ├── GitIntegration.java
│   ├── WatchPolicy.java
│   └── daemon/
├── installer/               # 安装器
│   ├── AgentInstaller.java
│   ├── ConfigWriter.java
│   └── targets/
│       ├── ClaudeInstaller.java
│       ├── CursorInstaller.java
│       └── ...
├── cli/                     # CLI 命令
│   ├── CodeGraphCli.java
│   ├── commands/
│   │   ├── InitCommand.java
│   │   ├── IndexCommand.java
│   │   ├── ServeCommand.java
│   │   └── ...
│   └── progress/
├── telemetry/               # 遥测
│   └── TelemetryService.java
└── utils/                   # 工具类
    ├── FileLock.java
    ├── Mutex.java
    ├── LruCache.java
    └── ...
```

### 3.2 核心类设计

#### 3.2.1 CodeGraph 主类

```java
public class CodeGraph {
    private final DatabaseConnection db;
    private final QueryBuilder queries;
    private final String projectRoot;
    private final ExtractionOrchestrator orchestrator;
    private final ReferenceResolver resolver;
    private final GraphQueryManager graphManager;
    private final GraphTraverser traverser;
    private final ContextBuilder contextBuilder;
    private final FileLock fileLock;
    private FileWatcher watcher;
    
    // 生命周期方法
    public static CodeGraph init(String projectRoot, InitOptions options);
    public static CodeGraph open(String projectRoot, OpenOptions options);
    public void close();
    
    // 索引方法
    public IndexResult indexAll(ProgressCallback onProgress);
    public IndexResult indexFiles(List<String> filePaths);
    public SyncResult sync(ProgressCallback onProgress);
    
    // 查询方法
    public Node getNode(String id);
    public List<Node> searchNodes(String query, SearchOptions options);
    public List<Node> getNodesInFile(String filePath);
    public List<CallerInfo> getCallers(String nodeId, int maxDepth);
    public Subgraph getImpactRadius(String nodeId, int maxDepth);
    
    // 文件监视
    public boolean watch(WatchOptions options);
    public void unwatch();
    
    // 上下文构建
    public TaskContext buildContext(String query, BuildContextOptions options);
}
```

#### 3.2.2 MCP Server

```java
public class MCPServer {
    private final String projectPath;
    private MCPSession session;
    private MCPEngine engine;
    private Daemon daemon;
    
    public void start();
    public void stop();
    
    // 运行模式
    private void startDirect(String reason);
    private void startDaemonProcess();
    private void runProxyWithLocalHandshake(String root);
}
```

### 3.3 关键技术挑战与解决方案

#### 3.3.1 Tree-sitter JNI 集成

**挑战**: Tree-sitter 官方无 Java 绑定，需要 JNI 封装

**解决方案**:
1. 使用 [tree-sitter-java-bindings](https://github.com/tree-sitter/tree-sitter-java)（社区项目）
2. 或自行封装 JNI：
   - 编译 tree-sitter core 为动态库
   - 编译各语言 grammar 为动态库
   - Java 端通过 JNI 调用

**跨平台处理**:
- 使用 sqlite-jdbc 的跨平台动态库加载模式
- 预编译 Windows/macOS/Linux 的动态库
- 打包到 JAR 中，运行时解压加载

#### 3.3.2 JDK 8 并发模型

**挑战**: JDK 8 无 Virtual Threads，需要替代方案

**解决方案**:
- 使用 `ForkJoinPool` 处理并行解析任务
- 使用 `CompletableFuture` 处理异步任务编排
- 使用 `parallelStream()` 处理批量文件解析
- 线程池大小根据 `Runtime.getRuntime().availableProcessors()` 动态调整

**具体实现**:
```java
// 并行解析文件
List<ExtractionResult> results = files.parallelStream()
    .map(file -> parseFile(file))
    .collect(Collectors.toList());

// 异步任务编排
CompletableFuture<IndexResult> indexFuture = CompletableFuture
    .supplyAsync(() -> scanFiles(), executorService)
    .thenApply(files -> parseFiles(files))
    .thenApply(results -> storeResults(results));
```

#### 3.3.3 文件监视跨平台

**挑战**: Java WatchService 在不同平台行为不一致

**解决方案**:
- macOS: 使用 WatchService（底层 FSEvents）
- Linux: 使用 WatchService（底层 inotify）
- Windows: 使用 WatchService（底层 ReadDirectoryChangesW）
- 添加 debounce 逻辑（与 Node.js 版本一致）

#### 3.3.4 Git 集成

**挑战**: Node.js 版本使用 git CLI，Java 版本需要替代

**解决方案**:
- 使用 JGit（纯 Java Git 实现）
- 或保持 git CLI 调用（ProcessBuilder）
- 推荐 JGit：无外部依赖，更稳定

#### 3.3.5 MCP 协议实现

**挑战**: MCP 是新协议，无现成 Java SDK

**解决方案**:
- 自实现 JSON-RPC 2.0 处理
- Jackson 处理 JSON 序列化
- stdio 传输使用 System.in/System.out
- TCP 传输使用 Java Socket

---

## 四、实施路线图

### Phase 1: 基础框架（2-3 周）

1. **项目结构搭建**
   - MAVEN 项目初始化
   - 包结构设计
   - 基础类型定义（Node, Edge, Language 等）

2. **数据库层**
   - SQLite JDBC 集成
   - Schema 迁移（复用 schema.sql）
   - QueryBuilder 实现
   - FTS5 集成

3. **CLI 框架**
   - Picocli 集成
   - 基础命令（init, index, status, serve）

### Phase 2: 代码解析（3-4 周）

1. **Tree-sitter JNI 集成**
   - 动态库编译与打包
   - JNI 封装类
   - 解析 API 设计

2. **语言提取器**
   - Java 提取器（优先）
   - Python 提取器
   - TypeScript 提取器
   - 其他语言逐步添加

3. **ExtractionOrchestrator**
   - 文件扫描
   - 并行解析（Virtual Threads）
   - 结果存储

### Phase 3: 引用解析（2-3 周）

1. **基础解析器**
   - ImportResolver
   - NameMatcher
   - LRU Cache

2. **框架解析器**
   - Spring Resolver（优先）
   - Django Resolver
   - 其他框架逐步添加

### Phase 4: 图查询（1-2 周）

1. **GraphTraverser**
   - BFS 遍历
   - 调用图查询
   - 影响半径分析

2. **GraphQueryManager**
   - 上下文构建
   - 格式化输出

### Phase 5: MCP 服务器（2-3 周）

1. **MCP 协议实现**
   - JSON-RPC 处理
   - 工具定义与注册
   - stdio 传输

2. **Daemon 模式**
   - 后台进程管理
   - TCP 传输
   - 多客户端共享

### Phase 6: 文件同步（1-2 周）

1. **FileWatcher**
   - WatchService 集成
   - Debounce 逻辑
   - Git 集成（JGit）

2. **自动同步**
   - 变化检测
   - 增量索引

### Phase 7: 安装器与完善（1-2 周）

1. **Agent 安装器**
   - Claude Code 配置
   - Cursor 配置
   - 其他 agents

2. **测试与文档**
   - 单元测试
   - 集成测试
   - 使用文档

---

## 五、风险与决策点

### 5.1 Tree-sitter JNI vs 纯 Java 解析器

**决策点**: 是否使用 Tree-sitter JNI？

| 方案 | 优点 | 缺点 |
|------|------|------|
| **Tree-sitter JNI** | 多语言支持，与 Node.js 版本一致 | JNI 复杂性，跨平台动态库 |
| **JavaParser + JDT** | 纯 Java，无 JNI | 仅支持 Java，功能缩减 |
| **混合方案** | Java 用 JavaParser，其他用 Tree-sitter | 维护复杂度增加 |

**推荐**: Tree-sitter JNI（保持功能完整性）

### 5.2 Java 版本选择

**决策点**: Java 8 vs Java 11 vs Java 17？

| 版本 | 优点 | 缺点 |
|------|------|------|
| **Java 8** | 广泛部署，企业级兼容性 | 无 Virtual Threads，无新特性 |
| **Java 11** | LTS，性能优化 | 部署可能受限 |
| **Java 17** | LTS，新特性丰富 | 部署可能受限 |

**已确认**: Java 8（用户指定）

**JDK 8 限制与应对**:
- 无 Virtual Threads → 使用 ForkJoinPool + CompletableFuture
- 无 var 关键字 → 显式类型声明
- 无 Stream API 增强 → 使用基础 Stream API
- 无 Optional 增强 → 使用基础 Optional

### 5.3 数据兼容性

**决策点**: 是否保持与 Node.js 版本的数据兼容？

| 方案 | 优点 | 缺点 |
|------|------|------|
| **完全兼容** | 可复用现有索引 | Schema 约束 |
| **独立 Schema** | 更灵活 | 无法复用索引 |

**推荐**: 完全兼容（复用 schema.sql）

---

## 六、依赖清单

### 6.1 核心依赖

```groovy
// build.gradle
dependencies {
    // CLI
    implementation 'info.picocli:picocli:4.7.5'
    
    // 数据库
    implementation 'org.xerial:sqlite-jdbc:3.45.1.0'
    
    // JSON (JDK 8 兼容版本)
    implementation 'com.fasterxml.jackson.core:jackson-databind:2.16.1'
    
    // Git
    implementation 'org.eclipse.jgit:org.eclipse.jgit:6.8.0.202311291450-r'
    
    // 缓存 (JDK 8 兼容)
    implementation 'com.google.guava:guava:33.0.0-jre'
    
    // 日志
    implementation 'org.slf4j:slf4j-api:2.0.9'
    implementation 'ch.qos.logback:logback-classic:1.4.14'
    
    // 测试
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.1'
    testImplementation 'org.assertj:assertj-core:3.24.2'
    testImplementation 'org.mockito:mockito-core:5.8.0'
}

// JDK 8 配置
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
```

### 6.2 Tree-sitter 依赖

- 需自行编译或使用社区绑定
- 动态库打包策略参考 sqlite-jdbc

---

## 七、总结

### 推荐技术栈（JDK 8 版本）

| 层面 | 推荐方案 |
|------|---------|
| **语言** | Java 8（用户指定） |
| **构建** | Maven (XML 配置文件) |
| **CLI** | Picocli |
| **解析** | Tree-sitter JNI |
| **并发** | ForkJoinPool + CompletableFuture + parallelStream |
| **数据库** | SQLite JDBC |
| **全文搜索** | SQLite FTS5 |
| **JSON** | Jackson |
| **Git** | JGit |
| **缓存** | Guava Cache |
| **测试** | JUnit 5 + AssertJ + Mockito |

### 关键决策

1. **使用 Tree-sitter JNI**：保持多语言支持
2. **使用 Java 8**：用户指定，企业级兼容性
3. **保持数据兼容**：复用 schema.sql
4. **自实现 MCP**：协议简单，无现成 SDK
5. **使用 ForkJoinPool + CompletableFuture**：替代 Virtual Threads

### 实施优先级

1. 基础框架 + 数据库层（Phase 1）
2. Tree-sitter JNI + Java 提取器（Phase 2）
3. MCP 服务器（Phase 5）—— 尽早实现，验证核心功能
4. 引用解析 + 图查询（Phase 3-4）
5. 文件同步 + 安装器（Phase 6-7）

---

**请确认以上技术选型和重构方案，确认后我将开始实施。**