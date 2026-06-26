package com.codegraph.cli.commands;

import com.codegraph.core.FileRecord;
import com.codegraph.core.Node;
import com.codegraph.core.Edge;
import com.codegraph.core.types.NodeKind;
import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;
import com.codegraph.db.SchemaManager;
import com.codegraph.parser.CodeParser;
import com.codegraph.parser.ParseResult;
import com.codegraph.parser.ParserFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@CommandLine.Command(
    name = "index",
    description = "Index project files"
)
public class IndexCommand implements Runnable {
    
    private static final Logger logger = LoggerFactory.getLogger(IndexCommand.class);
    
    @CommandLine.Option(names = {"-p", "--project"}, 
        description = "Project root directory", 
        defaultValue = ".")
    private String projectRoot;
    
    @CommandLine.Option(names = {"--force"}, 
        description = "Force re-index all files", 
        defaultValue = "false")
    private boolean force;
    
    @CommandLine.Option(names = {"--watch"}, 
        description = "Watch for file changes", 
        defaultValue = "false")
    private boolean watch;
    
    private ParserFactory parserFactory;
    private List<String> excludePatterns;
    
    public IndexCommand() {
        this.parserFactory = new ParserFactory();
        this.excludePatterns = new ArrayList<>();
        this.excludePatterns.add(".git");
        this.excludePatterns.add(".codegraph");
        this.excludePatterns.add("node_modules");
        this.excludePatterns.add("target");
        this.excludePatterns.add("build");
        this.excludePatterns.add("dist");
    }
    
    @Override
    public void run() {
        logger.info("========== 开始索引流程 ==========");
        logger.info("项目路径: {}", projectRoot);
        logger.info("强制重新索引: {}, 监听模式: {}", force, watch);
        
        Path projectPath = Paths.get(projectRoot).toAbsolutePath();
        File dbFile = new File(projectPath.toFile(), ".codegraph/codegraph4j.db");
        
        logger.info("数据库文件: {}", dbFile.getAbsolutePath());
        
        if (!dbFile.exists()) {
            System.err.println("Error: CodeGraph not initialized. Run 'init' first.");
            logger.error("数据库文件不存在: {}", dbFile.getAbsolutePath());
            return;
        }
        
        System.out.println("Indexing project: " + projectPath);
        
        try (DatabaseConnection db = new DatabaseConnection(dbFile.getAbsolutePath())) {
            logger.info("[Step 1] 打开数据库连接");
            db.open();
            QueryBuilder queryBuilder = new QueryBuilder(db);
            
            // 查找所有代码文件
            logger.info("[Step 2] 扫描代码文件");
            List<Path> codeFiles = findCodeFiles(projectPath);
            logger.info("发现 {} 个代码文件", codeFiles.size());
            System.out.println("Found " + codeFiles.size() + " code files");
            
            int indexedFiles = 0;
            int skippedFiles = 0;
            int totalNodes = 0;
            int totalEdges = 0;
            
            for (int i = 0; i < codeFiles.size(); i++) {
                Path filePath = codeFiles.get(i);
                logger.info("[Step 3.{}/{}] 处理文件: {}", i + 1, codeFiles.size(), filePath.getFileName());
                
                try {
                    // 计算文件 hash
                    logger.debug("  计算文件 hash...");
                    String contentHash = calculateHash(filePath);
                    logger.debug("  文件 hash: {}", contentHash.substring(0, Math.min(16, contentHash.length())) + "...");
                    
                    // 检查是否需要重新索引
                    FileRecord existingFile = queryBuilder.getFile(filePath.toString());
                    
                    if (!force && existingFile != null && existingFile.getHash().equals(contentHash)) {
                        logger.info("  [跳过] 文件未发生变化");
                        skippedFiles++;
                        continue;
                    }
                    
                    if (existingFile != null) {
                        logger.info("  [更新] 重新索引已变化的文件");
                    } else {
                        logger.info("  [新增] 新文件");
                    }
                    
                    // 解析文件
                    CodeParser parser = parserFactory.getParser(filePath);
                    if (parser == null) {
                        logger.warn("  [跳过] 没有支持的解析器");
                        continue;
                    }
                    logger.info("  使用解析器: {} ({})", parser.getClass().getSimpleName(), parser.getLanguage());
                    
                    // 读取文件内容
                    logger.debug("  读取文件内容...");
                    String content = new String(Files.readAllBytes(filePath));
                    logger.debug("  文件大小: {} bytes", content.length());
                    
                    // 解析文件
                    logger.info("  开始解析代码符号...");
                    long parseStart = System.currentTimeMillis();
                    ParseResult parseResult = parser.parseWithEdges(filePath, content);
                    List<Node> nodes = parseResult.getNodes();
                    List<Edge> edges = parseResult.getEdges();
                    long parseTime = System.currentTimeMillis() - parseStart;
                    logger.info("  解析完成: 耗时 {}ms, 提取 {} 个符号, {} 条边",
                        parseTime, nodes.size(), edges.size());
                    
                    // 删除旧数据
                    if (existingFile != null) {
                        logger.debug("  删除旧的节点和边...");
                        int deletedNodes = queryBuilder.deleteNodesByFile(filePath.toString());
                        logger.debug("  删除 {} 个旧节点, {} 条旧边",
                            deletedNodes, edges.size()); // edges will be deleted via cascade or here
                    }
                    
                    // 保存节点
                    logger.debug("  保存 {} 个节点到数据库...", nodes.size());
                    for (Node node : nodes) {
                        queryBuilder.insertNode(node);
                        totalNodes++;
                    }
                    logger.info("  节点保存完成");
                    
                    // 保存边
                    logger.debug("  保存 {} 条边到数据库...", edges.size());
                    for (Edge edge : edges) {
                        queryBuilder.insertEdge(edge);
                        totalEdges++;
                    }
                    logger.info("  边保存完成");
                    
                    // 更新文件记录
                    FileRecord fileRecord = new FileRecord();
                    fileRecord.setFilePath(filePath.toString());
                    fileRecord.setHash(contentHash);
                    fileRecord.setLanguage(parser.getLanguage());
                    fileRecord.setSize(Files.size(filePath));
                    fileRecord.setMtime(Files.getLastModifiedTime(filePath).toMillis());
                    fileRecord.setIndexedAt(System.currentTimeMillis());
                    
                    queryBuilder.insertOrUpdateFile(fileRecord);
                    indexedFiles++;
                    
                    // 提交当前文件的事务
                    db.commit();
                    logger.debug("  事务已提交");
                    
                    // 按类型统计
                    long classCount = nodes.stream().filter(n -> n.getKind() == NodeKind.CLASS).count();
                    long interfaceCount = nodes.stream().filter(n -> n.getKind() == NodeKind.INTERFACE).count();
                    long methodCount = nodes.stream().filter(n -> n.getKind() == NodeKind.METHOD).count();
                    long fieldCount = nodes.stream().filter(n -> n.getKind() == NodeKind.FIELD).count();
                    
                    System.out.println("  Indexed: " + filePath.getFileName() + " (classes:" + classCount + ", methods:" + methodCount + ", fields:" + fieldCount + ")");
                    
                } catch (IOException e) {
                    logger.error("  [错误] 处理文件失败: {}", e.getMessage(), e);
                    System.err.println("  Error: " + filePath + " - " + e.getMessage());
                    db.rollback();
                }
            }
            
            System.out.println();
            System.out.println("========== 索引完成 ==========");
            System.out.println("✓ Indexing completed");
            System.out.println("  处理文件数: " + codeFiles.size());
            System.out.println("  新增/更新: " + indexedFiles);
            System.out.println("  跳过(未变化): " + skippedFiles);
            System.out.println("  总节点数: " + totalNodes);
            System.out.println("  总边数: " + totalEdges);
            System.out.println("============================");
            
            logger.info("========== 索引完成 ==========");
            logger.info("处理文件数: {}, 新增/更新: {}, 跳过: {}", 
                codeFiles.size(), indexedFiles, skippedFiles);
            logger.info("总节点数: {}, 总边数: {}", totalNodes, totalEdges);
            logger.info("============================");
            
            if (watch) {
                System.out.println();
                System.out.println("Watching for file changes...");
                watchFiles(projectPath, db);
            }
            
        } catch (Exception e) {
            logger.error("索引流程失败", e);
            System.err.println("Error: " + e.getMessage());
        }
    }
    
    private List<Path> findCodeFiles(Path projectPath) throws IOException {
        return Files.walk(projectPath)
            .filter(Files::isRegularFile)
            .filter(this::isCodeFile)
            .filter(this::isNotExcluded)
            .collect(Collectors.toList());
    }
    
    private boolean isCodeFile(Path path) {
        String fileName = path.toString().toLowerCase();
        return fileName.endsWith(".java") || 
               fileName.endsWith(".js") || 
               fileName.endsWith(".jsx") ||
               fileName.endsWith(".ts") ||
               fileName.endsWith(".tsx") ||
               fileName.endsWith(".mjs");
    }
    
    private boolean isNotExcluded(Path path) {
        String pathStr = path.toString();
        for (String pattern : excludePatterns) {
            if (pathStr.contains(pattern)) {
                return false;
            }
        }
        return true;
    }
    
    private String calculateHash(Path filePath) throws IOException {
        byte[] bytes = Files.readAllBytes(filePath);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(new String(bytes).hashCode());
        }
    }
    
    private void watchFiles(Path projectPath, com.codegraph.db.DatabaseConnection db) {
        // 简单的轮询监听实现
        // 实际项目中可以使用 WatchService
        System.out.println("File watching is not fully implemented yet.");
        System.out.println("Use --force to re-index when files change.");
    }
}