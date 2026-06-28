package com.codegraph.sync;

import com.codegraph.cli.CodeGraphCli;
import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;
import com.codegraph.core.FileRecord;
import com.codegraph.core.Node;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * SyncOrchestrator 本地集成测试。
 * 
 * 流程：
 * 1. 创建临时 mock 项目（含几个 .java 文件）
 * 2. 执行 init + index
 * 3. 修改部分文件 + 新增文件 + 删除文件
 * 4. 执行 sync 并验证增量同步结果
 */
public class SyncOrchestratorTest {

    private static Path tempDir;
    private static Path dbFile;

    public static void main(String[] args) throws Exception {
        System.out.println("========== SyncOrchestrator 本地测试 ==========\n");

        try {
            testFullFlow();
            System.out.println("\n✓ 所有测试通过！");
        } finally {
            cleanup();
        }
    }

    static void testFullFlow() throws Exception {
        // ===== Phase 1: 创建 mock 项目 =====
        System.out.println(">>> Phase 1: 创建 mock 项目");
        tempDir = Files.createTempDirectory("codegraph-sync-test-");
        dbFile = tempDir.resolve(".codegraph/codegraph4j.db");
        System.out.println("  项目路径: " + tempDir);

        // 创建 src/main/java/com/example 目录结构
        Path srcDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(srcDir);

        writeJavaFile(srcDir, "HelloService.java",
                "package com.example;\n" +
                "\n" +
                "public class HelloService {\n" +
                "    public String sayHello(String name) {\n" +
                "        return \"Hello, \" + name;\n" +
                "    }\n" +
                "}\n");

        writeJavaFile(srcDir, "UserController.java",
                "package com.example;\n" +
                "\n" +
                "public class UserController {\n" +
                "    private HelloService helloService;\n" +
                "\n" +
                "    public String greet(String name) {\n" +
                "        return helloService.sayHello(name);\n" +
                "    }\n" +
                "}\n");

        writeJavaFile(srcDir, "User.java",
                "package com.example;\n" +
                "\n" +
                "public class User {\n" +
                "    private String name;\n" +
                "    private int age;\n" +
                "\n" +
                "    public String getName() { return name; }\n" +
                "    public int getAge() { return age; }\n" +
                "}\n");

        System.out.println("  创建了 3 个 Java 文件\n");

        // ===== Phase 2: init + index =====
        System.out.println(">>> Phase 2: init + index");
        runCli("init", "-f", "-p", tempDir.toString());
        runCli("index", "-p", tempDir.toString());

        // 验证初始状态
        try (DatabaseConnection db = openDb()) {
            QueryBuilder qb = new QueryBuilder(db);
            long nodeCount = qb.getNodeCount();
            long edgeCount = qb.getEdgeCount();
            List<String> files = qb.getAllFiles();
            System.out.println("  初始化后: nodes=" + nodeCount + ", edges=" + edgeCount + ", files=" + files.size());
            assert nodeCount > 0 : "应该有节点被索引";
            assert files.size() >= 3 : "应该有至少3个文件被追踪";
        }
        System.out.println();

        // ===== Phase 3: 修改部分文件、新增、删除 =====
        System.out.println(">>> Phase 3: 变更文件");

        // 3a. 修改 HelloService.java
        System.out.println("  3a. 修改 HelloService.java");
        writeJavaFile(srcDir, "HelloService.java",
                "package com.example;\n" +
                "\n" +
                "public class HelloService {\n" +
                "    public String sayHello(String name) {\n" +
                "        return \"Hi, \" + name + \"!\";\n" +  // 改动了这行
                "    }\n" +
                "\n" +
                "    public String sayGoodbye(String name) {\n" +  // 新增方法
                "        return \"Goodbye, \" + name;\n" +
                "    }\n" +
                "}\n");

        // 3b. 新增文件
        System.out.println("  3b. 新增 OrderService.java");
        writeJavaFile(srcDir, "OrderService.java",
                "package com.example;\n" +
                "\n" +
                "public class OrderService {\n" +
                "    public String createOrder(String item) {\n" +
                "        return \"Order created: \" + item;\n" +
                "    }\n" +
                "}\n");

        // 3c. 删除 User.java
        System.out.println("  3c. 删除 User.java");
        Files.delete(srcDir.resolve("User.java"));

        System.out.println();

        // ===== Phase 4: 执行 sync =====
        System.out.println(">>> Phase 4: 执行增量同步");
        SyncResult result;
        try (DatabaseConnection db = openDb()) {
            QueryBuilder qb = new QueryBuilder(db);
            SyncOrchestrator orch = new SyncOrchestrator();
            result = orch.sync(tempDir, qb, false, 
                fileName -> System.out.println("    Indexed: " + fileName));
        }

        System.out.println();
        System.out.println("  同步结果:");
        System.out.println("    filesChecked : " + result.getFilesChecked());
        System.out.println("    filesAdded   : " + result.getFilesAdded());
        System.out.println("    filesModified: " + result.getFilesModified());
        System.out.println("    filesRemoved : " + result.getFilesRemoved());
        System.out.println("    nodesUpdated : " + result.getNodesUpdated());
        System.out.println("    durationMs   : " + result.getDurationMs());
        System.out.println("    changedPaths : " + result.getChangedFilePaths());
        System.out.println();

        // ===== Phase 5: 验证 =====
        System.out.println(">>> Phase 5: 验证结果");

        // 5a. 验证计数
        assert result.getFilesAdded() == 1 : "应该新增 1 个文件";
        assert result.getFilesModified() == 1 : "应该修改 1 个文件";
        assert result.getFilesRemoved() == 1 : "应该删除 1 个文件";
        System.out.println("  ✓ filesAdded=1, filesModified=1, filesRemoved=1");

        // 5b. 验证变更路径
        assert result.getChangedFilePaths().stream().anyMatch(p -> p.contains("OrderService.java"))
            : "应该包含 OrderService.java";
        assert result.getChangedFilePaths().stream().anyMatch(p -> p.contains("HelloService.java"))
            : "应该包含 HelloService.java";
        assert result.getChangedFilePaths().stream().anyMatch(p -> p.contains("User.java"))
            : "应该包含 User.java";
        System.out.println("  ✓ changedFilePaths 包含所有变更文件");

        // 5c. 验证再次 sync 没有变化
        System.out.println("  5c. 再次 sync（应该无变化）...");
        try (DatabaseConnection db = openDb()) {
            QueryBuilder qb = new QueryBuilder(db);
            SyncOrchestrator orch = new SyncOrchestrator();
            SyncResult result2 = orch.sync(tempDir, qb, false, null);
            assert result2.getFilesChanged() == 0 : "第二次 sync 应该没有变化";
            System.out.println("  ✓ 第二次 sync: filesChanged=0 (无变化)");
        }

        System.out.println();
    }

    // ===== 辅助方法 =====

    static void writeJavaFile(Path dir, String name, String content) throws IOException {
        Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    static DatabaseConnection openDb() throws Exception {
        DatabaseConnection db = new DatabaseConnection(dbFile.toString());
        db.open();
        return db;
    }

    static void runCli(String... args) {
        int exitCode = new CommandLine(new CodeGraphCli()).execute(args);
        if (exitCode != 0) {
            throw new RuntimeException("CLI 命令失败: " + Arrays.toString(args) + " exit=" + exitCode);
        }
    }

    static void cleanup() {
        if (tempDir != null && Files.exists(tempDir)) {
            try {
                Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
                System.out.println("  已清理临时目录: " + tempDir);
            } catch (IOException e) {
                System.err.println("  清理临时目录失败: " + e.getMessage());
            }
        }
    }
}
