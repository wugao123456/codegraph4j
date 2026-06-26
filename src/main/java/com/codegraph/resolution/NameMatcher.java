package com.codegraph.resolution;

import com.codegraph.core.Node;
import com.codegraph.core.types.EdgeKind;
import com.codegraph.core.types.NodeKind;
import com.codegraph.db.QueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 名称匹配器 — 通过多策略管线按名称匹配符号。
 *
 * 策略管线（按置信度降序）：
 * 1. 全限定名匹配（qualified-name）
 * 2. 精确名匹配（exact-match）
 * 3. 模糊名匹配（fuzzy）
 *
 * 对应 codegraph 项目中的 name-matcher.ts。
 */
public class NameMatcher {

    private static final Logger logger = LoggerFactory.getLogger(NameMatcher.class);

    private final QueryBuilder queries;

    public NameMatcher(QueryBuilder queries) {
        this.queries = queries;
    }

    /**
     * 匹配引用名称到目标节点。
     *
     * @param refName      引用名称
     * @param sourceFile   源文件路径
     * @param edgeKind     边类型（用于过滤）
     * @return 最佳匹配的节点 ID，未找到返回 null
     */
    public String match(String refName, String sourceFile, EdgeKind edgeKind) {
        if (refName == null || refName.isEmpty()) {
            return null;
        }

        // 策略 1：全限定名匹配
        String qnameResult = matchByQualifiedName(refName);
        if (qnameResult != null) return qnameResult;

        // 策略 2：精确名匹配
        String exactResult = matchByExactName(refName, sourceFile);
        if (exactResult != null) return exactResult;

        // 策略 3：模糊匹配
        String fuzzyResult = matchFuzzy(refName, sourceFile);
        if (fuzzyResult != null) return fuzzyResult;

        return null;
    }

    /**
     * 按全限定名精确匹配。
     */
    private String matchByQualifiedName(String refName) {
        try {
            List<Node> nodes = queries.getNodesByQualifiedName(refName);
            if (!nodes.isEmpty()) {
                logger.debug("[NameMatcher] qualified-name 匹配: {} -> {}", refName, nodes.get(0).getId());
                return nodes.get(0).getId();
            }
        } catch (SQLException e) {
            logger.warn("[NameMatcher] qualified-name 查询失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 按名称精确匹配，多候选时选择最佳（同文件优先）。
     */
    private String matchByExactName(String refName, String sourceFile) {
        try {
            List<Node> nodes = queries.getNodesByName(refName);
            if (nodes.isEmpty()) {
                return null;
            }

            if (nodes.size() == 1) {
                logger.debug("[NameMatcher] exact-match (唯一): {} -> {}", refName, nodes.get(0).getId());
                return nodes.get(0).getId();
            }

            // 多候选：优先选择同一文件中的
            Node best = findBestMatch(nodes, sourceFile);
            if (best != null) {
                logger.debug("[NameMatcher] exact-match (最佳): {} -> {}", refName, best.getId());
                return best.getId();
            }
        } catch (SQLException e) {
            logger.warn("[NameMatcher] exact-match 查询失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 模糊匹配：按小写名称匹配。
     */
    private String matchFuzzy(String refName, String sourceFile) {
        try {
            String lowerName = refName.toLowerCase();
            List<Node> nodes = queries.getNodesByLowerName(lowerName);
            if (!nodes.isEmpty()) {
                Node best = findBestMatch(nodes, sourceFile);
                if (best != null) {
                    logger.debug("[NameMatcher] fuzzy-match: {} -> {}", refName, best.getId());
                    return best.getId();
                }
            }
        } catch (SQLException e) {
            logger.warn("[NameMatcher] fuzzy-match 查询失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 从多候选中选择最佳匹配。
     * 评分规则：同文件 +100，目录接近加分，同类型加分。
     */
    private Node findBestMatch(List<Node> candidates, String sourceFile) {
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);

        String sourceDir = getDirectory(sourceFile);

        return candidates.stream()
            .max(Comparator.comparingInt(candidate -> scoreCandidate(candidate, sourceFile, sourceDir)))
            .orElse(candidates.get(0));
    }

    private int scoreCandidate(Node candidate, String sourceFile, String sourceDir) {
        int score = 0;

        // 同文件 +100
        if (sourceFile.equals(candidate.getFilePath())) {
            score += 100;
        }

        // 同目录 +30
        String candDir = getDirectory(candidate.getFilePath());
        if (candDir.equals(sourceDir)) {
            score += 30;
        }

        // 目录接近度（每共享一层 +10）
        score += computePathProximity(sourceDir, candDir);

        // 导出 +10
        if (candidate.isExported()) {
            score += 10;
        }

        return score;
    }

    private String getDirectory(String filePath) {
        if (filePath == null) return "";
        int lastSep = filePath.lastIndexOf('/');
        return lastSep >= 0 ? filePath.substring(0, lastSep) : "";
    }

    private int computePathProximity(String path1, String path2) {
        if (path1.isEmpty() || path2.isEmpty()) return 0;
        String[] parts1 = path1.split("/");
        String[] parts2 = path2.split("/");
        int common = 0;
        for (int i = 0; i < Math.min(parts1.length, parts2.length); i++) {
            if (parts1[i].equals(parts2[i])) common++;
            else break;
        }
        return Math.min(common * 10, 80);
    }
}
