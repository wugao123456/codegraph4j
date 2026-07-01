# Sync 功能实现计划

## 概述

参考 `codegraph` (Node.js) 项目的 sync 子系统，在 `codegraph4j` (Java) 中实现功能等价、基本逻辑不变的 sync 功能。包括新增 `sync` CLI 命令、文件变更监听器（FileWatcher）、Git 钩子管理、Git Worktree 检测、以及监听策略决策。

## 当前状态分析

### 已有基础设施

* **CLI 层**: `CodeGraphCli.java` 注册了 5 个子命令（init/index/status/serve/traverse）

* **文件锁**: `FileLockUtil.java` — 跨进程 `FileChannel.tryLock()`

* **数据库**: `DatabaseConnection`（WAL 模式 + 事务）、`QueryBuilder`（完整 CRUD）、`files` 表（path/content\_hash/size/modified\_at/indexed\_at）

* **索引逻辑**: `IndexCommand.java` 实现了完整的两阶段索引流程（文件解析 + 框架提取），包含文件级 hash 增量跳过

* **未实现**: `--watch` 参数仅有 stub，无 sync 命令

### codegraph (Node.js) sync 子系统结构

| 模块          | 路径                         | 功能                                                    |
| ----------- | -------------------------- | ----------------------------------------------------- |
| FileWatcher | `src/sync/watcher.ts`      | 文件监听 + 去抖同步 + 锁重试 + 降级                                |
| WatchPolicy | `src/sync/watch-policy.ts` | 环境变量 / WSL2 检测决定是否启用监听                                |
| GitHooks    | `src/sync/git-hooks.ts`    | 安装/移除 post-commit、post-merge、post-checkout 钩子         |
| Worktree    | `src/sync/worktree.ts`     | 检测跨 worktree 索引借用                                     |
| Sync入口      | `src/index.ts`             | `CodeGraph.sync()` 方法                                 |
| Sync引擎      | `src/extraction/index.ts`  | `ExtractionOrchestrator.sync()` + `getChangedFiles()` |

***

## 拟议变更

### 1. 新增 `SyncCommand` CLI 命令

**文件**: `src/main/java/com/codegraph/cli/commands/SyncCommand.java`（新建）

**功能**: 实现 `codegraph4j sync` 命令

* 打开数据库 → 创建 QueryBuilder → 执行增量同步 → 显示结果

* 支持 `-p/--project`、`-q/--quiet`（静默模式，用于 Git 钩子触发）

* 输出格式参照 codegraph：显示 filesAdded/filesModified/filesRemoved/nodesUpdated/durationMs

* 使用 `FileLockUtil` 获取跨进程文件锁（`codegraph.lock`），防止并发同步

**参考 Node.js 逻辑**:

* `codegraph sync` CLI 入口调用 `CodeGraph.sync()` → `ExtractionOrchestrator.sync()`

* 同步流程：扫描文件系统 → 与 DB 对账 → (size+mtime) 快速预过滤 → SHA256 hash 比较 → 解析变更文件

### 2. 新增 `SyncOrchestrator` 核心同步引擎

**文件**: `src/main/java/com/codegraph/sync/SyncOrchestrator.java`（新建）

**功能**: 执行增量同步的核心逻辑，从 `IndexCommand` 中提炼出可复用的 sync 方法

**关键方法**:

```java
// 执行增量同步
SyncResult sync(Path projectRoot, QueryBuilder queryBuilder, 
                Consumer<ProgressEvent> onProgress);

// 获取自上次索引以来变更的文件（Git 快速路径 + 文件系统回退）
List<String> getChangedFiles(Path projectRoot, QueryBuilder queryBuilder);
```

**SyncResult 内部类**:

```java
class SyncResult {
    int filesChecked;     // 检查的文件总数
    int filesAdded;       // 新增文件数
    int filesModified;    // 修改文件数
    int filesRemoved;     // 删除文件数
    int nodesUpdated;     // 更新的节点总数
    long durationMs;      // 耗时（毫秒）
    List<String> changedFilePaths; // 变更文件路径
}
```

**同步流程（参照 codegraph ExtractionOrchestrator.sync()）**:

1. 扫描项目目录获取当前源文件列表（复用 `IndexCommand.findCodeFiles` 逻辑）
2. 从数据库 `files` 表获取已跟踪文件
3. 对账（Reconcile）：

   * **已删除**: DB 中存在但文件系统中不存在 → `deleteNodesByFile()` 删除

   * **已新增**: 文件系统中存在但 DB 中不存在 → 标记为待索引

   * **已修改**: 用 (size, mtime) 快速预过滤，仅对疑似变更文件计算 SHA256 hash
4. 对变更的文件执行解析 + 索引（复用 ParserFactory + CodeParser）
5. 执行框架提取（复用 FrameworkRegistry 逻辑）
6. 返回 SyncResult

**与 IndexCommand 的关系**:

* `IndexCommand` 将重构为委托给 `SyncOrchestrator`，实现 `force=true` 时的全量索引

* `SyncCommand` 委托给 `SyncOrchestrator`，实现增量同步

### 3. 新增 `FileWatcher` 文件监听器

**文件**: `src/main/java/com/codegraph/sync/FileWatcher.java`（新建）

**功能**: 使用 `java.nio.file.WatchService` 监听项目目录的文件变更并触发去抖同步

**关键设计（参照 codegraph FileWatcher）**:

* **去抖机制**: 默认 2000ms 去抖，通过 `ScheduledExecutorService` 实现

* **平台策略**:

  * macOS/Windows: 使用 `WatchService` 注册单个递归目录（register recursive）

  * Linux: 逐目录注册（每个非忽略目录一个 WatchKey）

* **pendingFiles**: `ConcurrentHashMap<String, PendingFileInfo>` 跟踪待同步文件

* **锁竞争重试**: 最多 5 次，指数退避（debounceMs \* 2^(n-1)，上限 30s）

* **降级机制**: 超过重试上限后永久降级，回调 `onDegraded`

* **忽略过滤**: 复用 IndexCommand 的 excludePatterns（`.git`、`.codegraph`、`node_modules`、`target`、`build`、`dist`）

**关键属性**:

```java
class FileWatcher {
    Path projectRoot;
    WatchService watchService;
    Map<WatchKey, Path> watchKeys;        // WatchKey -> 目录路径
    Map<String, PendingFileInfo> pendingFiles; // 项目相对路径 -> 文件信息
    ScheduledExecutorService scheduler;
    ScheduledFuture<?> debounceFuture;
    int lockRetryCount;                    // 锁竞争重试计数
    String degradedReason;                 // 降级原因（单向闩锁）
    boolean stopped;
    // 回调
    Consumer<SyncResult> onSyncComplete;
    Consumer<Throwable> onSyncError;
    Consumer<String> onDegraded;
}
```

**关键方法**:

* `start(): boolean` — 启动监听，先检查 `watchDisabledReason()`，按平台策略注册 WatchService

* `stop()` — 停止监听，清理资源

* `isActive(): boolean` — 是否活跃

* `isDegraded(): boolean` — 是否已降级

* `getPendingFiles(): List<PendingFile>` — 获取待同步文件快照

* `flush()` — 执行同步（调用 syncFn），管理 pendingFiles

* `scheduleSync()` — 去抖后调度同步

* `scheduleRetrySync(long delayMs)` — 锁竞争失败后重试

* `handleChange(Path rel)` — 处理文件变更事件

**配置常量**:

```java
int MAX_LOCK_RETRIES = 5;
long MAX_LOCK_RETRY_DELAY_MS = 30_000;
int DEFAULT_MAX_DIR_WATCHES = 50_000;
long DEFAULT_DEBOUNCE_MS = 2000;
```

**Promise 到 Java 的对应关系**:

* Node.js `syncFn(): Promise<{filesChanged, durationMs}>` → Java `Callable<SyncResult>` 或 `Supplier<SyncResult>`

* Node.js `setTimeout` → Java `ScheduledExecutorService.schedule()`

* Node.js `fs.watch` → Java `WatchService`

### 4. 新增 `WatchPolicy` 监听策略

**文件**: `src/main/java/com/codegraph/sync/WatchPolicy.java`（新建）

**功能**: 决定文件监听器是否应该运行（参照 codegraph watch-policy）

**关键方法**:

```java
// 返回禁用原因，null 表示可以启用
static String watchDisabledReason(Path projectRoot);

// 检测 WSL 环境
static boolean isWsl();

// 检测是否为 Windows 驱动器挂载 (/mnt/[a-z])
static boolean isWindowsDriveMount(Path projectRoot);
```

**优先级**（参照 codegraph）:

1. `CODEGRAPH_NO_WATCH=1` → 禁用（最高优先级）
2. `CODEGRAPH_FORCE_WATCH=1` → 强制启用
3. WSL + `/mnt/[a-z]` 驱动器 → 禁用（递归 fs.watch 太慢）

### 5. 新增 `GitHooksManager` Git 钩子管理

**文件**: `src/main/java/com/codegraph/sync/GitHooksManager.java`（新建）

**功能**: 在文件监听不可用时（如 WSL2），安装 Git 钩子作为备选方案

**关键方法**:

```java
// 检查是否为 Git 仓库
static boolean isGitRepo(Path projectRoot);

// 获取 git hooks 目录（考虑 core.hooksPath 和 worktree）
static Path gitHooksDir(Path projectRoot);

// 安装同步钩子（post-commit, post-merge, post-checkout）
GitHookResult installGitSyncHook(Path projectRoot, List<GitHookName> hooks);

// 移除同步钩子
GitHookResult removeGitSyncHook(Path projectRoot, List<GitHookName> hooks);

// 检查钩子是否已安装
boolean isSyncHookInstalled(Path projectRoot, List<GitHookName> hooks);
```

**钩子内容**（Shell 脚本片段，由标记注释界定，参照 codegraph）:

```sh
# >>> codegraph sync hook >>>
# Keeps the CodeGraph index fresh while the live file watcher is off
# (e.g. WSL2 /mnt drives). Runs in the background so it never blocks git.
# Managed by codegraph; remove with `codegraph uninit` or delete this block.
if command -v codegraph4j >/dev/null 2>&1; then
  ( codegraph4j sync -q >/dev/null 2>&1 & ) >/dev/null 2>&1
fi
# <<< codegraph sync hook <<<
```

**参照 codegraph 的幂等设计**:

* 安装时：去除已有标记块，追加新块，保留用户自定义内容

* 移除时：仅去除标记块，若仅剩 shebang 则删除整个文件

### 6. 新增 `GitWorktreeDetector` Git Worktree 检测

**文件**: `src/main/java/com/codegraph/sync/GitWorktreeDetector.java`（新建）

**功能**: 检测跨 worktree 索引借用问题

**关键方法**:

```java
// 获取目录对应的 git worktree 根路径
static Path gitWorktreeRoot(Path dir);

// 检测 worktree 索引借用
static WorktreeIndexMismatch detectWorktreeIndexMismatch(Path startPath, Path indexRoot);

// 生成警告信息
static String worktreeMismatchWarning(WorktreeIndexMismatch m);
static String worktreeMismatchNotice(WorktreeIndexMismatch m);
```

**逻辑**（参照 codegraph worktree.ts）:

* `git rev-parse --show-toplevel` 获取 per-worktree 根

* 比较 worktreeRoot 与 indexRoot（realpath 解析后）

* 若不同且 indexRoot 本身也是 worktree 根，则报告借用

### 7. 修改 `IndexCommand` 重构

**文件**: `src/main/java/com/codegraph/cli/commands/IndexCommand.java`（修改）

**变更**:

* 将核心索引逻辑提取到 `SyncOrchestrator`，`IndexCommand` 委托给 `SyncOrchestrator`

* `--force` 参数对应全量重新索引（不走增量跳过逻辑）

* 保留文件扫描、解析器匹配、节点边存储的逻辑不变

* `watchFiles()` stub 替换为真正的 `FileWatcher` 调用

### 8. 修改 `CodeGraphCli` 注册 SyncCommand

**文件**: `src/main/java/com/codegraph/cli/CodeGraphCli.java`（修改）

**变更**: 在 `subcommands` 数组中加入 `SyncCommand.class`

### 9. 新增 `sync` 包目录结构

```
src/main/java/com/codegraph/sync/
├── SyncOrchestrator.java      # 核心同步引擎
├── FileWatcher.java           # 文件变更监听器
├── WatchPolicy.java           # 监听策略决策
├── GitHooksManager.java       # Git 钩子管理
├── GitWorktreeDetector.java   # Git Worktree 检测
└── SyncResult.java            # 同步结果 DTO（可内嵌于 SyncOrchestrator）
```

### 10. CLI 命令目录新增

```
src/main/java/com/codegraph/cli/commands/
└── SyncCommand.java           # sync CLI 命令（新建）
```

***

## 假设与决策

1. **Java WatchService 替代 fs.watch**: Java 标准库的 `WatchService` 功能等价于 Node.js `fs.watch`，在 macOS 上底层使用 FSEvents，在 Linux 上使用 inotify。不做 Native 桥接。
2. **不实现 WSL2 检测的** **`/proc/version`** **检查**: Java 不直接访问 `/proc/version`，WSL 检测仅通过环境变量 `WSL_DISTRO_NAME` 和 `WSL_INTEROP` 判断。
3. **去抖使用 ScheduledExecutorService**: 替代 Node.js 的 `setTimeout`。
4. **Git 命令通过 ProcessBuilder 调用**: 替代 Node.js 的 `execFileSync('git', ...)`。
5. **syncFn 使用 Callable<SyncResult>**: 替代 Node.js 的 `Promise-returning syncFn`。
6. **框架提取作为 sync 的一个阶段**: 参照 codegraph 在 `CodeGraph.sync()` 中会运行后提取阶段。
7. **环境变量命名保持一致**: `CODEGRAPH_NO_WATCH`、`CODEGRAPH_FORCE_WATCH`、`CODEGRAPH_MAX_DIR_WATCHES`。
8. **文件锁位置**: `.codegraph/codegraph.lock`（与 codegraph 的 `codegraph.lock` 一致）。

***

## 验证步骤

1. **编译验证**: 执行 `mvn compile` 确保所有新文件编译通过
2. **单元测试**: 编写针对 `SyncOrchestrator`、`FileWatcher`、`GitHooksManager`、`WatchPolicy` 的测试
3. **集成测试**:

   * `codegraph4j sync` 在已索引项目上运行，验证增量同步跳过未变化文件

   * 修改文件后运行 `codegraph4j sync`，验证变化文件被重新索引

   * `codegraph4j index --watch` 验证文件监听和自动同步
4. **Git 钩子测试**: 在有 Git 的项目中安装/移除钩子，验证幂等性
5. **Worktree 测试**: 在多 worktree 环境下检测索引借用

***

## 实现顺序

1. `SyncResult` — 数据类，无依赖
2. `SyncOrchestrator` — 核心引擎，依赖已有的解析器/DB
3. `SyncCommand` — CLI 命令，依赖 SyncOrchestrator
4. 修改 `CodeGraphCli` — 注册 SyncCommand
5. 重构 `IndexCommand` — 委托给 SyncOrchestrator
6. `WatchPolicy` — 独立模块
7. `FileWatcher` — 依赖 WatchPolicy + SyncOrchestrator
8. `GitHooksManager` — 独立模块
9. `GitWorktreeDetector` — 独立模块
10. 在 `StatusCommand` 中集成 worktree 检测警告

