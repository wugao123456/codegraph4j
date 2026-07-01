# Installer 模块实施计划

## Summary

为 codegraph4j 添加 `install` / `uninstall` 命令，参考 [codegraph installer](file:///Users/wugao-pc/Desktop/Project/codegraph/src/installer/) 的核心设计，将 AI 编码助手的 MCP 配置安装到对应 agent 的配置文件中。首批支持 **Claude Code**、**Cursor**、**Trae** 三个 target。

---

## Phase 1: Explore 结果

### codegraph4j 现状

| 项目 | 详情 |
|------|------|
| CLI 框架 | picocli 4.7.5，主入口 `CodeGraphCli.java` |
| 现有子命令 | `init`, `index`, `status`, `sync`, `serve`, `traverse` |
| install 命令 | **不存在** |
| MCP 服务 | `ServeCommand` 通过 `MCPServer` 启动 stdio JSON-RPC |
| JAR 查找 | `ServeCommand.findJarPath()` 在 `target/` 目录查找 `*.jar` |
| JSON 库 | Jackson 2.16.1（已在 pom.xml 依赖中） |
| 已有注入模式 | `GitHooksManager` 使用标记区块 (`# >>>` / `# <<<`) 注入配置到 git hooks |

### codegraph (TypeScript) installer 核心设计

| 组件 | 文件 | 职责 |
|------|------|------|
| `AgentTarget` 接口 | `targets/types.ts` | 定义 id/displayName/supportsLocation/detect/install/uninstall |
| 注册中心 | `targets/registry.ts` | `ALL_TARGETS` 数组，按 id 查找，`resolveTargetFlag()` |
| 公共工具 | `targets/shared.ts` | JSON 原子读写、标记区块 upsert/remove、MCP config 构建 |
| 安装编排 | `index.ts` | 交互式安装/卸载流程 |
| Claude Target | `targets/claude.ts` | `~/.claude.json` / `./.mcp.json` + permissions + CLAUDE.md |
| Cursor Target | `targets/cursor.ts` | `~/.cursor/mcp.json` / `./.cursor/mcp.json` + `--path` 参数 |

### Trae IDE 配置（已探明）

- **全局 MCP 路径**：`~/Library/Application Support/Trae CN/User/mcp.json`
- **格式**：标准 `mcpServers` JSON（与 Claude/Cursor 一致）
  ```json
  { "mcpServers": { "codegraph": { "command": "...", "args": [...] } } }
  ```
- **已存在的条目**：`codegraphy`（npx）、`MySQL`（uvx），均使用 `"disabled": true`
- **无额外配置**：不需要 settings.json permissions，不需要 instructions 文件

---

## Proposed Changes

### 新增文件清单

```
src/main/java/com/codegraph/install/
├── InstallCommand.java          # install 子命令（picocli）
├── UninstallCommand.java        # uninstall 子命令（picocli）
└── target/
    ├── AgentTarget.java         # Target 接口定义
    ├── Location.java            # GLOBAL / LOCAL 枚举
    ├── TargetId.java            # Target 标识枚举
    ├── FileAction.java          # 文件操作结果枚举 (CREATED/UPDATED/UNCHANGED/REMOVED/NOT_FOUND)
    ├── FileChange.java          # 文件变更数据类 (path + action)
    ├── WriteResult.java         # 安装结果 (files: List<FileChange> + notes: List<String>)
    ├── TargetRegistry.java      # Target 注册中心
    ├── Shared.java              # 公共工具（JSON 读写、标记区块操作）
    ├── JsonMCPTarget.java       # JSON 格式 MCP 配置的抽象基类
    ├── ClaudeTarget.java        # Claude Code target
    ├── CursorTarget.java        # Cursor target
    └── TraeTarget.java          # Trae IDE target（新增）
```

### 修改文件清单

- **`CodeGraphCli.java`**：在 `subcommands` 中注册 `InstallCommand.class`、`UninstallCommand.class`

---

### 文件详细设计

#### 1. `target/Location.java`

```java
package com.codegraph.install.target;

public enum Location {
    GLOBAL,
    LOCAL
}
```

**对标**：codegraph `types.ts` 的 `Location` 类型

---

#### 2. `target/TargetId.java`

```java
package com.codegraph.install.target;

public enum TargetId {
    CLAUDE("claude"),
    CURSOR("cursor"),
    TRAE("trae");

    private final String value;
    TargetId(String value) { this.value = value; }
    public String getValue() { return value; }

    public static TargetId fromString(String s) {
        for (TargetId id : values()) {
            if (id.value.equalsIgnoreCase(s)) return id;
        }
        throw new IllegalArgumentException("Unknown target: " + s);
    }
}
```

**对标**：codegraph `types.ts` 的 `TargetId` 联合类型

---

#### 3. `target/FileAction.java`

```java
package com.codegraph.install.target;

public enum FileAction {
    CREATED, UPDATED, UNCHANGED, REMOVED, NOT_FOUND
}
```

**对标**：codegraph `WriteResult.files[].action` 联合类型

---

#### 4. `target/FileChange.java`

```java
package com.codegraph.install.target;

public class FileChange {
    public final String path;
    public final FileAction action;

    public FileChange(String path, FileAction action) {
        this.path = path;
        this.action = action;
    }
}
```

---

#### 5. `target/WriteResult.java`

```java
package com.codegraph.install.target;

import java.util.ArrayList;
import java.util.List;

public class WriteResult {
    public final List<FileChange> files = new ArrayList<>();
    public final List<String> notes = new ArrayList<>();
}
```

**对标**：codegraph `types.ts` 的 `WriteResult` 接口

---

#### 6. `target/AgentTarget.java`

```java
package com.codegraph.install.target;

import java.nio.file.Path;

public interface AgentTarget {
    TargetId id();
    String displayName();
    boolean supportsLocation(Location loc);
    WriteResult install(Location loc, Path projectRoot, String jarPath);
    WriteResult uninstall(Location loc);
}
```

**对标**：codegraph `types.ts` 的 `AgentTarget` 接口

**简化说明**：
- 移除 `detect()`：picocli 命令行模式，用户通过 `--target` 显式指定，不自动检测
- 移除 `printConfig()`、`describePaths()`：第一阶段不实现 `--print-config`
- `install()` 新增 `projectRoot` 和 `jarPath` 参数：生成 MCP 配置需要

---

#### 7. `target/Shared.java`

```java
package com.codegraph.install.target;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.*;
import java.util.*;

public class Shared {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * 读取 JSON 文件，文件不存在或解析失败返回空 Map。
     * 对标 codegraph shared.ts readJsonFile()
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readJsonFile(Path filePath) {
        if (!Files.exists(filePath)) return new LinkedHashMap<>();
        try {
            return MAPPER.readValue(filePath.toFile(), LinkedHashMap.class);
        } catch (Exception e) {
            System.err.println("Warning: Could not parse " + filePath + ": " + e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /**
     * 原子写入 JSON 文件（先写 .tmp 再 rename）。
     * 对标 codegraph shared.ts writeJsonFile()
     */
    public static void writeJsonFile(Path filePath, Object data) throws Exception {
        Path parent = filePath.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmpPath = filePath.resolveSibling(filePath.getFileName() + ".tmp." + getPid());
        try {
            MAPPER.writeValue(tmpPath.toFile(), data);
            Files.move(tmpPath, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            try { Files.deleteIfExists(tmpPath); } catch (Exception ignored) {}
            throw e;
        }
    }

    /**
     * 构建标准 MCP 服务配置，所有 target 共用。
     * 对标 codegraph shared.ts getMcpServerConfig()
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> buildMcpServerConfig(String jarPath, Path projectRoot) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", "stdio");
        config.put("command", "java");
        List<String> args = new ArrayList<>();
        args.add("-jar");
        args.add(jarPath);
        args.add("serve");
        args.add("--mcp");
        args.add("-p");
        args.add(projectRoot.toAbsolutePath().toString());
        config.put("args", args);
        return config;
    }

    /**
     * 在标记区块中替换或追加内容。对标 codegraph shared.ts replaceOrAppendMarkedSection()
     */
    public static FileAction replaceOrAppendMarkedSection(
            Path filePath, String body, String startMarker, String endMarker) throws Exception {
        if (!Files.exists(filePath)) {
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, (body + "\n").getBytes());
            return FileAction.CREATED;
        }
        String content = new String(Files.readAllBytes(filePath));
        int startIdx = content.indexOf(startMarker);
        int endIdx = content.indexOf(endMarker);
        if (startIdx != -1 && endIdx > startIdx) {
            String existingBlock = content.substring(startIdx, endIdx + endMarker.length());
            if (existingBlock.equals(body)) return FileAction.UNCHANGED;
            String before = content.substring(0, startIdx);
            String after = content.substring(endIdx + endMarker.length());
            Files.write(filePath, (before + body + after).getBytes());
            return FileAction.UPDATED;
        }
        // 无标记 — 追加
        String trimmed = content.trim();
        String sep = trimmed.length() > 0 ? "\n\n" : "";
        Files.write(filePath, (trimmed + sep + body + "\n").getBytes());
        return FileAction.UPDATED; // appended = updated
    }

    /**
     * 移除标记区块。对标 codegraph shared.ts removeMarkedSection()
     */
    public static FileAction removeMarkedSection(Path filePath, String startMarker, String endMarker) throws Exception {
        if (!Files.exists(filePath)) return FileAction.NOT_FOUND;
        String content = new String(Files.readAllBytes(filePath));
        int startIdx = content.indexOf(startMarker);
        int endIdx = content.indexOf(endMarker);
        if (startIdx == -1 || endIdx <= startIdx) return FileAction.NOT_FOUND;
        String before = content.substring(0, startIdx).trim();
        String after = content.substring(endIdx + endMarker.length()).trim();
        String joined = before + (before.length() > 0 && after.length() > 0 ? "\n\n" : "") + after;
        if (joined.trim().isEmpty()) {
            Files.delete(filePath);
        } else {
            Files.write(filePath, (joined + "\n").getBytes());
        }
        return FileAction.REMOVED;
    }

    private static String getPid() {
        try {
            return java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        } catch (Exception e) {
            return "0";
        }
    }
}
```

**对标**：codegraph `shared.ts` 的 `getMcpServerConfig`、`readJsonFile`、`writeJsonFile`、`replaceOrAppendMarkedSection`、`removeMarkedSection`

---

#### 8. `target/JsonMCPTarget.java`

```java
package com.codegraph.install.target;

import java.nio.file.*;
import java.util.*;

/**
 * JSON 格式 MCP 配置目标的抽象基类。
 * Claude/Cursor/Trae 的配置格式相同（mcpServers JSON），共用此基类。
 */
public abstract class JsonMCPTarget implements AgentTarget {

    /** 获取全局 MCP 配置文件路径 */
    protected abstract Path getGlobalConfigPath();

    /** 获取项目本地 MCP 配置文件路径 */
    protected abstract Path getLocalConfigPath(Path projectRoot);

    /**
     * 构建 MCP 服务配置，子类可覆写以添加特殊参数。
     * Cursor 需要追加 --path 参数。
     */
    protected Map<String, Object> buildConfig(String jarPath, Path projectRoot) {
        return Shared.buildMcpServerConfig(jarPath, projectRoot);
    }

    @Override
    public WriteResult install(Location loc, Path projectRoot, String jarPath) {
        WriteResult result = new WriteResult();
        Path configPath = loc == Location.GLOBAL
                ? getGlobalConfigPath()
                : getLocalConfigPath(projectRoot);

        try {
            Map<String, Object> existing = Shared.readJsonFile(configPath);
            boolean fileExisted = Files.exists(configPath);

            @SuppressWarnings("unchecked")
            Map<String, Object> mcpServers = (Map<String, Object>) existing
                    .computeIfAbsent("mcpServers", k -> new LinkedHashMap<>());

            Map<String, Object> newConfig = buildConfig(jarPath, projectRoot);

            Object before = mcpServers.get("codegraph");
            if (deepEquals(before, newConfig)) {
                result.files.add(new FileChange(configPath.toString(), FileAction.UNCHANGED));
            } else {
                mcpServers.put("codegraph", newConfig);
                Shared.writeJsonFile(configPath, existing);
                FileAction action = (!fileExisted || before == null) ? FileAction.CREATED : FileAction.UPDATED;
                result.files.add(new FileChange(configPath.toString(), action));
            }
        } catch (Exception e) {
            result.files.add(new FileChange(configPath.toString(), FileAction.NOT_FOUND));
            result.notes.add("Error: " + e.getMessage());
        }

        return result;
    }

    @Override
    public WriteResult uninstall(Location loc) {
        WriteResult result = new WriteResult();
        Path configPath = getGlobalConfigPath(); // uninstall 默认全局

        try {
            Map<String, Object> existing = Shared.readJsonFile(configPath);
            if (!Files.exists(configPath)) {
                result.files.add(new FileChange(configPath.toString(), FileAction.NOT_FOUND));
                return result;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> mcpServers = (Map<String, Object>) existing.get("mcpServers");
            if (mcpServers == null || !mcpServers.containsKey("codegraph")) {
                result.files.add(new FileChange(configPath.toString(), FileAction.NOT_FOUND));
                return result;
            }

            mcpServers.remove("codegraph");
            if (mcpServers.isEmpty()) {
                existing.remove("mcpServers");
            }
            Shared.writeJsonFile(configPath, existing);
            result.files.add(new FileChange(configPath.toString(), FileAction.REMOVED));
        } catch (Exception e) {
            result.files.add(new FileChange(configPath.toString(), FileAction.NOT_FOUND));
            result.notes.add("Error: " + e.getMessage());
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private static boolean deepEquals(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Map && b instanceof Map) {
            Map<String, Object> ma = (Map<String, Object>) a;
            Map<String, Object> mb = (Map<String, Object>) b;
            if (ma.size() != mb.size()) return false;
            for (Map.Entry<String, Object> e : ma.entrySet()) {
                if (!mb.containsKey(e.getKey())) return false;
                if (!deepEquals(e.getValue(), mb.get(e.getKey()))) return false;
            }
            return true;
        }
        if (a instanceof List && b instanceof List) {
            List<Object> la = (List<Object>) a;
            List<Object> lb = (List<Object>) b;
            if (la.size() != lb.size()) return false;
            for (int i = 0; i < la.size(); i++) {
                if (!deepEquals(la.get(i), lb.get(i))) return false;
            }
            return true;
        }
        return a.equals(b);
    }
}
```

**对标**：codegraph Claude/Cursor Target 的 `writeMcpEntry()` 逻辑

---

#### 9. `target/ClaudeTarget.java`

```java
package com.codegraph.install.target;

import java.nio.file.*;

public class ClaudeTarget extends JsonMCPTarget {

    private static final String INSTRUCTIONS_START = "<!-- CODEGRAPH_START -->";
    private static final String INSTRUCTIONS_END = "<!-- CODEGRAPH_END -->";
    private static final String INSTRUCTIONS_BLOCK = INSTRUCTIONS_START + "\n"
            + "## CodeGraph\n\n"
            + "In repositories indexed by CodeGraph (a `.codegraph/` directory exists at the repo root), "
            + "reach for it BEFORE grep/find or reading files when you need to understand or locate code.\n\n"
            + "- **MCP tool**: `codegraph_explore` answers most code questions in one call.\n"
            + "- **Shell**: `codegraph explore \"<query>\"` prints the same output.\n\n"
            + "If there is no `.codegraph/` directory, skip CodeGraph entirely.\n"
            + INSTRUCTIONS_END;

    @Override
    public TargetId id() { return TargetId.CLAUDE; }

    @Override
    public String displayName() { return "Claude Code"; }

    @Override
    public boolean supportsLocation(Location loc) { return true; }

    @Override
    protected Path getGlobalConfigPath() {
        return Paths.get(System.getProperty("user.home"), ".claude.json");
    }

    @Override
    protected Path getLocalConfigPath(Path projectRoot) {
        return projectRoot.resolve(".mcp.json");
    }

    @Override
    public WriteResult install(Location loc, Path projectRoot, String jarPath) {
        WriteResult result = super.install(loc, projectRoot, jarPath);

        // 额外写入 CLAUDE.md instructions
        Path instructionsPath = loc == Location.GLOBAL
                ? Paths.get(System.getProperty("user.home"), ".claude", "CLAUDE.md")
                : projectRoot.resolve(".claude").resolve("CLAUDE.md");

        try {
            FileAction action = Shared.replaceOrAppendMarkedSection(
                    instructionsPath, INSTRUCTIONS_BLOCK, INSTRUCTIONS_START, INSTRUCTIONS_END);
            result.files.add(new FileChange(instructionsPath.toString(), action));
        } catch (Exception e) {
            result.files.add(new FileChange(instructionsPath.toString(), FileAction.NOT_FOUND));
        }

        return result;
    }

    @Override
    public WriteResult uninstall(Location loc) {
        WriteResult result = super.uninstall(loc);

        // 额外移除 CLAUDE.md instructions
        Path instructionsPath = Paths.get(System.getProperty("user.home"), ".claude", "CLAUDE.md");
        try {
            FileAction action = Shared.removeMarkedSection(
                    instructionsPath, INSTRUCTIONS_START, INSTRUCTIONS_END);
            result.files.add(new FileChange(instructionsPath.toString(), action));
        } catch (Exception e) {
            result.files.add(new FileChange(instructionsPath.toString(), FileAction.NOT_FOUND));
        }

        return result;
    }
}
```

**对标**：codegraph `claude.ts`，简化版：跳过 permissions、prompt-hook、legacy 迁移

---

#### 10. `target/CursorTarget.java`

```java
package com.codegraph.install.target;

import java.nio.file.*;
import java.util.*;

public class CursorTarget extends JsonMCPTarget {

    @Override
    public TargetId id() { return TargetId.CURSOR; }

    @Override
    public String displayName() { return "Cursor"; }

    @Override
    public boolean supportsLocation(Location loc) { return true; }

    @Override
    protected Path getGlobalConfigPath() {
        return Paths.get(System.getProperty("user.home"), ".cursor", "mcp.json");
    }

    @Override
    protected Path getLocalConfigPath(Path projectRoot) {
        return projectRoot.resolve(".cursor").resolve("mcp.json");
    }

    @Override
    protected Map<String, Object> buildConfig(String jarPath, Path projectRoot) {
        Map<String, Object> config = Shared.buildMcpServerConfig(jarPath, projectRoot);
        // Cursor 需要 --project 参数替代 --path
        // 本地：使用绝对路径；全局：使用 ${workspaceFolder} 宏
        @SuppressWarnings("unchecked")
        List<String> args = (List<String>) config.get("args");
        // 替换 -p <path> 为适合 Cursor 的方式
        // 对标 codegraph cursor.ts 的 buildCursorMcpConfig
        return config;
    }

    @Override
    public WriteResult install(Location loc, Path projectRoot, String jarPath) {
        WriteResult result = super.install(loc, projectRoot, jarPath);
        result.notes.add("Restart Cursor for MCP changes to take effect.");
        return result;
    }
}
```

**对标**：codegraph `cursor.ts`

---

#### 11. `target/TraeTarget.java`（**新增核心**）

```java
package com.codegraph.install.target;

import java.nio.file.*;

public class TraeTarget extends JsonMCPTarget {

    private static final String TRAE_CONFIG_DIR =
            System.getProperty("user.home") + "/Library/Application Support/Trae CN/User";

    @Override
    public TargetId id() { return TargetId.TRAE; }

    @Override
    public String displayName() { return "Trae"; }

    @Override
    public boolean supportsLocation(Location loc) {
        // Trae 当前仅支持全局配置
        return loc == Location.GLOBAL;
    }

    @Override
    protected Path getGlobalConfigPath() {
        return Paths.get(TRAE_CONFIG_DIR, "mcp.json");
    }

    @Override
    protected Path getLocalConfigPath(Path projectRoot) {
        return projectRoot.resolve(".trae").resolve("mcp.json");
    }

    @Override
    public WriteResult install(Location loc, Path projectRoot, String jarPath) {
        WriteResult result = super.install(loc, projectRoot, jarPath);
        result.notes.add("Restart Trae for MCP changes to take effect.");
        return result;
    }
}
```

**说明**：Trae 的 MCP 配置与 Claude/Cursor 格式一致，直接继承 `JsonMCPTarget`。无需额外配置 permissions 或 instructions。

---

#### 12. `target/TargetRegistry.java`

```java
package com.codegraph.install.target;

import java.util.*;

public class TargetRegistry {

    private static final List<AgentTarget> ALL_TARGETS = Collections.unmodifiableList(Arrays.asList(
            new ClaudeTarget(),
            new CursorTarget(),
            new TraeTarget()
    ));

    /** 所有已注册的 target */
    public static List<AgentTarget> getAllTargets() {
        return ALL_TARGETS;
    }

    /** 按 id 查找 target */
    public static AgentTarget get(TargetId id) {
        for (AgentTarget t : ALL_TARGETS) {
            if (t.id() == id) return t;
        }
        return null;
    }

    /** 解析 --target 参数，返回对应的 target 列表 */
    public static List<AgentTarget> resolve(List<String> names, Location loc) {
        List<AgentTarget> result = new ArrayList<>();
        for (String name : names) {
            if ("all".equalsIgnoreCase(name)) return new ArrayList<>(ALL_TARGETS);
            if ("auto".equalsIgnoreCase(name)) return new ArrayList<>(ALL_TARGETS);
            TargetId id = TargetId.fromString(name);
            AgentTarget t = get(id);
            if (t == null) {
                throw new IllegalArgumentException("Unknown target: " + name);
            }
            if (t.supportsLocation(loc)) {
                result.add(t);
            }
        }
        return result;
    }
}
```

**对标**：codegraph `registry.ts`

---

#### 13. `InstallCommand.java`

```java
package com.codegraph.install.command;

import com.codegraph.install.target.*;
import picocli.CommandLine;

import java.nio.file.*;
import java.util.*;

@CommandLine.Command(name = "install", description = "Install CodeGraph MCP server config for AI assistants")
public class InstallCommand implements Runnable {

    @CommandLine.Option(names = {"--target"}, defaultValue = "all",
            description = "Target agents (comma-separated): claude, cursor, trae, all, auto")
    private String target;

    @CommandLine.Option(names = {"--global"}, defaultValue = "false",
            description = "Install globally (user-wide config)")
    private boolean globalInstall;

    @CommandLine.Option(names = {"-p", "--project"}, defaultValue = ".",
            description = "Project root directory")
    private String projectRoot;

    @Override
    public void run() {
        Path projectPath = Paths.get(projectRoot).toAbsolutePath().normalize();
        Location loc = globalInstall ? Location.GLOBAL : Location.LOCAL;
        String jarPath = findJarPath();

        System.out.println("CodeGraph Installer");
        System.out.println("  Location: " + loc);
        System.out.println("  Targets:  " + target);
        System.out.println("  JAR:      " + jarPath);
        System.out.println();

        List<AgentTarget> targets = TargetRegistry.resolve(
                Arrays.asList(target.split(",")), loc);

        for (AgentTarget t : targets) {
            System.out.println("--- " + t.displayName() + " ---");
            WriteResult result = t.install(loc, projectPath, jarPath);
            for (FileChange fc : result.files) {
                System.out.printf("  [%s] %s%n", fc.action, fc.path);
            }
            for (String note : result.notes) {
                System.out.println("  Note: " + note);
            }
        }

        System.out.println("\nDone! Restart your IDE/agent to use CodeGraph.");
    }

    /** 复用 ServeCommand 的 jar 查找逻辑 */
    public static String findJarPath() {
        String userDir = System.getProperty("user.dir");
        Path targetDir = Paths.get(userDir, "target");
        if (Files.isDirectory(targetDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(targetDir, "*.jar")) {
                for (Path jar : stream) {
                    String name = jar.getFileName().toString();
                    if (!name.contains("sources") && !name.contains("javadoc")) {
                        return jar.toAbsolutePath().toString();
                    }
                }
            } catch (Exception ignored) {}
        }
        return "codegraph4j.jar";
    }
}
```

**对标**：codegraph `index.ts` 的 `runInstallerWithOptions()`，简化为 picocli 命令行模式

---

#### 14. `UninstallCommand.java`

```java
package com.codegraph.install.command;

import com.codegraph.install.target.*;
import picocli.CommandLine;

import java.util.*;

@CommandLine.Command(name = "uninstall", description = "Uninstall CodeGraph MCP config from AI assistants")
public class UninstallCommand implements Runnable {

    @CommandLine.Option(names = {"--target"}, defaultValue = "all",
            description = "Target agents (comma-separated)")
    private String target;

    @Override
    public void run() {
        Location loc = Location.GLOBAL;
        List<AgentTarget> targets = TargetRegistry.resolve(
                Arrays.asList(target.split(",")), loc);

        for (AgentTarget t : targets) {
            System.out.println("--- " + t.displayName() + " ---");
            WriteResult result = t.uninstall(loc);
            for (FileChange fc : result.files) {
                System.out.printf("  [%s] %s%n", fc.action, fc.path);
            }
        }

        System.out.println("\nDone!");
    }
}
```

**对标**：codegraph `index.ts` 的 `runUninstaller()`

---

#### 15. 修改 `CodeGraphCli.java`

在 `subcommands` 数组中新增两个命令：
```java
subcommands = {
    InitCommand.class,
    IndexCommand.class,
    StatusCommand.class,
    SyncCommand.class,
    ServeCommand.class,
    TraverseCommand.class,
    InstallCommand.class,    // 新增
    UninstallCommand.class   // 新增
}
```

---

## Core Logic Mapping

| codegraph (TS) | codegraph4j (Java) | 说明 |
|----------------|---------------------|------|
| `targets/types.ts` → `AgentTarget`, `Location`, `WriteResult` | `AgentTarget.java`, `Location.java`, `WriteResult.java`, `FileChange.java`, `FileAction.java` | 接口/类型定义 |
| `targets/registry.ts` → `ALL_TARGETS`, `getTarget()`, `resolveTargetFlag()` | `TargetRegistry.java` | Target 注册与查找 |
| `targets/shared.ts` → `getMcpServerConfig()`, `readJsonFile()`, `writeJsonFile()` | `Shared.java` | 公共工具 |
| `targets/claude.ts` → `writeMcpEntry()`, `upsertInstructionsEntry()` | `ClaudeTarget.java` (extends `JsonMCPTarget`) | Claude 安装 |
| `targets/cursor.ts` → `writeMcpEntry()`, `buildCursorMcpConfig()` | `CursorTarget.java` (extends `JsonMCPTarget`) | Cursor 安装 |
| （无） | `TraeTarget.java` (extends `JsonMCPTarget`) | **Trae 安装（新增）** |
| `index.ts` → `runInstallerWithOptions()` | `InstallCommand.java` | 安装编排 |
| `index.ts` → `runUninstaller()` | `UninstallCommand.java` | 卸载编排 |

### 关键简化

| 方面 | codegraph | codegraph4j | 理由 |
|------|-----------|-------------|------|
| 交互式 UI | `@clack/prompts` 多选/确认 | picocli `--target` 参数 | Java 生态，picocli 已接入 |
| 自动检测 | `detect()` 探测已安装 agent | 跳过 | picocli 非交互，用户显式指定 |
| 遥测 | `getTelemetry().recordLifecycle()` | 跳过 | project scope 外 |
| npm 全局安装 | `npm install -g` CLI | 跳过 | JAR 直接运行 |
| permissions | `settings.json` autoAllow | 跳过 | 第一阶段不实现 |
| prompt-hook | Claude `UserPromptSubmit` | 跳过 | 第一阶段不实现 |
| legacy 迁移 | 清理旧版 `.claude.json` 等 | 跳过 | codegraph4j 首次实现，无历史遗留 |

---

## Assumptions & Decisions

1. **Jacoco pom.xml 依赖**：`com.fasterxml.jackson.core:jackson-databind:2.16.1` 已在 pom.xml 中，无需新增 JSON 库
2. **Trae 仅支持全局安装**：Trae MCP 配置位于 `~/Library/Application Support/Trae CN/User/mcp.json`，暂无项目级 `.trae/mcp.json`
3. **jar 路径**：安装时写入绝对路径 `java -jar <absolute-path>.jar serve --mcp -p <project>`
4. **不创建目录**：如果父目录不存在，`Files.createDirectories()` 会自动创建
5. **命令包路径**：`install` 和 `uninstall` 命令放在 `com.codegraph.install` 包（与现有 `com.codegraph.cli.commands` 包同级），参考 `ServeCommand` 在 `com.codegraph.cli.commands` 的模式
6. **JDK 8 兼容**：不使用 `List.of()`（Java 9+），改用 `Arrays.asList()` + `Collections.unmodifiableList()`

---

## Verification Steps

1. **编译**：`mvn compile`
2. **安装测试**：
   ```bash
   mvn package -DskipTests
   java -jar target/codegraph4j.jar install --target claude,cursor,trae
   ```
   验证各配置文件写入正确的 MCP 配置
3. **卸载测试**：
   ```bash
   java -jar target/codegraph4j.jar uninstall --target claude,cursor,trae
   ```
   验证 MCP 配置已移除，其他内容保留
4. **幂等性测试**：重复运行 `install`，确认输出 `[UNCHANGED]`
5. **Trae 测试**：验证 `~/Library/Application Support/Trae CN/User/mcp.json` 被正确修改，已有条目如 `codegraphy`、`MySQL` 保持不变
