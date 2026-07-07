package com.codegraph.integration;

import com.codegraph.cli.CodeGraphCli;
import com.codegraph.config.CodeGraphConfig;
import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;
import com.codegraph.graph.GraphQueryManager;
import com.codegraph.graph.GraphTraverser;
import com.codegraph.mcp.MCPTransport.ContentItem;
import com.codegraph.mcp.MCPTransport.ToolCallResult;
import com.codegraph.mcp.tools.*;
import com.codegraph.sync.SyncOrchestrator;
import com.codegraph.sync.SyncResult;
import picocli.CommandLine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * MCP 工具输入大小限制测试。
 *
 * <p>针对 DoS 攻击面的回归覆盖：MCP 客户端可以发送无界负载
 * （{@code query}、{@code symbol}、{@code projectPath}、
 * {@code path}、{@code pattern}）。在设置上限之前，超长字符串
 * 会直接命中 FTS5 层并导致服务器卡死。这些测试断言工具层
 * 在访问数据库之前就能提前拒绝超大输入。
 *
 * <p>每个测试创建一个含单个合法 Java 文件的临时项目，
 * 索引后向对应工具发送超大参数，并验证返回错误及描述性消息。
 */
public class McpInputLimitsTest {

    private Path tempDir;
    private DatabaseConnection db;
    private QueryBuilder queries;
    private CodeGraphConfig config;
    private GraphTraverser traverser;
    private GraphQueryManager graphQueryMgr;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("codegraph-mcp-limits-");
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);

        // 创建含单个文件的最小合法 Java 项目
        String alphaContent = "package com.example;\n" +
                "public class Alpha {\n" +
                "    public int alpha() { return 1; }\n" +
                "}\n";
        Files.write(srcDir.resolve("Alpha.java"), alphaContent.getBytes(StandardCharsets.UTF_8));

        // 通过 CLI 初始化 codegraph 数据库
        int code = new CommandLine(new CodeGraphCli()).execute(
                "init", "-f", "-p", tempDir.toString());
        assertEquals(0, code);

        // 打开数据库并索引项目
        Path dbFile = tempDir.resolve(".codegraph/codegraph4j.db");
        db = new DatabaseConnection(dbFile.toString());
        db.open();
        queries = new QueryBuilder(db);

        SyncOrchestrator syncOrchestrator = new SyncOrchestrator();
        SyncResult result = syncOrchestrator.sync(tempDir, queries, false, null);
        assertTrue(result.getFilesAdded() >= 1);

        config = new CodeGraphConfig(tempDir.toString(), tempDir.toString());
        traverser = new GraphTraverser(queries);
        graphQueryMgr = new GraphQueryManager(queries);
    }

    @After
    public void tearDown() throws Exception {
        if (db != null) {
            db.close();
        }
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    /**
     * 正常大小的查询应被接受且不出错。
     * 这是基准"正常路径"测试。
     */
    @Test
    public void testAcceptsNormalSizedQuery() {
        SearchTool tool = new SearchTool(db, queries, traverser, graphQueryMgr, config);
        Map<String, Object> args = new HashMap<>();
        args.put("query", "alpha");
        ToolCallResult result = tool.execute(args);
        assertFalse("Expected success, got: " + getResultText(result), result.isError);
    }

    /**
     * codegraph_search 的超大查询必须在访问数据库前被拒绝。
     */
    @Test
    public void testRejectsOversizeQueryOnCodegraphSearch() {
        SearchTool tool = new SearchTool(db, queries, traverser, graphQueryMgr, config);
        String huge = createHugeString('a', 20_000);
        Map<String, Object> args = new HashMap<>();
        args.put("query", huge);
        ToolCallResult result = tool.execute(args);
        assertTrue("Expected error for oversize query", result.isError);
        String text = getResultText(result);
        assertTrue("Error message should mention maximum length: " + text,
                text.toLowerCase().contains("maximum") || text.toLowerCase().contains("length"));
    }

    /**
     * codegraph_explore 的超大查询必须在访问数据库前被拒绝。
     */
    @Test
    public void testRejectsOversizeQueryOnCodegraphExplore() {
        ExploreTool tool = new ExploreTool(db, queries, traverser, graphQueryMgr, config);
        String huge = createHugeString('b', 50_000);
        Map<String, Object> args = new HashMap<>();
        args.put("query", huge);
        ToolCallResult result = tool.execute(args);
        assertTrue("Expected error for oversize query", result.isError);
        String text = getResultText(result);
        assertTrue("Error message should mention maximum length: " + text,
                text.toLowerCase().contains("maximum") || text.toLowerCase().contains("length"));
    }

    /**
     * codegraph_callers 的超大 symbol 参数必须在访问数据库前被拒绝。
     */
    @Test
    public void testRejectsOversizeSymbolOnCodegraphCallers() {
        CallersTool tool = new CallersTool(db, queries, traverser, graphQueryMgr, config);
        String huge = createHugeString('c', 15_000);
        Map<String, Object> args = new HashMap<>();
        args.put("symbol", huge);
        ToolCallResult result = tool.execute(args);
        assertTrue("Expected error for oversize symbol", result.isError);
        String text = getResultText(result);
        assertTrue("Error message should mention maximum length: " + text,
                text.toLowerCase().contains("maximum") || text.toLowerCase().contains("length"));
    }

    /**
     * codegraph_impact 的超大 symbol 参数必须在访问数据库前被拒绝。
     */
    @Test
    public void testRejectsOversizeSymbolOnCodegraphImpact() {
        ImpactTool tool = new ImpactTool(db, queries, traverser, graphQueryMgr, config);
        String huge = createHugeString('d', 11_000);
        Map<String, Object> args = new HashMap<>();
        args.put("symbol", huge);
        ToolCallResult result = tool.execute(args);
        assertTrue("Expected error for oversize symbol", result.isError);
        String text = getResultText(result);
        assertTrue("Error message should mention maximum length: " + text,
                text.toLowerCase().contains("maximum") || text.toLowerCase().contains("length"));
    }

    /**
     * 超大的 projectPath 参数必须被拒绝。
     * 覆盖攻击者发送超长路径的路径遍历 DoS 向量。
     */
    @Test
    public void testRejectsOversizeProjectPath() {
        SearchTool tool = new SearchTool(db, queries, traverser, graphQueryMgr, config);
        String hugePath = "/tmp/" + createHugeString('x', 5_000);
        Map<String, Object> args = new HashMap<>();
        args.put("query", "alpha");
        args.put("projectPath", hugePath);
        ToolCallResult result = tool.execute(args);
        assertTrue("Expected error for oversize projectPath", result.isError);
        String text = getResultText(result);
        assertTrue("Error message should mention projectPath: " + text,
                text.toLowerCase().contains("project"));
    }

    /**
     * codegraph_files 的超大 path 过滤器必须被拒绝。
     */
    @Test
    public void testRejectsOversizePathFilterOnCodegraphFiles() {
        FilesTool tool = new FilesTool(db, queries, traverser, graphQueryMgr, config);
        String hugePath = "src/" + createHugeString('y', 5_000);
        Map<String, Object> args = new HashMap<>();
        args.put("path", hugePath);
        ToolCallResult result = tool.execute(args);
        assertTrue("Expected error for oversize path", result.isError);
        String text = getResultText(result);
        assertTrue("Error message should mention path: " + text,
                text.toLowerCase().contains("path"));
    }

    /**
     * codegraph_files 的超大 glob 模式必须被拒绝。
     */
    @Test
    public void testRejectsOversizeGlobPatternOnCodegraphFiles() {
        FilesTool tool = new FilesTool(db, queries, traverser, graphQueryMgr, config);
        String hugePattern = createHugeString('*', 5_000);
        Map<String, Object> args = new HashMap<>();
        args.put("pattern", hugePattern);
        ToolCallResult result = tool.execute(args);
        assertTrue("Expected error for oversize pattern", result.isError);
        String text = getResultText(result);
        assertTrue("Error message should mention pattern: " + text,
                text.toLowerCase().contains("pattern"));
    }

    /**
     * 创建由指定字符填充的指定长度字符串。
     * 用于生成长度限制测试的超大输入。
     */
    private String createHugeString(char c, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 从 ToolCallResult 中提取文本内容，用于断言消息。
     * 返回第一个内容项的文本，如果没有则返回空字符串。
     */
    private String getResultText(ToolCallResult result) {
        if (result.content != null && !result.content.isEmpty()) {
            ContentItem item = result.content.get(0);
            return item != null ? item.text : "";
        }
        return "";
    }
}
