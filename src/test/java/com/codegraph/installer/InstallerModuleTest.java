package com.codegraph.installer;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.codegraph.installer.target.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * Installer 模块单元测试 — 覆盖 install/uninstall/幂等性/print-config。
 *
 * 通过临时 user.home 隔离，不污染真实配置文件。
 *
 * 测试范围：
 * - Claude、Cursor、Trae 三个 target 的安装与卸载
 * - 幂等性：重复 install → UNCHANGED
 * - 新 key 名 "codegraph4j"
 * - 已有其他条目不受影响
 * - print-config 返回正确状态
 * - 标记区块注入与移除（CLAUDE.md、rules）
 */
public class InstallerModuleTest {

    private static final String ORIG_HOME = System.getProperty("user.home");
    private static Path tempHome;

    @BeforeClass
    public static void setUp() throws Exception {
        tempHome = Files.createTempDirectory("codegraph4j-test-home");
        System.setProperty("user.home", tempHome.toString());
    }

    @Before
    public void cleanHome() throws IOException {
        // 清理 tempHome 保证各测试独立
        if (Files.exists(tempHome)) {
            Files.walkFileTree(tempHome, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            Files.createDirectories(tempHome);
        }
    }

    @AfterClass
    public static void tearDown() {
        System.setProperty("user.home", ORIG_HOME);
    }

    /* ==================== Claude ==================== */

    @Test
    public void testClaudeGlobalInstallCreatesConfigAndInstructions() throws Exception {
        ClaudeTarget claude = new ClaudeTarget();
        Path project = Files.createTempDirectory("claude-project");
        String jarPath = "/fake/codegraph4j.jar";

        WriteResult r = claude.install(Location.GLOBAL, project, jarPath);

        // 验证返回结果
        assertEquals("Claude Code", claude.displayName());
        assertEquals(TargetId.CLAUDE, claude.id());
        assertTrue(claude.supportsLocation(Location.GLOBAL));
        assertTrue(claude.supportsLocation(Location.LOCAL));

        List<FileChange> files = r.files;
        assertEquals(2, files.size());

        // MCP config
        FileChange mcp = findChange(files, ".claude.json");
        assertNotNull(mcp);
        assertEquals(FileAction.CREATED, mcp.action);

        // 验证 JSON 内容：key 为 "codegraph4j"
        Path configPath = tempHome.resolve(".claude.json");
        assertTrue(Files.exists(configPath));
        Map<String, Object> json = Shared.readJsonFile(configPath);
        @SuppressWarnings("unchecked")
        Map<String, Object> servers = (Map<String, Object>) json.get("mcpServers");
        assertNotNull("mcpServers should exist", servers);
        assertTrue("should contain codegraph4j key", servers.containsKey("codegraph4j"));
        assertFalse("should NOT contain old 'codegraph' key", servers.containsKey("codegraph"));
        @SuppressWarnings("unchecked")
        Map<String, Object> cg = (Map<String, Object>) servers.get("codegraph4j");
        assertEquals("stdio", cg.get("type"));
        assertEquals("java", cg.get("command"));

        // CLAUDE.md
        FileChange instructions = findChange(files, "CLAUDE.md");
        assertNotNull(instructions);
        assertEquals(FileAction.CREATED, instructions.action);
        Path mdPath = tempHome.resolve(".claude").resolve("CLAUDE.md");
        assertTrue(Files.exists(mdPath));
        String md = new String(Files.readAllBytes(mdPath));
        assertTrue(md.contains("<!-- CODEGRAPH_START -->"));
        assertTrue(md.contains("## CodeGraph4J"));
    }

    @Test
    public void testClaudeGlobalInstallIdempotent() throws Exception {
        ClaudeTarget claude = new ClaudeTarget();
        Path project = Files.createTempDirectory("claude-idem-project");
        String jarPath = "/fake/codegraph4j.jar";

        // 手动创建已有配置（模拟已安装状态）
        Map<String, Object> existing = new LinkedHashMap<>();
        Map<String, Object> servers = new LinkedHashMap<>();
        servers.put("codegraph4j", Shared.buildMcpServerConfig(jarPath));
        existing.put("mcpServers", servers);
        Shared.writeJsonFile(tempHome.resolve(".claude.json"), existing);

        // 创建已有 CLAUDE.md
        Path mdPath = tempHome.resolve(".claude").resolve("CLAUDE.md");
        Files.createDirectories(mdPath.getParent());
        Files.write(mdPath, "old content\n".getBytes(StandardCharsets.UTF_8));

        WriteResult r1 = claude.install(Location.GLOBAL, project, jarPath);

        FileChange mcp1 = findChange(r1.files, ".claude.json");
        assertEquals(FileAction.UNCHANGED, mcp1.action);

        FileChange md1 = findChange(r1.files, "CLAUDE.md");
        // 已有内容无标记，追加 block
        assertEquals(FileAction.UPDATED, md1.action);

        // 再次 install
        WriteResult r2 = claude.install(Location.GLOBAL, project, jarPath);

        FileChange mcp2 = findChange(r2.files, ".claude.json");
        assertEquals(FileAction.UNCHANGED, mcp2.action);

        FileChange md2 = findChange(r2.files, "CLAUDE.md");
        assertEquals(FileAction.UNCHANGED, md2.action);
    }

    @Test
    public void testClaudeUninstallRemovesConfigAndInstructions() throws Exception {
        ClaudeTarget claude = new ClaudeTarget();
        String jarPath = "/fake/codegraph4j.jar";

        // 先安装
        claude.install(Location.GLOBAL, Files.createTempDirectory("clu-uninstall"), jarPath);

        // 卸载
        WriteResult r = claude.uninstall(Location.GLOBAL);
        List<FileChange> files = r.files;

        FileChange mcp = findChange(files, ".claude.json");
        assertNotNull(mcp);
        assertEquals(FileAction.REMOVED, mcp.action);

        FileChange md = findChange(files, "CLAUDE.md");
        assertNotNull(md);
        assertTrue(md.action == FileAction.REMOVED || md.action == FileAction.NOT_FOUND);

        // 验证配置文件已无 codegraph4j
        if (Files.exists(tempHome.resolve(".claude.json"))) {
            Map<String, Object> json = Shared.readJsonFile(tempHome.resolve(".claude.json"));
            @SuppressWarnings("unchecked")
            Map<String, Object> servers = (Map<String, Object>) json.get("mcpServers");
            assertTrue(servers == null || !servers.containsKey("codegraph4j"));
        }
    }

    @Test
    public void testClaudeUninstallWhenNotInstalled() throws Exception {
        ClaudeTarget claude = new ClaudeTarget();
        WriteResult r = claude.uninstall(Location.GLOBAL);

        FileChange mcp = findChange(r.files, ".claude.json");
        assertNotNull(mcp);
        assertEquals(FileAction.NOT_FOUND, mcp.action);
    }

    @Test
    public void testClaudePreservesExistingEntries() throws Exception {
        ClaudeTarget claude = new ClaudeTarget();
        Path project = Files.createTempDirectory("claude-preserve");
        String jarPath = "/fake/codegraph4j.jar";

        // 预置其他 MCP 条目
        Map<String, Object> existing = new LinkedHashMap<>();
        Map<String, Object> servers = new LinkedHashMap<>();
        Map<String, Object> other = new LinkedHashMap<>();
        other.put("command", "node");
        other.put("args", Arrays.asList("other.js"));
        servers.put("other-server", other);
        existing.put("mcpServers", servers);
        Shared.writeJsonFile(tempHome.resolve(".claude.json"), existing);

        // 安装 codegraph4j
        claude.install(Location.GLOBAL, project, jarPath);

        // 验证原有条目保留
        Map<String, Object> json = Shared.readJsonFile(tempHome.resolve(".claude.json"));
        @SuppressWarnings("unchecked")
        Map<String, Object> allServers = (Map<String, Object>) json.get("mcpServers");
        assertTrue(allServers.containsKey("other-server"));
        assertTrue(allServers.containsKey("codegraph4j"));
    }

    /* ==================== Cursor ==================== */

    @Test
    public void testCursorGlobalInstall() throws Exception {
        CursorTarget cursor = new CursorTarget();
        Path project = Files.createTempDirectory("cursor-project");
        String jarPath = "/fake/codegraph4j.jar";

        WriteResult r = cursor.install(Location.GLOBAL, project, jarPath);

        assertEquals("Cursor", cursor.displayName());
        assertEquals(TargetId.CURSOR, cursor.id());

        FileChange mcp = findChange(r.files, "mcp.json");
        assertNotNull(mcp);
        assertEquals(FileAction.CREATED, mcp.action);

        // 验证内容
        Path configPath = tempHome.resolve(".cursor").resolve("mcp.json");
        assertTrue(Files.exists(configPath));
        Map<String, Object> json = Shared.readJsonFile(configPath);
        @SuppressWarnings("unchecked")
        Map<String, Object> servers = (Map<String, Object>) json.get("mcpServers");
        @SuppressWarnings("unchecked")
        Map<String, Object> cg = (Map<String, Object>) servers.get("codegraph4j");
        assertNotNull(cg);

        // Cursor 全局用 ${workspaceFolder}
        @SuppressWarnings("unchecked")
        List<String> args = (List<String>) cg.get("args");
        assertTrue(args.contains("${workspaceFolder}"));

        // 幂等性
        WriteResult r2 = cursor.install(Location.GLOBAL, project, jarPath);
        assertEquals(FileAction.UNCHANGED, findChange(r2.files, "mcp.json").action);
    }

    @Test
    public void testCursorLocalInstall() throws Exception {
        CursorTarget cursor = new CursorTarget();
        Path project = Files.createTempDirectory("cursor-local");
        String jarPath = "/fake/codegraph4j.jar";

        WriteResult r = cursor.install(Location.LOCAL, project, jarPath);
        FileChange mcp = findChange(r.files, "mcp.json");
        assertEquals(FileAction.CREATED, mcp.action);

        // 本地用绝对路径
        Path configPath = project.resolve(".cursor").resolve("mcp.json");
        Map<String, Object> json = Shared.readJsonFile(configPath);
        @SuppressWarnings("unchecked")
        Map<String, Object> servers = (Map<String, Object>) json.get("mcpServers");
        @SuppressWarnings("unchecked")
        Map<String, Object> cg = (Map<String, Object>) servers.get("codegraph4j");
        @SuppressWarnings("unchecked")
        List<String> args = (List<String>) cg.get("args");
        assertTrue(args.contains(project.toAbsolutePath().toString()));
    }

    /* ==================== Trae ==================== */

    @Test
    public void testTraeGlobalInstallCreatesConfigAndRules() throws Exception {
        TraeTarget trae = new TraeTarget();
        Path project = Files.createTempDirectory("trae-project");
        String jarPath = "/fake/codegraph4j.jar";

        WriteResult r = trae.install(Location.GLOBAL, project, jarPath);

        assertEquals("Trae", trae.displayName());
        assertTrue(trae.supportsLocation(Location.GLOBAL));
        assertFalse(trae.supportsLocation(Location.LOCAL));

        // MCP config
        FileChange mcp = findChange(r.files, "mcp.json");
        assertNotNull(mcp);
        assertEquals(FileAction.CREATED, mcp.action);

        // 验证 Trae 特定路径
        Path traeConfig = Paths.get(tempHome.toString(),
                "Library/Application Support/Trae CN/User/mcp.json");
        assertTrue(Files.exists(traeConfig));
        Map<String, Object> json = Shared.readJsonFile(traeConfig);
        @SuppressWarnings("unchecked")
        Map<String, Object> servers = (Map<String, Object>) json.get("mcpServers");
        assertTrue(servers.containsKey("codegraph4j"));

        // Rules 文件
        FileChange rules = findChange(r.files, "codegraph.md");
        assertNotNull(rules);
        assertEquals(FileAction.CREATED, rules.action);
        Path rulesPath = project.resolve(".trae").resolve("rules").resolve("codegraph.md");
        assertTrue(Files.exists(rulesPath));
        String rulesContent = new String(Files.readAllBytes(rulesPath));
        assertTrue(rulesContent.contains("<!-- CODEGRAPH_START -->"));

        // 幂等性
        WriteResult r2 = trae.install(Location.GLOBAL, project, jarPath);
        assertEquals(FileAction.UNCHANGED, findChange(r2.files, "mcp.json").action);
        assertEquals(FileAction.UNCHANGED, findChange(r2.files, "codegraph.md").action);
    }

    @Test
    public void testTraeUninstall() throws Exception {
        TraeTarget trae = new TraeTarget();
        Path project = Files.createTempDirectory("trae-uninstall");
        String jarPath = "/fake/codegraph4j.jar";

        // 手动构建 Trae 配置目录
        Path traeConfigDir = Paths.get(tempHome.toString(),
                "Library/Application Support/Trae CN/User");
        Files.createDirectories(traeConfigDir);
        Map<String, Object> existing = new LinkedHashMap<>();
        Map<String, Object> servers = new LinkedHashMap<>();
        servers.put("codegraph4j", Shared.buildMcpServerConfig(jarPath));
        servers.put("MySQL", new LinkedHashMap<>()); // 其他条目
        existing.put("mcpServers", servers);
        Shared.writeJsonFile(traeConfigDir.resolve("mcp.json"), existing);

        WriteResult r = trae.uninstall(Location.GLOBAL);

        FileChange mcp = findChange(r.files, "mcp.json");
        assertEquals(FileAction.REMOVED, mcp.action);

        // 验证 codegraph4j 已移除，MySQL 保留
        Map<String, Object> json = Shared.readJsonFile(traeConfigDir.resolve("mcp.json"));
        @SuppressWarnings("unchecked")
        Map<String, Object> allServers = (Map<String, Object>) json.get("mcpServers");
        assertFalse(allServers.containsKey("codegraph4j"));
        assertTrue(allServers.containsKey("MySQL"));
    }

    /* ==================== describePaths / print-config ==================== */

    @Test
    public void testDescribePathsForAllTargets() throws Exception {
        Path project = Files.createTempDirectory("describe-project");
        String jarPath = "/fake/codegraph4j.jar";

        // 先全部安装
        new ClaudeTarget().install(Location.GLOBAL, project, jarPath);
        new CursorTarget().install(Location.GLOBAL, project, jarPath);
        new TraeTarget().install(Location.GLOBAL, project, jarPath);

        // Claude describePaths
        List<ConfigPathInfo> claudePaths = new ClaudeTarget().describePaths(Location.GLOBAL, project);
        long claudeConfigured = claudePaths.stream().filter(p -> p.configured).count();
        assertTrue("Claude should have configured paths", claudeConfigured >= 2);

        // Cursor describePaths
        List<ConfigPathInfo> cursorPaths = new CursorTarget().describePaths(Location.GLOBAL, project);
        long cursorConfigured = cursorPaths.stream().filter(p -> p.configured).count();
        assertEquals(1, cursorConfigured);

        // Trae describePaths
        List<ConfigPathInfo> traePaths = new TraeTarget().describePaths(Location.GLOBAL, project);
        long traeConfigured = traePaths.stream().filter(p -> p.configured).count();
        assertEquals(2, traeConfigured); // MCP config + Rules
    }

    /* ==================== TargetRegistry ==================== */

    @Test
    public void testTargetRegistryResolveAll() {
        List<AgentTarget> all = TargetRegistry.resolve(
                Collections.singletonList("all"), Location.GLOBAL);
        assertEquals(3, all.size());
    }

    @Test
    public void testTargetRegistryResolveSpecific() {
        List<AgentTarget> list = TargetRegistry.resolve(
                Arrays.asList("claude", "cursor"), Location.GLOBAL);
        assertEquals(2, list.size());
        assertEquals(TargetId.CLAUDE, list.get(0).id());
        assertEquals(TargetId.CURSOR, list.get(1).id());
    }

    @Test
    public void testTargetRegistryResolveCaseInsensitive() {
        List<AgentTarget> list = TargetRegistry.resolve(
                Collections.singletonList("CLAuDE"), Location.GLOBAL);
        assertEquals(1, list.size());
        assertEquals(TargetId.CLAUDE, list.get(0).id());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testTargetRegistryResolveUnknown() {
        TargetRegistry.resolve(
                Collections.singletonList("unknown-ide"), Location.GLOBAL);
    }

    @Test
    public void testTargetRegistryAutoResolvesAll() {
        List<AgentTarget> list = TargetRegistry.resolve(
                Collections.singletonList("auto"), Location.GLOBAL);
        assertEquals(3, list.size());
    }

    /* ==================== Shared utilities ==================== */

    @Test
    public void testSharedReadNonExistent() {
        Path p = tempHome.resolve("nonexistent.json");
        Map<String, Object> result = Shared.readJsonFile(p);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testSharedWriteAndRead() throws Exception {
        Path p = tempHome.resolve("test-write.json");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", "value");
        Shared.writeJsonFile(p, data);

        Map<String, Object> read = Shared.readJsonFile(p);
        assertEquals("value", read.get("key"));
    }

    @Test
    public void testSharedReplaceOrAppendMarkedSectionCreatesFile() throws Exception {
        Path p = tempHome.resolve("new-marked.md");
        FileAction action = Shared.replaceOrAppendMarkedSection(
                p, "<!-- S -->\nhello\n<!-- E -->", "<!-- S -->", "<!-- E -->");
        assertEquals(FileAction.CREATED, action);
        String content = new String(Files.readAllBytes(p));
        assertTrue(content.contains("hello"));
    }

    @Test
    public void testSharedReplaceOrAppendMarkedSectionUpdatesBlock() throws Exception {
        Path p = tempHome.resolve("update-marked.md");
        Files.write(p, "old\n<!-- S -->\nv1\n<!-- E -->\nmore".getBytes(StandardCharsets.UTF_8));
        FileAction action = Shared.replaceOrAppendMarkedSection(
                p, "<!-- S -->\nv2\n<!-- E -->", "<!-- S -->", "<!-- E -->");
        assertEquals(FileAction.UPDATED, action);
        String content = new String(Files.readAllBytes(p));
        assertTrue(content.contains("v2"));
        assertFalse(content.contains("v1"));
    }

    @Test
    public void testSharedRemoveMarkedSection() throws Exception {
        Path p = tempHome.resolve("remove-marked.md");
        Files.write(p, "keep\n<!-- S -->\nblock\n<!-- E -->\nkeep2".getBytes(StandardCharsets.UTF_8));
        FileAction action = Shared.removeMarkedSection(p, "<!-- S -->", "<!-- E -->");
        assertEquals(FileAction.REMOVED, action);
        String content = new String(Files.readAllBytes(p));
        assertTrue(content.contains("keep"));
        assertTrue(content.contains("keep2"));
        assertFalse(content.contains("block"));
    }

    /* ==================== deepEquals ==================== */

    @Test
    public void testDeepEqualsMaps() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", "stdio");
        a.put("command", "java");

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("command", "java");
        b.put("type", "stdio");

        assertTrue(JsonMCPTarget.deepEquals(a, b));
    }

    @Test
    public void testDeepEqualsMapsNotEqual() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", "stdio");
        a.put("command", "java");

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", "sse");
        b.put("command", "java");

        assertFalse(JsonMCPTarget.deepEquals(a, b));
    }

    @Test
    public void testDeepEqualsLists() {
        List<String> a = Arrays.asList("-jar", "a.jar", "serve");
        List<String> b = Arrays.asList("-jar", "a.jar", "serve");
        assertTrue(JsonMCPTarget.deepEquals(a, b));
    }

    @Test
    public void testDeepEqualsNulls() {
        assertTrue(JsonMCPTarget.deepEquals(null, null));
        assertFalse(JsonMCPTarget.deepEquals(null, "x"));
        assertFalse(JsonMCPTarget.deepEquals("x", null));
    }

    /* ==================== helper ==================== */

    private FileChange findChange(List<FileChange> files, String pathContains) {
        for (FileChange f : files) {
            if (f.path != null && f.path.contains(pathContains)) {
                return f;
            }
        }
        fail("No FileChange found for: " + pathContains
                + " in " + files.stream().map(f -> f.path).collect(Collectors.joining(", ")));
        return null;
    }
}
