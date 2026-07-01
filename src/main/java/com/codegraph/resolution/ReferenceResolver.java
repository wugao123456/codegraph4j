package com.codegraph.resolution;

import com.codegraph.core.Edge;
import com.codegraph.core.Node;
import com.codegraph.core.types.EdgeKind;
import com.codegraph.db.QueryBuilder;
import com.codegraph.resolution.frameworks.FrameworkRegistry;
import com.codegraph.resolution.frameworks.FrameworkResolver;
import com.codegraph.resolution.frameworks.UnresolvedRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.*;

/**
 * 引用解析器 — 在 extraction 完成后统一解析跨文件引用。
 *
 * 策略管线（按优先级）：
 * 1. 框架解析器（FrameworkResolver.resolve）
 * 2. Import 解析（ImportResolver.resolveViaImport）
 * 3. 名称匹配（NameMatcher.match）
 *    3a. 全限定名匹配
 *    3b. 精确名匹配（同文件优先）
 *    3c. 模糊名匹配（小写名）
 *
 * 对应 codegraph 项目中的 resolution/index.ts。
 */
public class ReferenceResolver {

    private static final Logger logger = LoggerFactory.getLogger(ReferenceResolver.class);

    private static final int BATCH_SIZE = 5000;

    private final QueryBuilder queries;
    private final ImportResolver importResolver;
    private final NameMatcher nameMatcher;
    private final ResolutionContext ctx;
    private final FrameworkRegistry frameworkRegistry;
    private final List<FrameworkResolver> activeFrameworks;

    public ReferenceResolver(QueryBuilder queries, String projectRoot) {
        this.queries = queries;
        this.importResolver = new ImportResolver(queries);
        this.nameMatcher = new NameMatcher(queries);
        this.ctx = new ResolutionContext(queries, projectRoot);
        this.frameworkRegistry = new FrameworkRegistry();
        this.activeFrameworks = new ArrayList<>();
    }

    /**
     * 初始化：检测活跃的框架解析器。
     */
    public void initialize() {
        List<FrameworkResolver> allResolvers = frameworkRegistry.getAll();
        for (FrameworkResolver fw : allResolvers) {
            try {
                if (fw.detect(ctx)) {
                    activeFrameworks.add(fw);
                    logger.info("[Resolution] 检测到框架: {}", fw.getName());
                }
            } catch (Exception e) {
                logger.warn("[Resolution] 框架 {} 检测失败: {}", fw.getName(), e.getMessage());
            }
        }
    }

    /**
     * 运行框架的 postExtract（跨文件后处理）。
     */
    public void runPostExtract() {
        for (FrameworkResolver fw : activeFrameworks) {
            try {
                List<Node> extraNodes = fw.postExtract(ctx);
                if (!extraNodes.isEmpty()) {
                    for (Node node : extraNodes) {
                        try {
                            queries.insertNode(node);
                        } catch (SQLException e) {
                            logger.debug("[Resolution] 插入框架合成节点失败: {}", e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("[Resolution] 框架 {} postExtract 失败: {}", fw.getName(), e.getMessage());
            }
        }
    }

    /**
     * 批量解析所有未解析引用，生成 edges 并清理。
     */
    public void resolveUnresolvedRefs() {
        try {
            int totalResolved = 0;
            int totalFailed = 0;
            int prevRemaining = Integer.MAX_VALUE;

            while (true) {
                int remaining = queries.countUnresolvedRefs();
                if (remaining == 0) break;

                // 防止无限循环
                if (remaining >= prevRemaining) {
                    logger.warn("[Resolution] 无法继续缩减 unresolved refs (剩余: {})", remaining);
                    break;
                }
                prevRemaining = remaining;

                List<UnresolvedRef> batch = queries.getUnresolvedRefsBatch(0, BATCH_SIZE);
                if (batch.isEmpty()) break;

                List<Edge> resolvedEdges = new ArrayList<>();
                int maxId = 0;

                for (UnresolvedRef ref : batch) {
                    String targetId = resolveOne(ref);
                    if (targetId != null) {
                        Edge edge = createEdge(ref, targetId);
                        resolvedEdges.add(edge);
                        totalResolved++;
                    } else {
                        totalFailed++;
                    }
                }

                // 持久化解析成功的边
                if (!resolvedEdges.isEmpty()) {
                    for (Edge edge : resolvedEdges) {
                        try {
                            queries.insertEdge(edge);
                        } catch (SQLException e) {
                            logger.debug("[Resolution] 插入边失败: {}", e.getMessage());
                        }
                    }
                }

                // 删除已处理的批次
                int maxIdInBatch = batch.stream()
                        .mapToInt(ref -> {
                            try {
                                // use this as a rough estimate; the batch was sequential
                                return batch.size();
                            } catch (Exception e) {
                                return 0;
                            }
                        }).max().orElse(0);

                // 简单方案：删除当前批次对应的所有记录
                // 使用最新 id 作为截止点
                queries.deleteResolvedRefs(batch.size());
            }

            logger.info("[Resolution] 解析完成: 成功 {} 条, 失败 {} 条", totalResolved, totalFailed);
        } catch (SQLException e) {
            logger.error("[Resolution] 批量解析失败: {}", e.getMessage());
        }
    }

    /**
     * 解析单个引用。
     */
    private String resolveOne(UnresolvedRef ref) {
        // 策略 1: 框架解析器
        for (FrameworkResolver fw : activeFrameworks) {
            try {
                String result = fw.resolve(ref.getReferenceName(), ref.getReferenceKind(),
                        ref.getFilePath(), ctx);
                if (result != null) {
                    logger.debug("[Resolution] 框架 {} 解析: {} -> {}", fw.getName(),
                            ref.getReferenceName(), result);
                    return result;
                }
            } catch (Exception e) {
                logger.debug("[Resolution] 框架 {} 解析异常: {}", fw.getName(), e.getMessage());
            }
        }

        // 策略 2: Import 解析
        try {
            EdgeKind edgeKind = toEdgeKind(ref.getReferenceKind());
            String result = importResolver.resolveViaImport(
                    ref.getReferenceName(), ref.getFilePath(), edgeKind);
            if (result != null) {
                logger.debug("[Resolution] import 解析: {} -> {}", ref.getReferenceName(), result);
                return result;
            }
        } catch (Exception e) {
            logger.debug("[Resolution] import 解析异常: {}", e.getMessage());
        }

        // 策略 3: 名称匹配
        try {
            EdgeKind edgeKind = toEdgeKind(ref.getReferenceKind());
            String result = nameMatcher.match(ref.getReferenceName(), ref.getFilePath(), edgeKind);
            if (result != null) {
                logger.debug("[Resolution] 名称匹配: {} -> {}", ref.getReferenceName(), result);
                return result;
            }
        } catch (Exception e) {
            logger.debug("[Resolution] 名称匹配异常: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 将 UnresolvedRef 转换为 Edge（带 metadata）。
     */
    private Edge createEdge(UnresolvedRef ref, String targetNodeId) {
        Edge edge = new Edge();
        edge.setSource(ref.getFromNodeId());
        edge.setTarget(targetNodeId);
        edge.setKind(toEdgeKind(ref.getReferenceKind()));
        edge.setLine(ref.getLine());
        edge.setColumn(ref.getColumn());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("confidence", 0.95);
        metadata.put("resolvedBy", "resolution");
        edge.setMetadata(metadata);

        return edge;
    }

    /**
     * 将 referenceKind 字符串转换为 EdgeKind 枚举。
     */
    private EdgeKind toEdgeKind(String referenceKind) {
        if (referenceKind == null) return EdgeKind.REFERENCES;
        try {
            return EdgeKind.fromValue(referenceKind.toLowerCase());
        } catch (IllegalArgumentException e) {
            // 回退到 REFERENCES
            return EdgeKind.REFERENCES;
        }
    }
}
