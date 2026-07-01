# --print-config 参数实施计划

## Summary

为 `install` 命令添加 `--print-config` 参数，运行后打印所有 target 的 MCP 配置路径及其当前状态（文件是否存在、是否已配置 codegraph），不执行任何写入操作。

对标 codegraph AgentTarget 接口的 `describePaths()` / `detect()` 方法。

---

## Phase 1: Explore 结果

### 现有代码关键点

**`AgentTarget.java`** — 4 个方法，无路径查询能力：
```java
TargetId id();
String displayName();
boolean supportsLocation(Location loc);
WriteResult install(Location loc, Path projectRoot, String jarPath);
WriteResult uninstall(Location loc);
```

**`JsonMCPTarget.java`** — 抽象基类，已有关键抽象方法：
```java
protected abstract Path getGlobalConfigPath();
protected abstract Path getLocalConfigPath(Path projectRoot);
```
`install()` / `uninstall()` 已有检测 "codegraph" 是否存在的逻辑，`describePaths()` 可复用。

**`ClaudeTarget.java`** — 额外管理 `CLAUDE.md` instructions 文件。

**`TraeTarget.java`** — 仅支持 `Location.GLOBAL`。

**`InstallCommand.java`** — 已有 option 定义模式：
```java
@CommandLine.Option(names = {"--target"}, defaultValue = "all", ...)
private String target;

@CommandLine.Option(names = {"--global"}, defaultValue = "false", ...)
private boolean globalInstall;

@CommandLine.Option(names = {"-p", "--project"}, defaultValue = ".", ...)
private String projectRoot;
```

### codegraph (TS) 参考

- `describePaths(loc)` → `string[]`：返回会被触碰的文件路径列表
- `detect(loc)` → `DetectionResult { installed, alreadyConfigured, configPath }`：检测 agent 是否已安装/已配置
- `printConfig(loc)` → `string`：打印配置片段（不写文件系统）

---

## Proposed Changes

### 新增文件

```
src/main/java/com/codegraph/install/target/ConfigPathInfo.java
```

### 修改文件

```
src/main/java/com/codegraph/install/target/AgentTarget.java      # 新增 describePaths()
src/main/java/com/codegraph/install/target/JsonMCPTarget.java    # 实现 describePaths()
src/main/java/com/codegraph/install/target/ClaudeTarget.java     # 覆写 describePaths() 追加 CLAUDE.md
src/main/java/com/codegraph/install/InstallCommand.java          # 新增 --print-config 参数
```

---

### 详细设计

#### 1. 新增 `ConfigPathInfo.java`

```java
package com.codegraph.install.target;

/**
 * 描述单个配置路径的状态信息。
 */
public class ConfigPathInfo {

    /** 文件路径 */
    public final String path;

    /** 文件是否存在 */
    public final boolean exists;

    /** 是否已包含 codegraph MCP 配置 */
    public final boolean configured;

    /** 人类可读描述（如 "MCP config", "Instructions"） */
    public final String description;

    public ConfigPathInfo(String path, boolean exists, boolean configured, String description) {
        this.path = path;
        this.exists = exists;
        this.configured = configured;
        this.description = description;
    }
}
```

---

#### 2. 修改 `AgentTarget.java` — 新增 `describePaths()`

在接口中新增方法：

```java
/**
 * 返回此 target 管理的所有配置文件路径及其当前状态。
 * 对标 codegraph describePaths() + detect() 的组合。
 *
 * @param loc         安装位置
 * @param projectRoot 项目根目录
 * @return 配置文件路径信息列表
 */
List<ConfigPathInfo> describePaths(Location loc, Path projectRoot);
```

---

#### 3. 修改 `JsonMCPTarget.java` — 实现 `describePaths()`

```java
@Override
public List<ConfigPathInfo> describePaths(Location loc, Path projectRoot) {
    List<ConfigPathInfo> result = new ArrayList<>();
    Path configPath = loc == Location.GLOBAL
            ? getGlobalConfigPath()
            : getLocalConfigPath(projectRoot);

    boolean exists = Files.exists(configPath);
    boolean configured = false;
    if (exists) {
        try {
            Map<String, Object> json = Shared.readJsonFile(configPath);
            @SuppressWarnings("unchecked")
            Map<String, Object> mcpServers = (Map<String, Object>) json.get("mcpServers");
            configured = mcpServers != null && mcpServers.containsKey("codegraph");
        } catch (Exception ignored) {
            // ignore
        }
    }

    result.add(new ConfigPathInfo(
            configPath.toString(), exists, configured, "MCP config"));
    return result;
}
```

---

#### 4. 修改 `ClaudeTarget.java` — 覆写 `describePaths()` 追加 CLAUDE.md

```java
@Override
public List<ConfigPathInfo> describePaths(Location loc, Path projectRoot) {
    List<ConfigPathInfo> result = super.describePaths(loc, projectRoot);

    // 追加 CLAUDE.md instructions 路径
    Path instructionsPath = loc == Location.GLOBAL
            ? Paths.get(System.getProperty("user.home"), ".claude", "CLAUDE.md")
            : projectRoot.resolve(".claude").resolve("CLAUDE.md");

    boolean exists = Files.exists(instructionsPath);
    boolean configured = false;
    if (exists) {
        try {
            String content = new String(Files.readAllBytes(instructionsPath));
            configured = content.contains("<!-- CODEGRAPH_START -->");
        } catch (Exception ignored) {}
    }

    result.add(new ConfigPathInfo(
            instructionsPath.toString(), exists, configured, "Instructions"));
    return result;
}
```

---

#### 5. 修改 `InstallCommand.java` — 新增 `--print-config` 参数

新增 option：

```java
@CommandLine.Option(names = {"--print-config"}, defaultValue = "false",
        description = "Print config paths and status without making changes")
private boolean printConfig;
```

修改 `run()` 方法，在开始时判断：

```java
@Override
public void run() {
    Path projectPath = Paths.get(projectRoot).toAbsolutePath().normalize();
    Location loc = globalInstall ? Location.GLOBAL : Location.LOCAL;

    List<AgentTarget> targets;
    try {
        targets = TargetRegistry.resolve(
                Arrays.asList(target.split(",")), loc);
    } catch (IllegalArgumentException e) {
        System.err.println("Error: " + e.getMessage());
        System.err.println("Known targets: claude, cursor, trae, all, auto");
        return;
    }

    if (printConfig) {
        // --print-config 模式：只打印状态，不安装
        System.out.println("CodeGraph Config Status");
        System.out.println("  Location: " + loc);
        System.out.println();

        for (AgentTarget t : targets) {
            if (!t.supportsLocation(loc)) {
                System.out.println("--- " + t.displayName() + " (unsupported for " + loc + ") ---");
                System.out.println();
                continue;
            }
            System.out.println("--- " + t.displayName() + " ---");
            List<ConfigPathInfo> paths = t.describePaths(loc, projectPath);
            for (ConfigPathInfo info : paths) {
                String status = info.configured ? "configured" : (info.exists ? "not configured" : "not found");
                System.out.printf("  [%s] %s  (%s)%n", status, info.path, info.description);
            }
            System.out.println();
        }
        return;
    }

    // ... 原有 install 逻辑 ...
}
```

---

## Core Logic Mapping

| codegraph (TS) | codegraph4j (Java) | 说明 |
|----------------|---------------------|------|
| `AgentTarget.describePaths(loc)` | `AgentTarget.describePaths(loc, projectRoot)` | 返回路径列表 |
| `AgentTarget.detect(loc)` | `JsonMCPTarget.describePaths()` 内联检测 | 判断已配置状态 |
| 无直接对应 | `ConfigPathInfo` (新增) | 路径 + 状态信息封装 |

---

## Assumptions & Decisions

1. **`--print-config` 放在 `install` 命令上**：作为一个 flag，与 install/uninstall 共享 target 选择和 location 参数，语义清晰
2. **不生成 jar 路径**：print-config 模式不需要 jarPath，跳过 `findJarPath()` 调用
3. **Trae 的 `describePaths()` 由 `JsonMCPTarget` 基类实现即可**：Trae 无额外文件，无需覆写
4. **Cursor 也无额外文件**：同样使用基类实现
5. **不实现 `printConfig()` 的内容打印**：用户需求是"路径和状态"，不是打印完整 MCP 配置内容

---

## Verification Steps

1. **编译**：`mvn compile`
2. **print-config 测试**：
   ```bash
   java -jar target/codegraph4j-1.0-SNAPSHOT.jar install --print-config --target all
   ```
   预期输出：每个 target 的 MCP 配置路径 + 当前状态
3. **安装后 print-config 测试**：
   ```bash
   java -jar target/codegraph4j-1.0-SNAPSHOT.jar install --target claude
   java -jar target/codegraph4j-1.0-SNAPSHOT.jar install --print-config --target claude
   ```
   预期：Claude 路径显示 `[configured]`
4. **卸载后 print-config 测试**：
   ```bash
   java -jar target/codegraph4j-1.0-SNAPSHOT.jar uninstall --target claude
   java -jar target/codegraph4j-1.0-SNAPSHOT.jar install --print-config --target claude
   ```
   预期：Claude 路径显示 `[not found]`（如果文件被删除）或 `[not configured]`
5. **Trae print-config**：确认仅显示 GLOBAL 路径，不显示 LOCAL
