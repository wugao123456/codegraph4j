package com.codegraph.sync;

import com.codegraph.core.Edge;
import com.codegraph.core.FileRecord;
import com.codegraph.core.Node;
import com.codegraph.core.types.NodeKind;
import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;
import com.codegraph.extraction.CodeParser;
import com.codegraph.extraction.ParseResult;
import com.codegraph.extraction.ParserFactory;
import com.codegraph.resolution.ResolutionContext;
import com.codegraph.resolution.frameworks.FrameworkExtractionResult;
import com.codegraph.resolution.frameworks.FrameworkRegistry;
import com.codegraph.resolution.frameworks.FrameworkResolver;
import com.codegraph.utils.FileFilterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 同步编排器 — 执行增量同步的核心引擎。
 * 从 IndexCommand 提炼出可复用的 sync 逻辑，同时供 SyncCommand 和 FileWatcher 使用。
 */
public class SyncOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(SyncOrchestrator.class);

    private final ParserFactory parserFactory;
    private final List<String> excludePatterns;

    public SyncOrchestrator() {
        this.parserFactory = new ParserFactory();
        this.excludePatterns = new ArrayList<>();
        this.excludePatterns.add(".git");
        this.excludePatterns.add(".codegraph");
        this.excludePatterns.add(".idea");
        this.excludePatterns.add(".vscode");
        this.excludePatterns.add(".trae");
        this.excludePatterns.add("node_modules");
        this.excludePatterns.add("target");
        this.excludePatterns.add("build");
        this.excludePatterns.add("dist");
        this.excludePatterns.add(".DS_Store");
        this.excludePatterns.add(".iml");
    }

    /**
     * 执行增量同步。对账文件系统与数据库，仅处理变更的文件。
     *
     * @param projectRoot  项目根路径
     * @param queryBuilder 数据库查询构建器
     * @param force        是否强制全量索引
     * @param onProgress   进度回调
     * @return 同步结果
     */
    public SyncResult sync(Path projectRoot, QueryBuilder queryBuilder, boolean force,
                           java.util.function.Consumer<String> onProgress) throws Exception {
        long startTime = System.currentTimeMillis();
        SyncResult result = new SyncResult();

        // 1. 扫描项目目录获取当前源文件列表
        List<Path> currentFiles = findCodeFiles(projectRoot);
        result.setFilesChecked(currentFiles.size());

        // 2. 从数据库获取已跟踪文件
        List<String> trackedFiles = queryBuilder.getAllFiles();
        Set<String> trackedFileSet = new HashSet<>(trackedFiles);
        Set<String> currentFileSet = currentFiles.stream()
                .map(p -> p.toString())
                .collect(Collectors.toSet());

        // 3. 对账：已删除文件
        Set<String> deletedFiles = new HashSet<>(trackedFileSet);
        deletedFiles.removeAll(currentFileSet);
        for (String deletedPath : deletedFiles) {
            int removed = queryBuilder.deleteNodesByFile(deletedPath);
            result.setFilesRemoved(result.getFilesRemoved() + 1);
            result.setNodesUpdated(result.getNodesUpdated() + removed);
            result.getChangedFilePaths().add(deletedPath);
            logger.debug("已删除文件: {}", deletedPath);
        }

        // 4. 找出需要处理的新增/修改文件
        List<Path> toProcess = new ArrayList<>();
        DatabaseConnection db = queryBuilder.getDb();

        for (Path filePath : currentFiles) {
            String filePathStr = filePath.toString();

            if (!force && trackedFileSet.contains(filePathStr)) {
                // 用 (size, mtime) 快速预过滤
                FileRecord existing = queryBuilder.getFile(filePathStr);
                if (existing != null) {
                    try {
                        long currentSize = Files.size(filePath);
                        long currentMtime = Files.getLastModifiedTime(filePath).toMillis();
                        if (currentSize == existing.getSize() && currentMtime == existing.getMtime()) {
                            // size + mtime 都没变，跳过
                            continue;
                        }
                        // size 或 mtime 变了，计算 hash 确认
                        String currentHash = calculateHash(filePath);
                        if (currentHash.equals(existing.getHash())) {
                            // 内容没变，仅更新 mtime
                            existing.setMtime(currentMtime);
                            queryBuilder.insertOrUpdateFile(existing);
                            continue;
                        }
                    } catch (IOException e) {
                        // 读取失败，标记为需要处理
                    }
                }
            }

            toProcess.add(filePath);
        }

        // 5. 处理变更的文件
        int batchSize = 100;
        for (int batchStart = 0; batchStart < toProcess.size(); batchStart += batchSize) {
            int batchEnd = Math.min(batchStart + batchSize, toProcess.size());
            List<Path> batch = toProcess.subList(batchStart, batchEnd);

            for (Path filePath : batch) {
                String filePathStr = filePath.toString();

                try {
                    boolean isNew = !trackedFileSet.contains(filePathStr);

                    // 计算 hash
                    String contentHash = calculateHash(filePath);

                    // 删除旧数据
                    if (!isNew) {
                        queryBuilder.deleteNodesByFile(filePathStr);
                    }

                    // 解析文件
                    CodeParser parser = parserFactory.getParser(filePath);
                    if (parser == null) {
                        continue;
                    }

                    String content = new String(Files.readAllBytes(filePath));
                    ParseResult parseResult = parser.parseWithEdges(filePath, content);

                    // 保存节点
                    for (Node node : parseResult.getNodes()) {
                        queryBuilder.insertNode(node);
                        result.setNodesUpdated(result.getNodesUpdated() + 1);
                    }

                    // 保存边
                    for (Edge edge : parseResult.getEdges()) {
                        try {
                            if (!queryBuilder.insertEdge(edge)) {
                                logger.debug("跳过边 (target 不存在): {} -> {}",
                                        edge.getSource(), edge.getTarget());
                            }
                        } catch (SQLException e) {
                            logger.debug("跳过边 (异常): {} -> {}, {}",
                                    edge.getSource(), edge.getTarget(), e.getMessage());
                        }
                    }

                    // 保存未解析引用（供 resolution 阶段处理）
                    if (!parseResult.getUnresolvedRefs().isEmpty()) {
                        queryBuilder.insertUnresolvedRefs(parseResult.getUnresolvedRefs());
                    }

                    // 更新文件记录
                    FileRecord fileRecord = new FileRecord();
                    fileRecord.setFilePath(filePathStr);
                    fileRecord.setHash(contentHash);
                    fileRecord.setLanguage(parser.getLanguage());
                    fileRecord.setSize(Files.size(filePath));
                    fileRecord.setMtime(Files.getLastModifiedTime(filePath).toMillis());
                    fileRecord.setIndexedAt(System.currentTimeMillis());
                    queryBuilder.insertOrUpdateFile(fileRecord);

                    if (isNew) {
                        result.setFilesAdded(result.getFilesAdded() + 1);
                    } else {
                        result.setFilesModified(result.getFilesModified() + 1);
                    }
                    result.getChangedFilePaths().add(filePathStr);

                    if (onProgress != null) {
                        onProgress.accept(filePath.getFileName().toString());
                    }

                } catch (Exception e) {
                    logger.error("处理文件失败: {} — {}", filePathStr, e.getMessage());
                    db.rollback();
                }
            }

            // 每个批次提交一次事务
            db.commit();
        }

        // 6. 如果有文件变更，执行框架提取
        if (result.getFilesChanged() > 0) {
            runFrameworkExtraction(projectRoot, queryBuilder, currentFiles, result);
            db.commit();
        }

        // 7. Resolution 阶段：解析跨文件引用
        runResolution(projectRoot, queryBuilder);
        db.commit();

        result.setDurationMs(System.currentTimeMillis() - startTime);
        return result;
    }

    /**
     * 执行引用解析阶段。
     */
    private void runResolution(Path projectRoot, QueryBuilder queryBuilder) {
        try {
            com.codegraph.resolution.ReferenceResolver resolver =
                    new com.codegraph.resolution.ReferenceResolver(queryBuilder, projectRoot.toString());
            resolver.initialize();
            resolver.runPostExtract();
            resolver.resolveUnresolvedRefs();
        } catch (Exception e) {
            logger.error("Resolution 阶段失败: {}", e.getMessage());
        }
    }

    /**
     * 执行框架提取阶段。
     */
    private void runFrameworkExtraction(Path projectRoot, QueryBuilder queryBuilder,
                                        List<Path> codeFiles, SyncResult result) {
        try {
            ResolutionContext ctx = new ResolutionContext(queryBuilder, projectRoot.toString());
            FrameworkRegistry registry = new FrameworkRegistry();
            registry.registerDefaults();

            List<FrameworkResolver> detectedFrameworks = registry.detectFrameworks(ctx);
            if (detectedFrameworks.isEmpty()) {
                return;
            }

            logger.info("执行框架提取: {}",
                    detectedFrameworks.stream().map(FrameworkResolver::getName).collect(Collectors.toList()));

            for (FrameworkResolver fw : detectedFrameworks) {
                for (Path filePath : codeFiles) {
                    CodeParser parser = parserFactory.getParser(filePath);
                    if (parser == null) continue;

                    List<com.codegraph.core.types.Language> fwLangs = fw.getLanguages();
                    if (fwLangs != null && !fwLangs.isEmpty() &&
                            !fwLangs.contains(parser.getLanguage())) {
                        continue;
                    }

                    try {
                        String content = new String(Files.readAllBytes(filePath));
                        FrameworkExtractionResult fwResult = fw.extract(
                                filePath.toString(), content, ctx);

                        if (fwResult.getNodes().isEmpty() && fwResult.getReferences().isEmpty()) {
                            continue;
                        }

                        for (Node node : fwResult.getNodes()) {
                            queryBuilder.insertNode(node);
                            result.setNodesUpdated(result.getNodesUpdated() + 1);
                        }

                        for (com.codegraph.resolution.frameworks.UnresolvedRef ref : fwResult.getReferences()) {
                            String targetId = fw.resolve(
                                    ref.getReferenceName(), ref.getReferenceKind(),
                                    ref.getFilePath(), ctx);
                            if (targetId != null) {
                                Edge edge = new Edge();
                                edge.setSource(ref.getFromNodeId());
                                edge.setTarget(targetId);
                                edge.setKind(com.codegraph.core.types.EdgeKind.REFERENCES);
                                edge.setLine(ref.getLine());
                                edge.setColumn(ref.getColumn());
                                edge.setProvenance("framework:" + fw.getName());
                                Map<String, Object> fwMeta = new HashMap<>();
                                fwMeta.put("provenance", "framework:" + fw.getName());
                                edge.setMetadata(fwMeta);
                                try {
                                    if (!queryBuilder.insertEdge(edge)) {
                                        logger.debug("跳过框架边 (target 不存在): {} -> {}",
                                                edge.getSource(), edge.getTarget());
                                    }
                                } catch (SQLException e) {
                                    logger.debug("跳过框架边: {}", e.getMessage());
                                }
                            }
                        }
                    } catch (IOException e) {
                        logger.warn("框架提取失败: {} — {}", filePath.getFileName(), e.getMessage());
                    }
                }

                // 框架后处理
                List<Node> postNodes = fw.postExtract(ctx);
                for (Node node : postNodes) {
                    try {
                        queryBuilder.insertNode(node);
                        result.setNodesUpdated(result.getNodesUpdated() + 1);
                    } catch (SQLException e) {
                        logger.warn("后处理节点插入失败: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("框架提取阶段失败: {}", e.getMessage());
        }
    }

    /**
     * 获取自上次索引以来变更的文件列表（Git 快速路径 + 文件系统回退）。
     */
    public List<String> getChangedFiles(Path projectRoot, QueryBuilder queryBuilder) throws Exception {
        // 尝试 Git 快速路径
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "status", "--porcelain", "--no-renames");
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            byte[] bytes = readAllBytes(process.getInputStream());
            String output = new String(bytes);
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                List<String> changedFiles = new ArrayList<>();
                for (String line : output.split("\n")) {
                    line = line.trim();
                    if (line.length() < 4) continue;
                    // git status --porcelain 格式: "XY filename"
                    String filePath = line.substring(3).trim();
                    if (FileFilterUtils.isSourceFile(filePath)) {
                        changedFiles.add(filePath);
                    }
                }
                if (!changedFiles.isEmpty()) {
                    return changedFiles;
                }
            }
        } catch (Exception e) {
            logger.debug("Git 快速路径失败，回退到文件系统扫描: {}", e.getMessage());
        }

        // 文件系统回退：扫描全部文件，用 hash 比较
        List<String> changed = new ArrayList<>();
        List<Path> currentFiles = findCodeFiles(projectRoot);

        for (Path filePath : currentFiles) {
            String filePathStr = filePath.toString();
            FileRecord existing = queryBuilder.getFile(filePathStr);
            if (existing == null) {
                changed.add(filePathStr);
            } else {
                try {
                    String currentHash = calculateHash(filePath);
                    if (!currentHash.equals(existing.getHash())) {
                        changed.add(filePathStr);
                    }
                } catch (IOException e) {
                    changed.add(filePathStr);
                }
            }
        }

        return changed;
    }

    /**
     * 扫描项目目录中的代码文件。
     */
    public List<Path> findCodeFiles(Path projectPath) throws IOException {
        return Files.walk(projectPath)
                .filter(Files::isRegularFile)
                .filter(p -> FileFilterUtils.isSourceFile(p.toString()))
                .filter(this::isNotExcluded)
                .collect(Collectors.toList());
    }

    /**
     * 判断路径是否未被排除。
     */
    public boolean isNotExcluded(Path path) {
        String pathStr = path.toString();
        for (String pattern : excludePatterns) {
            if (pathStr.contains(pattern)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 计算文件的 SHA-256 hash。
     */
    public String calculateHash(Path filePath) throws IOException {
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

    public List<String> getExcludePatterns() {
        return excludePatterns;
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[4096];
        int nRead;
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }
}
