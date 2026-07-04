package com.codegraph.sync;

import com.codegraph.cli.CodeGraphCli;
import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;
import com.codegraph.db.SchemaManager;
import com.codegraph.utils.FileFilterUtils;
import com.codegraph.core.FileRecord;
import com.codegraph.core.types.Language;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import picocli.CommandLine;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * SyncOrchestrator 单元测试 — 覆盖核心逻辑与边界情况。
 *
 * 测试范围：
 * - calculateHash: 相同/不同内容
 * - isCodeFile: 支持/不支持的扩展名
 * - isNotExcluded: 排除/不排除的路径
 * - findCodeFiles: 扫描、空目录、过滤非代码文件
 * - sync: 空项目、首次索引、无变化跳过、修改检测、新增、删除、混合、force
 * - getChangedFiles: 文件系统回退
 * - SyncResult: getFilesChanged 计算
 */
public class SyncOrchestratorUnitTest {

    private Path tempDir;
    private Path dbFile;
    private SyncOrchestrator orchestrator;

    @Before
    public void setUp() throws Exception {
        orchestrator = new SyncOrchestrator();
        tempDir = Files.createTempDirectory("codegraph-sync-unit-");
        dbFile = tempDir.resolve(".codegraph/codegraph4j.db");
        Files.createDirectories(dbFile.getParent());
    }

    @After
    public void tearDown() throws Exception {
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    // ==================== calculateHash ====================

    @Test
    public void testCalculateHash_sameContentProducesSameHash() throws Exception {
        Path f1 = tempDir.resolve("a.txt");
        Path f2 = tempDir.resolve("b.txt");
        Files.write(f1, "hello".getBytes(StandardCharsets.UTF_8));
        Files.write(f2, "hello".getBytes(StandardCharsets.UTF_8));

        assertEquals(orchestrator.calculateHash(f1), orchestrator.calculateHash(f2));
    }

    @Test
    public void testCalculateHash_differentContentProducesDifferentHash() throws Exception {
        Path f1 = tempDir.resolve("a.txt");
        Path f2 = tempDir.resolve("b.txt");
        Files.write(f1, "hello".getBytes(StandardCharsets.UTF_8));
        Files.write(f2, "world".getBytes(StandardCharsets.UTF_8));

        assertNotEquals(orchestrator.calculateHash(f1), orchestrator.calculateHash(f2));
    }

    @Test
    public void testCalculateHash_nonEmpty() throws Exception {
        Path f = tempDir.resolve("x.txt");
        Files.write(f, "test".getBytes(StandardCharsets.UTF_8));
        assertNotNull(orchestrator.calculateHash(f));
        assertFalse(orchestrator.calculateHash(f).isEmpty());
    }

    // ==================== isCodeFile ====================

    private boolean isCodeFile(Path p) {
        return FileFilterUtils.isSourceFile(p.toString());
    }

    @Test
    public void testIsCodeFile_supportedExtensions() {
        assertTrue(isCodeFile(tempDir.resolve("Foo.java")));
        assertTrue(isCodeFile(tempDir.resolve("bar.js")));
        assertTrue(isCodeFile(tempDir.resolve("baz.jsx")));
        assertTrue(isCodeFile(tempDir.resolve("app.ts")));
        assertTrue(isCodeFile(tempDir.resolve("app.tsx")));
        assertTrue(isCodeFile(tempDir.resolve("lib.mjs")));
        // 大小写不敏感
        assertTrue(isCodeFile(tempDir.resolve("Foo.JAVA")));
        assertTrue(isCodeFile(tempDir.resolve("App.TS")));
    }

    @Test
    public void testIsCodeFile_unsupportedExtensions() {
        assertFalse(isCodeFile(tempDir.resolve("readme.md")));
        assertFalse(isCodeFile(tempDir.resolve("pom.xml")));
        assertFalse(isCodeFile(tempDir.resolve("test.py")));
        assertFalse(isCodeFile(tempDir.resolve("Dockerfile")));
        assertFalse(isCodeFile(tempDir.resolve("test.go")));
        assertFalse(isCodeFile(tempDir.resolve(".gitignore")));
    }

    @Test
    public void testIsCodeFile_directoryIsFalse() throws Exception {
        Path dir = tempDir.resolve("src");
        Files.createDirectories(dir);
        assertFalse(isCodeFile(dir));
    }

    // ==================== isNotExcluded ====================

    @Test
    public void testIsNotExcluded_excludedPatterns() {
        assertFalse(orchestrator.isNotExcluded(tempDir.resolve("some/.git/config")));
        assertFalse(orchestrator.isNotExcluded(tempDir.resolve("proj/.codegraph/db")));
        assertFalse(orchestrator.isNotExcluded(tempDir.resolve("app/node_modules/lodash/index.js")));
        assertFalse(orchestrator.isNotExcluded(tempDir.resolve("project/target/classes/Foo.class")));
        assertFalse(orchestrator.isNotExcluded(tempDir.resolve("build/output.txt")));
        assertFalse(orchestrator.isNotExcluded(tempDir.resolve("dist/bundle.js")));
        assertFalse(orchestrator.isNotExcluded(tempDir.resolve("src/.DS_Store")));
        assertFalse(orchestrator.isNotExcluded(tempDir.resolve("project.iml")));
    }

    @Test
    public void testIsNotExcluded_allowedPaths() {
        assertTrue(orchestrator.isNotExcluded(tempDir.resolve("src/main/java/Foo.java")));
        assertTrue(orchestrator.isNotExcluded(tempDir.resolve("lib/utils.js")));
        assertTrue(orchestrator.isNotExcluded(tempDir.resolve("pom.xml")));
        assertTrue(orchestrator.isNotExcluded(tempDir.resolve("test/App.ts")));
    }

    @Test
    public void testExcludePatternsAreImmutable() {
        List<String> patterns = orchestrator.getExcludePatterns();
        assertTrue(patterns.contains(".git"));
        assertTrue(patterns.contains("node_modules"));
        assertTrue(patterns.contains("target"));
        assertTrue(patterns.contains("build"));
        assertTrue(patterns.contains("dist"));
        assertEquals(11, patterns.size());
    }

    // ==================== findCodeFiles ====================

    @Test
    public void testFindCodeFiles_emptyDirectory() throws Exception {
        List<Path> files = orchestrator.findCodeFiles(tempDir);
        assertTrue(files.isEmpty());
    }

    @Test
    public void testFindCodeFiles_onlyCodeFilesCollected() throws Exception {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve("App.java"), "class App {}".getBytes());
        Files.write(srcDir.resolve("readme.md"), "# doc".getBytes());
        Files.write(srcDir.resolve("config.xml"), "<xml/>".getBytes());
        Files.write(srcDir.resolve("helper.js"), "function f() {}".getBytes());

        List<Path> files = orchestrator.findCodeFiles(tempDir);
        List<String> names = files.stream().map(p -> p.getFileName().toString()).collect(Collectors.toList());

        assertEquals(2, files.size());
        assertTrue(names.contains("App.java"));
        assertTrue(names.contains("helper.js"));
        assertFalse(names.contains("readme.md"));
        assertFalse(names.contains("config.xml"));
    }

    @Test
    public void testFindCodeFiles_excludedDirectoriesFiltered() throws Exception {
        Path srcDir = tempDir.resolve("src/main/java");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve("App.java"), "class App {}".getBytes());

        Path nodeModulesDir = tempDir.resolve("node_modules/lodash");
        Files.createDirectories(nodeModulesDir);
        Files.write(nodeModulesDir.resolve("index.js"), "module.exports = {}".getBytes());

        Path targetDir = tempDir.resolve("target/classes");
        Files.createDirectories(targetDir);
        Files.write(targetDir.resolve("Generated.java"), "class G {}".getBytes());

        List<Path> files = orchestrator.findCodeFiles(tempDir);

        // 只有 src/main/java/App.java 被收集
        assertEquals(1, files.size());
        assertTrue(files.get(0).toString().endsWith("App.java"));
    }

    @Test
    public void testFindCodeFiles_nestedDirectories() throws Exception {
        Path javaDir = tempDir.resolve("src/main/java/com/example");
        Path jsDir = tempDir.resolve("frontend/js");
        Files.createDirectories(javaDir);
        Files.createDirectories(jsDir);
        Files.write(javaDir.resolve("App.java"), "class App {}".getBytes());
        Files.write(javaDir.resolve("User.java"), "class User {}".getBytes());
        Files.write(jsDir.resolve("main.js"), "console.log('x')".getBytes());

        List<Path> files = orchestrator.findCodeFiles(tempDir);

        assertEquals(3, files.size());
        long javaCount = files.stream().filter(p -> p.toString().endsWith(".java")).count();
        long jsCount = files.stream().filter(p -> p.toString().endsWith(".js")).count();
        assertEquals(2, javaCount);
        assertEquals(1, jsCount);
    }

    // ==================== sync() 核心流程 ====================

    /** 辅助：创建测试数据库 */
    private QueryBuilder createTestDb() throws Exception {
        DatabaseConnection db = new DatabaseConnection(dbFile.toString());
        db.open();
        new SchemaManager(db).initSchema();
        return new QueryBuilder(db);
    }

    /** 辅助：运行完整 init 以创建 schema */
    private void initProject() {
        int code = new CommandLine(new CodeGraphCli()).execute(
                "init", "-f", "-p", tempDir.toString());
        assertEquals(0, code);
    }

    /**
     * 空项目同步 — 不含任何代码文件时应返回空结果。
     */
    @Test
    public void testSync_emptyProject() throws Exception {
        initProject();

        try (DatabaseConnection db = new DatabaseConnection(dbFile.toString())) {
            db.open();
            QueryBuilder qb = new QueryBuilder(db);
            SyncResult result = orchestrator.sync(tempDir, qb, false, null);

            assertEquals(0, result.getFilesChecked());
            assertEquals(0, result.getFilesAdded());
            assertEquals(0, result.getFilesModified());
            assertEquals(0, result.getFilesRemoved());
            assertEquals(0, result.getFilesChanged());
            assertTrue(result.getChangedFilePaths().isEmpty());
            assertTrue(result.getDurationMs() >= 0);
        }
    }

    /**
     * 首次同步 — 所有代码文件都应被视为新增并索引。
     */
    @Test
    public void testSync_firstIndexAllFilesAdded() throws Exception {
        initProject();
        Path srcDir = tempDir.resolve("src/main/java");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve("App.java"), "class App {}".getBytes());
        Files.write(srcDir.resolve("User.java"), "class User {}".getBytes());

        try (DatabaseConnection db = new DatabaseConnection(dbFile.toString())) {
            db.open();
            QueryBuilder qb = new QueryBuilder(db);
            SyncResult result = orchestrator.sync(tempDir, qb, false, null);

            assertEquals(2, result.getFilesChecked());
            assertEquals(2, result.getFilesAdded());
            assertEquals(0, result.getFilesModified());
            assertEquals(0, result.getFilesRemoved());
            assertEquals(2, result.getFilesChanged());
            assertEquals(2, result.getChangedFilePaths().size());
        }
    }

    /**
     * 幂等同步 — 无文件变更时第二次 sync 应无变化。
     */
    @Test
    public void testSync_idempotentNoChangesOnRerun() throws Exception {
        initProject();
        Path srcDir = tempDir.resolve("src/main/java");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve("App.java"), "class App {}".getBytes());

        // 第一次 sync
        try (DatabaseConnection db = new DatabaseConnection(dbFile.toString())) {
            db.open();
            QueryBuilder qb = new QueryBuilder(db);
            SyncResult r1 = orchestrator.sync(tempDir, qb, false, null);
            assertEquals(1, r1.getFilesAdded());
        }

        // 第二次 sync — 文件未被修改
        try (DatabaseConnection db = new DatabaseConnection(dbFile.toString())) {
            db.open();
            QueryBuilder qb = new QueryBuilder(db);
            SyncResult r2 = orchestrator.sync(tempDir, qb, false, null);

            assertEquals(1, r2.getFilesChecked());
            assertEquals(0, r2.getFilesAdded());
            assertEquals(0, r2.getFilesModified());
            assertEquals(0, r2.getFilesRemoved());
            assertEquals(0, r2.getFilesChanged());
        }
    }

    /**
     * 修改检测 — 文件内容变更后应被检测为 modified。
     */
    @Test
    public void testSync_detectsModifiedFile() throws Exception {
        initProject();
        Path srcDir = tempDir.resolve("src/main/java");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve("App.java"), "class App {}".getBytes());

        // 首次索引
        try (DatabaseConnection db = new DatabaseConnection(dbFile.toString())) {
            db.open();
            QueryBuilder qb = new QueryBuilder(db);
            orchestrator.sync(tempDir, qb, false, null);
        }

        // 修改文件（需要确保 mtime 确实变了）
        Thread.sleep(10); // 等待文件系统时间戳推进
        Files.write(srcDir.resolve("App.java"), "class App { int x = 1; }".getBytes());

        // 同步应检测到修改
        try (DatabaseConnection db = new DatabaseConnection(dbFile.toString())) {
            db.open();
            QueryBuilder qb = new QueryBuilder(db);
            SyncResult result = orchestrator.sync(tempDir, qb, false, null);

            assertEquals(1, result.getFilesChecked());
            assertEquals(0, result.getFilesAdded());
            assertEquals(1, result.getFilesModified());
            assertEquals(0, result.getFilesRemoved());
            assertEquals(1, result.getFilesChanged());
        }
    }

    /**
     * (size+mtime) 快速预过滤 — 仅在 size 或 mtime 变化后才计算 hash。
     * 验证：size+mtime 都不变时跳过，不触发 modified。
     */
    @Test
    public void testSync_skipsUnchangedBySizeAndMtime() throws Exception {
        initProject();
        Path srcDir = tempDir.resolve("src/main/java");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve("App.java"), "class App {}".getBytes());

        // 首次索引
        try (DatabaseConnection db = new DatabaseConnection(dbFile.toString())) {
            db.open();
            QueryBuilder qb = new QueryBuilder(db);
            orchestrator.sync(tempDir, qb, false, null);
        }

        // 不修改文件直接同步
        try (DatabaseConnection db = new DatabaseConnection(dbFile.toString())) {
            db.open();
            QueryBuilder qb = new QueryBuilder(db);
            SyncResult result = orchestrator.sync(tempDir, qb, false, null);

            assertEquals(0, result.getFilesModified());
            assertEquals(0, result.getFilesAdded());
            assertEquals(0, result.getFilesChanged());
        }
    }

    /**
     * 新增文件 — 同步后新文件被添加到索引。
     */
    @Test
    public void testSync_detectsNewFile() throws Exception {
        initProject();
        Path srcDir = tempDir.resolve("src/main/java");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve("App.java"), "class App {}".getBytes());

        // 首次索引
        try (DatabaseConnection db = new DatabaseConnection(dbFile.toString())) {
            db.open();
            QueryBuilder qb = new QueryBuilder(db);
            orchestrator.sync(tempDir, qb, false, null);
        }

        // 新增文件
        Files.write(srcDir.resolve("User.java"), "class User {}".getBytes());

        // 同步
        try (DatabaseConnection db = new DatabaseConnection(dbFile.toString())) {
            db.open();
            QueryBuilder qb = new QueryBuilder(db);
            SyncResult result = orchestrator.sync(tempDir, qb, false, null);

            assertEquals(2, result.getFilesChecked());
            assertEquals(1, result.getFilesAdded());
            assertEquals(0, result.getFilesModified());
            assertEquals(0, result.getFilesRemoved());
            assertEquals(1, result.getFilesChanged());
            assertTrue(result.getChangedFilePaths().stream()
                    .anyMatch(p -> p.endsWith("User.java")));
        }
    }

    /**
     * 删除文件 — 已跟踪文件从文件系统删除后应被移除。
     */
    @Test
    public void testSync_detectsDeletedFile() throws Exception {
        initProject();
        Path srcDir = tempDir.resolve("src/main/java");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve("App.java"), "class App {}".getBytes());
        Files.write(srcDir.resolve("User.java"), "class User {}".getBytes());

        // 首次索引
        try (DatabaseConnection db = new DatabaseConnection(dbFile.toString())) {
            db.open();
            QueryBuilder qb = new QueryBuilder(db);
            orchestrator.sync(tempDir, qb, false, null);
        }

        // 删除一个文件
        Files.delete(srcDir.resolve("User.java"));

        // 同步
        try (DatabaseConnection db = new DatabaseConnection(dbFile.toString())) {
            db.open();
            QueryBuilder qb = new QueryBuilder(db);
            SyncResult result = orchestrator.sync(tempDir, qb, false, null);

            assertEquals(1, result.getFilesChecked()); // 只剩 1 个文件
            assertEquals(0, result.getFilesAdded());
            assertEquals(0, result.getFilesModified());
            assertEquals(1, result.getFilesRemoved());
            assertEquals(1, result.getFilesChanged());
            assertTrue(result.getChangedFilePaths().stream()
                    .anyMatch(p -> p.endsWith("User.java")));
        }
    }

    /**
     * 混合变更 — 同时新增、修改、删除。
     */
    @Test
    public void testSync_mixedAddModifyDelete() throws Exception {
        initProject();
        Path srcDir = tempDir.resolve("src/main/java");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve("App.java"), "class App {}".getBytes());
        Files.write(srcDir.resolve("User.java"), "class User {}".getBytes());
        Files.write(srcDir.resolve("Service.java"), "class Service {}".getBytes());

        // 首次索引全部
        try (DatabaseConnection db = new DatabaseConnection(dbFile.toString())) {
            db.open();
            QueryBuilder qb = new QueryBuilder(db);
            SyncResult r0 = orchestrator.sync(tempDir, qb, false, null);
            assertEquals(3, r0.getFilesAdded());
        }

        // 修改 App.java + 新增 Order.java + 删除 Service.java
        Thread.sleep(10);
        Files.write(srcDir.resolve("App.java"), "class App { void m() {} }".getBytes());
        Files.write(srcDir.resolve("Order.java"), "class Order {}".getBytes());
        Files.delete(srcDir.resolve("Service.java"));

        // 同步
        try (DatabaseConnection db = new DatabaseConnection(dbFile.toString())) {
            db.open();
            QueryBuilder qb = new QueryBuilder(db);
            SyncResult result = orchestrator.sync(tempDir, qb, false, null);

            assertEquals(3, result.getFilesChecked());
            assertEquals(1, result.getFilesAdded());
            assertEquals(1, result.getFilesModified());
            assertEquals(1, result.getFilesRemoved());
            assertEquals(3, result.getFilesChanged());
            assertEquals(3, result.getChangedFilePaths().size());
        }
    }

    /**
     * force=true — 强制重新索引所有文件，即使是未变更的也视为 modified。
     */
    @Test
    public void testSync_forceReindexesAll() throws Exception {
        initProject();
        Path srcDir = tempDir.resolve("src/main/java");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve("App.java"), "class App {}".getBytes());
        Files.write(srcDir.resolve("User.java"), "class User {}".getBytes());

        // 首次普通索引
        try (DatabaseConnection db = new DatabaseConnection(dbFile.toString())) {
            db.open();
            QueryBuilder qb = new QueryBuilder(db);
            orchestrator.sync(tempDir, qb, false, null);
        }

        // force 重新索引
        try (DatabaseConnection db = new DatabaseConnection(dbFile.toString())) {
            db.open();
            QueryBuilder qb = new QueryBuilder(db);
            SyncResult result = orchestrator.sync(tempDir, qb, true, null);

            assertEquals(2, result.getFilesChecked());
            // force 时不跳增，直接标记为 modified
            assertEquals(0, result.getFilesAdded());
            assertEquals(2, result.getFilesModified());
            assertEquals(0, result.getFilesRemoved());
            assertEquals(2, result.getFilesChanged());
        }
    }

    /**
     * 不支持的扩展名 — 无解析器的文件不参与 sync 处理。
     */
    @Test
    public void testSync_unsupportedExtensionsSkipped() throws Exception {
        initProject();
        Path srcDir = tempDir.resolve("src/main/java");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve("App.java"), "class App {}".getBytes());

        // 写入一个无解析器的文件
        Files.write(srcDir.resolve("test.py"), "def f(): pass".getBytes());

        try (DatabaseConnection db = new DatabaseConnection(dbFile.toString())) {
            db.open();
            QueryBuilder qb = new QueryBuilder(db);
            SyncResult result = orchestrator.sync(tempDir, qb, false, null);

            // test.py 不算代码文件，filesChecked 只有 1
            assertEquals(1, result.getFilesChecked());
            assertEquals(1, result.getFilesAdded());
        }
    }

    /**
     * 进度回调 — onProgress 在文件处理时被调用。
     */
    @Test
    public void testSync_onProgressCalled() throws Exception {
        initProject();
        Path srcDir = tempDir.resolve("src/main/java");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve("App.java"), "class App {}".getBytes());

        final int[] callCount = {0};
        try (DatabaseConnection db = new DatabaseConnection(dbFile.toString())) {
            db.open();
            QueryBuilder qb = new QueryBuilder(db);
            orchestrator.sync(tempDir, qb, false,
                    fileName -> { callCount[0]++; });
        }

        assertEquals(1, callCount[0]);
    }

    // ==================== getChangedFiles ====================

    /**
     * 文件系统回退 — 在有 DB 记录且文件未变化时返回空。
     */
    @Test
    public void testGetChangedFiles_noChangesEmpty() throws Exception {
        initProject();
        Path srcDir = tempDir.resolve("src/main/java");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve("App.java"), "class App {}".getBytes());

        // 首次索引
        try (DatabaseConnection db = new DatabaseConnection(dbFile.toString())) {
            db.open();
            QueryBuilder qb = new QueryBuilder(db);
            orchestrator.sync(tempDir, qb, false, null);
        }

        // getChangedFiles：文件未变化应返回空
        try (DatabaseConnection db = new DatabaseConnection(dbFile.toString())) {
            db.open();
            QueryBuilder qb = new QueryBuilder(db);
            List<String> changed = orchestrator.getChangedFiles(tempDir, qb);
            assertTrue("无变化时应返回空列表: " + changed, changed.isEmpty());
        }
    }

    /**
     * 文件系统回退 — 新文件被检测为变更。
     */
    @Test
    public void testGetChangedFiles_newFileDetected() throws Exception {
        initProject();
        Path srcDir = tempDir.resolve("src/main/java");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve("App.java"), "class App {}".getBytes());

        // 首次索引
        try (DatabaseConnection db = new DatabaseConnection(dbFile.toString())) {
            db.open();
            QueryBuilder qb = new QueryBuilder(db);
            orchestrator.sync(tempDir, qb, false, null);
        }

        // 新增文件
        Files.write(srcDir.resolve("User.java"), "class User {}".getBytes());

        try (DatabaseConnection db = new DatabaseConnection(dbFile.toString())) {
            db.open();
            QueryBuilder qb = new QueryBuilder(db);
            List<String> changed = orchestrator.getChangedFiles(tempDir, qb);
            assertEquals(1, changed.size());
            assertTrue(changed.get(0).endsWith("User.java"));
        }
    }

    /**
     * 文件系统回退 — 修改后的文件被检测为变更。
     */
    @Test
    public void testGetChangedFiles_modifiedFileDetected() throws Exception {
        initProject();
        Path srcDir = tempDir.resolve("src/main/java");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve("App.java"), "class App {}".getBytes());

        // 首次索引
        try (DatabaseConnection db = new DatabaseConnection(dbFile.toString())) {
            db.open();
            QueryBuilder qb = new QueryBuilder(db);
            orchestrator.sync(tempDir, qb, false, null);
        }

        // 修改文件
        Thread.sleep(10);
        Files.write(srcDir.resolve("App.java"), "class App { int x; }".getBytes());

        try (DatabaseConnection db = new DatabaseConnection(dbFile.toString())) {
            db.open();
            QueryBuilder qb = new QueryBuilder(db);
            List<String> changed = orchestrator.getChangedFiles(tempDir, qb);
            assertEquals(1, changed.size());
            assertTrue(changed.get(0).endsWith("App.java"));
        }
    }

    // ==================== SyncResult ====================

    @Test
    public void testSyncResult_getFilesChanged() {
        SyncResult r = new SyncResult();
        assertEquals(0, r.getFilesChanged());

        r.setFilesAdded(1);
        assertEquals(1, r.getFilesChanged());

        r.setFilesModified(2);
        assertEquals(3, r.getFilesChanged());

        r.setFilesRemoved(1);
        assertEquals(4, r.getFilesChanged());
    }

    @Test
    public void testSyncResult_initialValues() {
        SyncResult r = new SyncResult();
        assertEquals(0, r.getFilesChecked());
        assertEquals(0, r.getFilesAdded());
        assertEquals(0, r.getFilesModified());
        assertEquals(0, r.getFilesRemoved());
        assertEquals(0, r.getNodesUpdated());
        assertEquals(0, r.getDurationMs());
        assertNotNull(r.getChangedFilePaths());
        assertTrue(r.getChangedFilePaths().isEmpty());
    }
}
