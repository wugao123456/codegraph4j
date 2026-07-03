package com.codegraph.resolution;

import com.codegraph.core.Node;
import com.codegraph.core.types.EdgeKind;
import com.codegraph.core.types.NodeKind;
import com.codegraph.db.QueryBuilder;
import com.codegraph.resolution.frameworks.ResolverUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 导入解析器 — 通过 import 语句解析引用。
 *
 * 核心逻辑：
 * 1. 从源文件节点中提取 import 信息
 * 2. 将引用名匹配到导入的全限定名
 * 3. 按全限定名查找目标节点
 *
 * 对应 codegraph 项目中的 import-resolver.ts。
 */
public class ImportResolver {

    private static final Logger logger = LoggerFactory.getLogger(ImportResolver.class);

    private final QueryBuilder queries;

    // Java import 语句正则
    private static final Pattern JAVA_IMPORT_PATTERN = Pattern.compile(
        "^import\\s+(static\\s+)?([a-z_][\\w.]*(?:\\.[A-Z_][\\w.]*)+);?$",
        Pattern.MULTILINE
    );

    public ImportResolver(QueryBuilder queries) {
        this.queries = queries;
    }

    /**
     * 通过 import 解析引用。
     *
     * @param refName     引用名称（简单名，如 "List", "EsPersistService"）
     * @param sourceFile  源文件路径
     * @param edgeKind    边类型
     * @return 目标节点 ID，未解析返回 null
     */
    public String resolveViaImport(String refName, String sourceFile, EdgeKind edgeKind) {
        if (refName == null || refName.isEmpty() || sourceFile == null) {
            return null;
        }

        try {
            // 获取源文件中的 import 节点
            List<Node> sourceNodes = queries.getNodesInFile(sourceFile);

            // 从 import 节点中查找匹配的导入
            List<String> importedClasses = extractImportedClassNames(sourceNodes, refName);
            if (importedClasses.isEmpty()) {
                return null;
            }

            // 按全限定名查找目标节点
            for (String fqn : importedClasses) {
                List<Node> targetNodes = queries.getNodesByQualifiedName(fqn);
                if (!targetNodes.isEmpty()) {
                    logger.debug("[ImportResolver] 通过 import 解析: {} -> {} ({})",
                        refName, targetNodes.get(0).getId(), fqn);
                    return targetNodes.get(0).getId();
                }
            }
        } catch (SQLException e) {
            logger.warn("[ImportResolver] 解析失败: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 从文件的节点列表中提取当前引用对应的导入全限定名。
     */
    private List<String> extractImportedClassNames(List<Node> sourceNodes, String refName) {
        List<String> result = new ArrayList<>();

        for (Node node : sourceNodes) {
            if (node.getKind() == NodeKind.IMPORT) {
                // import 节点的 name 是导入的全限定名（如 "java.util.List"）
                String importName = node.getName();
                if (importName != null) {
                    // 检查简单名是否匹配
                    String simpleName = ResolverUtils.extractSimpleName(importName);
                    if (refName.equals(simpleName)) {
                        result.add(importName);
                    }

                    // 检查通配符导入（如 "java.util.*"）
                    if (importName.endsWith(".*")) {
                        String prefix = importName.substring(0, importName.length() - 2);
                        // 尝试按通常的命名约定查找
                        String guessedFqn = prefix + "." + refName;
                        result.add(guessedFqn);
                    }
                }
            }
        }

        return result;
    }
}
