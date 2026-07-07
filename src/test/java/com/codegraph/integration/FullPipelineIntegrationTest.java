package com.codegraph.integration;

import com.codegraph.cli.CodeGraphCli;
import com.codegraph.context.ContextBuilder;
import com.codegraph.context.Subgraph;
import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;
import com.codegraph.graph.GraphTraverser;
import com.codegraph.graph.GraphTraverser.CallerInfo;
import com.codegraph.resolution.ReferenceResolver;
import com.codegraph.sync.SyncOrchestrator;
import com.codegraph.sync.SyncResult;
import com.codegraph.core.Node;
import picocli.CommandLine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 端到端流水线集成测试。
 *
 * <p>覆盖单元测试独立验证的完整主流程：
 *   init → sync（索引）→ resolveReferences → searchNodes → getCallers → buildContext → sync
 *
 * <p>同时覆盖两个异常路径：
 *   <ul>
 *     <li>索引包含语法错误片段文件时，解析错误不得中断整个批处理。</li>
 *     <li>sync 在一次执行中正确应用新增 + 修改 + 删除。</li>
 *   </ul>
 *
 * <p>每个测试会生成一个链式模块类的合成项目。
 * 每个模块 {@code Mod<i>} 继承自 {@code Mod<i-1>} 并调用其方法，
 * 为解析器和遍历器提供真实的导入边和调用边。
 */
public class FullPipelineIntegrationTest {

    private Path tempDir;

    /**
     * 每个测试前创建唯一的临时目录。
     */
    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("codegraph-int-");
    }

    /**
     * 每个测试后递归删除临时目录。
     */
    @After
    public void tearDown() throws Exception {
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    /**
     * 生成指定模块数量的合成 Java 项目。
     *
     * <p>模块链结构：
     * <ul>
     *   <li>{@code Mod0} — 叶子类，无导入。</li>
     *   <li>{@code Mod1..Mod{N-1}} — 每个类继承前一个模块并调用其
     *       {@code fn<i-1>(x)} 方法，产生真实的导入边和调用边。</li>
     *   <li>{@code Entry} — 根类，实例化链中最后一个模块并调用其方法。</li>
     * </ul>
     *
     * @param moduleCount 要生成的链式模块数量
     */
    private void generateSyntheticProject(int moduleCount) throws Exception {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);

        // 叶子模块 — 无导入
        String mod0Content = "package com.example;\n" +
                "public class Mod0 {\n" +
                "    public int fn0(int x) { return x + 1; }\n" +
                "}\n";
        Files.write(srcDir.resolve("Mod0.java"), mod0Content.getBytes(StandardCharsets.UTF_8));

        // 链：每个模块继承前一个并调用其方法
        for (int i = 1; i < moduleCount; i++) {
            int prev = i - 1;
            String content = "package com.example;\n" +
                    "import com.example.Mod" + prev + ";\n" +
                    "public class Mod" + i + " extends Mod" + prev + " {\n" +
                    "    public int fn" + i + "(int x) { return fn" + prev + "(x) + 1; }\n" +
                    "}\n";
            Files.write(srcDir.resolve("Mod" + i + ".java"), content.getBytes(StandardCharsets.UTF_8));
        }

        // 入口点 — 导入并调用链中最后一个模块
        String entryContent = "package com.example;\n" +
                "import com.example.Mod" + (moduleCount - 1) + ";\n" +
                "public class Entry {\n" +
                "    public int entry() {\n" +
                "        Mod" + (moduleCount - 1) + " m = new Mod" + (moduleCount - 1) + "();\n" +
                "        return m.fn" + (moduleCount - 1) + "(0);\n" +
                "    }\n" +
                "}\n";
        Files.write(srcDir.resolve("Entry.java"), entryContent.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 执行 CLI 的 {@code init -f -p <tempDir>} 命令初始化项目。
     * 断言退出码为 0（成功）。
     */
    private void initProject() {
        int code = new CommandLine(new CodeGraphCli()).execute(
                "init", "-f", "-p", tempDir.toString());
        assertEquals(0, code);
    }

    /**
     * 端到端运行完整流水线：init → sync（indexAll）→ resolveReferences →
     * searchNodes → getCallers → buildContext → sync（新增 + 修改 + 删除）。
     *
     * <p>生成链式模块的合成项目（{@code Mod0..ModN}），
     * 索引所有文件，解析跨文件引用，搜索符号，验证调用者/被调用者边，
     * 构建相关上下文，然后修改文件系统（新增 Consumer.java、修改 Mod0、删除 Mod1）
     * 并运行 sync 验证新增、修改和删除均在单次执行中被检测到。
     */
    @Test
    public void testFullPipeline() throws Exception {
        int MODULE_COUNT = 10;
        generateSyntheticProject(MODULE_COUNT);

        initProject();

        try (DatabaseConnection db = new DatabaseConnection(
                tempDir.resolve(".codegraph/codegraph4j.db").toString())) {
            db.open();
            QueryBuilder queries = new QueryBuilder(db);
            SyncOrchestrator syncOrchestrator = new SyncOrchestrator();

            // ── sync（索引全部文件）─────────────────────────────────
            // 合成项目：MODULE_COUNT 个 mod 文件 + 1 个 entry 文件
            SyncResult indexResult = syncOrchestrator.sync(tempDir, queries, false, null);
            assertTrue(indexResult.getFilesAdded() >= MODULE_COUNT);

            long nodeCount = queries.getNodeCount();
            assertTrue(nodeCount > MODULE_COUNT * 2);

            // ── resolveReferences（解析引用）─────────────────────────
            // 许多调用边在提取阶段就已连接，因此到这里时未解析引用队列
            // 可能已经排空。我们断言解析过程顺利完成；
            // 后续的调用者断言验证图中已有实际数据。
            ReferenceResolver resolver = new ReferenceResolver(queries, tempDir.toString());
            resolver.initialize();
            resolver.runPostExtract();
            resolver.resolveUnresolvedRefs();

            // ── searchNodes（搜索节点）───────────────────────────────
            List<Node> entryResults = queries.searchNodes("entry");
            assertTrue(entryResults.size() > 0);
            Node entryNode = null;
            for (Node n : entryResults) {
                if ("entry".equals(n.getName())) {
                    entryNode = n;
                    break;
                }
            }
            assertNotNull(entryNode);

            List<Node> fn0Results = queries.searchNodes("fn0");
            Node fn0Node = null;
            for (Node n : fn0Results) {
                if ("fn0".equals(n.getName())) {
                    fn0Node = n;
                    break;
                }
            }
            assertNotNull(fn0Node);

            // ── getCallers（获取调用者）──────────────────────────────
            // fn0 至少被 fn1 调用。解析后此边应已连接。
            GraphTraverser traverser = new GraphTraverser(queries);
            List<CallerInfo> callers = traverser.getCallers(fn0Node.getId(), 1);
            assertNotNull(callers);
            assertFalse(callers.isEmpty());

            // ── buildContext（构建上下文）─────────────────────────────
            ContextBuilder contextBuilder = new ContextBuilder(queries);
            Subgraph context = contextBuilder.findRelevantContext("entry", null);
            assertNotNull(context);
            assertFalse(context.nodes.isEmpty());

            // ── sync（一次执行中新增 + 修改 + 删除）─────────────────
            // 新增：一个引用 Entry 的新文件
            String consumerContent = "package com.example;\n" +
                    "import com.example.Entry;\n" +
                    "public class Consumer {\n" +
                    "    public int useEntry() { return new Entry().entry(); }\n" +
                    "}\n";
            Files.write(tempDir.resolve("src/Consumer.java"), consumerContent.getBytes(StandardCharsets.UTF_8));

            // 修改：更改 Mod0 — 新增方法，修改 fn0 函数体
            Thread.sleep(10);
            String mod0Modified = "package com.example;\n" +
                    "public class Mod0 {\n" +
                    "    public int fn0(int x) { return x + 2; }\n" +
                    "    public String newHelper() { return \"new\"; }\n" +
                    "}\n";
            Files.write(tempDir.resolve("src/Mod0.java"), mod0Modified.getBytes(StandardCharsets.UTF_8));

            // 删除：删除 Mod1 — 这会在 Mod2 中留下悬空导入，
            // 解析器必须能够容错处理
            Files.delete(tempDir.resolve("src/Mod1.java"));

            SyncResult syncResult = syncOrchestrator.sync(tempDir, queries, false, null);
            assertTrue(syncResult.getFilesAdded() >= 1);
            assertTrue(syncResult.getFilesModified() >= 1);
            assertTrue(syncResult.getFilesRemoved() >= 1);

            // 新增的符号现在必须能被搜索到
            List<Node> newHelperResults = queries.searchNodes("newHelper");
            assertTrue(newHelperResults.size() > 0);

            // 被删除文件的节点应已清空
            List<Node> mod1Nodes = queries.getNodesInFile("src/Mod1.java");
            assertTrue(mod1Nodes.isEmpty());
        }
    }

    /**
     * 验证当存在解析错误文件时，索引仍能继续处理其余文件。
     *
     * <p>创建两个合法的 Java 文件和一个故意含有无效语法的损坏文件。
     * 批量索引必须完成，且合法符号必须能被找到——
     * 单个文件的解析错误不得中断整个批处理。
     */
    @Test
    public void testKeepsIndexingWhenFileHasParseError() throws Exception {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);

        // 合法文件
        String good1Content = "package com.example;\n" +
                "public class Good1 {\n" +
                "    public int good1() { return 1; }\n" +
                "}\n";
        Files.write(srcDir.resolve("Good1.java"), good1Content.getBytes(StandardCharsets.UTF_8));

        String good2Content = "package com.example;\n" +
                "public class Good2 {\n" +
                "    public int good2() { return 2; }\n" +
                "}\n";
        Files.write(srcDir.resolve("Good2.java"), good2Content.getBytes(StandardCharsets.UTF_8));

        // 故意损坏的文件 — 未闭合的大括号、乱码标记
        String brokenContent = "package com.example;\n" +
                "public class Broken {\n" +
                "    public void broken(" +
                "        this is { not valid java at all\n";
        Files.write(srcDir.resolve("Broken.java"), brokenContent.getBytes(StandardCharsets.UTF_8));

        initProject();

        try (DatabaseConnection db = new DatabaseConnection(
                tempDir.resolve(".codegraph/codegraph4j.db").toString())) {
            db.open();
            QueryBuilder queries = new QueryBuilder(db);
            SyncOrchestrator syncOrchestrator = new SyncOrchestrator();

            SyncResult result = syncOrchestrator.sync(tempDir, queries, false, null);
            assertTrue(result.getFilesAdded() >= 2);

            List<Node> good1Results = queries.searchNodes("good1");
            boolean foundGood1 = false;
            for (Node n : good1Results) {
                if ("good1".equals(n.getName())) {
                    foundGood1 = true;
                    break;
                }
            }
            assertTrue(foundGood1);

            List<Node> good2Results = queries.searchNodes("good2");
            boolean foundGood2 = false;
            for (Node n : good2Results) {
                if ("good2".equals(n.getName())) {
                    foundGood2 = true;
                    break;
                }
            }
            assertTrue(foundGood2);
        }
    }

    /**
     * 验证在文件系统无变化时，重复 sync 调用为空操作。
     *
     * <p>首次索引后，连续两次 sync 调用应报告零个新增、修改或删除文件，
     * 且节点/边计数应保持稳定。
     */
    @Test
    public void testHandlesRepeatedSyncCallsWhenNothingChanged() throws Exception {
        generateSyntheticProject(5);

        initProject();

        try (DatabaseConnection db = new DatabaseConnection(
                tempDir.resolve(".codegraph/codegraph4j.db").toString())) {
            db.open();
            QueryBuilder queries = new QueryBuilder(db);
            SyncOrchestrator syncOrchestrator = new SyncOrchestrator();

            syncOrchestrator.sync(tempDir, queries, false, null);
            long nodeCountBefore = queries.getNodeCount();

            SyncResult first = syncOrchestrator.sync(tempDir, queries, false, null);
            SyncResult second = syncOrchestrator.sync(tempDir, queries, false, null);

            assertEquals(0, first.getFilesAdded() + first.getFilesModified() + first.getFilesRemoved());
            assertEquals(0, second.getFilesAdded() + second.getFilesModified() + second.getFilesRemoved());

            long nodeCountAfter = queries.getNodeCount();
            assertEquals(nodeCountBefore, nodeCountAfter);
        }
    }
}
