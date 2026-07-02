package com.codegraph.context;

import com.codegraph.core.Node;
import com.codegraph.db.QueryBuilder;
import com.codegraph.graph.GraphTraverser;
import com.codegraph.graph.GraphTraverser.CallerInfo;

import java.util.*;

/**
 * 爆炸半径分析段落构建器。
 * 对标 codegraph mcp/tools.ts 的 buildBlastRadiusSection()。
 *
 * <p>分析查询命中的入口符号——即直接回答用户问题的核心定义——
 * 哪些其他代码调用了它们（需要更新/验证）。
 * 帮助 agent 在修改前评估影响范围。
 */
public class BlastRadiusBuilder {

    private static final int ROOT_CAP = 5;   // 最多展示 5 个入口符号
    private static final int FILE_CAP = 4;   // 每个符号最多列出 4 个调用文件
    private static final Set<String> MEANINGFUL_KINDS = new HashSet<>(Arrays.asList(
        "function", "method", "class", "interface", "struct", "trait", "protocol",
        "enum", "type_alias", "component", "constant", "variable", "property", "field"
    ));

    /**
     * 构建爆炸半径段落。
     * @param subgraph 已搜索到的子图
     * @param queries 数据库查询
     * @param traverser 图遍历器
     * @return 段落字符串，无内容时返回空字符串
     */
    public String build(Subgraph subgraph, QueryBuilder queries, GraphTraverser traverser) {
        StringBuilder sb = new StringBuilder();

        // 过滤有意义的入口符号
        List<Node> roots = new ArrayList<>();
        for (String rootId : subgraph.roots) {
            Node n = subgraph.nodes.get(rootId);
            if (n != null && MEANINGFUL_KINDS.contains(n.getKind().getValue())) {
                roots.add(n);
                if (roots.size() >= ROOT_CAP) break;
            }
        }
        if (roots.isEmpty()) return "";

        List<String> entries = new ArrayList<>();
        for (Node root : roots) {
            List<CallerInfo> callers = traverser.getCallers(root.getId(), 1);

            // 去重
            Set<String> seen = new HashSet<>();
            List<Node> uniq = new ArrayList<>();
            for (CallerInfo c : callers) {
                if (c.node != null && !seen.contains(c.node.getId())) {
                    seen.add(c.node.getId());
                    uniq.add(c.node);
                }
            }
            if (uniq.isEmpty()) continue;

            // 分类：调用文件 vs 测试文件
            List<String> callerFiles = new ArrayList<>();
            for (Node caller : uniq) {
                String fp = normalizePath(caller.getFilePath());
                if (fp != null) callerFiles.add(fp);
            }
            List<String> testFiles = filterTestFiles(callerFiles);
            List<String> nonTestFiles = new ArrayList<>();
            for (String f : callerFiles) {
                if (!isTestFile(f)) nonTestFiles.add(f);
            }

            // 截断非测试文件列表
            String shownNonTest = "";
            String moreNonTest = "";
            if (!nonTestFiles.isEmpty()) {
                List<String> shown = nonTestFiles.subList(0, Math.min(FILE_CAP, nonTestFiles.size()));
                shownNonTest = join(shown, ", ");
                if (nonTestFiles.size() > FILE_CAP) {
                    moreNonTest = " +" + (nonTestFiles.size() - FILE_CAP) + " more";
                }
            }

            String shownTests = "";
            String moreTests = "";
            if (!testFiles.isEmpty()) {
                List<String> shown = testFiles.subList(0, Math.min(FILE_CAP, testFiles.size()));
                shownTests = "; tests: " + join(shown, ", ");
                if (testFiles.size() > FILE_CAP) {
                    moreTests = " +" + (testFiles.size() - FILE_CAP);
                }
            } else {
                shownTests = "; \u26a0 no covering tests found";
            }

            String callerCount = uniq.size() == 1 ? "1 caller" : uniq.size() + " callers";
            String location = relPath(root.getFilePath()) + ":" + root.getStartLine();
            String line = String.format("- `%s` (%s) \u2014 %s in %s%s%s%s",
                root.getName(),
                location,
                callerCount,
                shownNonTest.isEmpty() ? "(no callers in project)" : shownNonTest,
                moreNonTest,
                shownTests,
                moreTests);
            entries.add(line);
        }

        if (entries.isEmpty()) return "";

        sb.append("**Blast radius \u2014 what depends on these (update/verify before editing)**\n\n");
        for (String e : entries) {
            sb.append(e).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    private static String normalizePath(String p) {
        return p != null ? p.replace("\\", "/") : null;
    }

    private static String relPath(String p) {
        if (p == null) return "";
        int idx = p.lastIndexOf('/');
        return idx >= 0 ? p.substring(idx + 1) : p;
    }

    private static boolean isTestFile(String path) {
        if (path == null) return false;
        String lp = path.toLowerCase();
        return lp.contains("/tests/") || lp.contains("/spec/") ||
               lp.contains("/__tests__/") || lp.contains("/test/") ||
               lp.contains("_test.") || lp.contains("_spec.") ||
               lp.endsWith(".test.ts") || lp.endsWith(".spec.ts") ||
               lp.endsWith(".test.tsx") || lp.endsWith(".spec.tsx") ||
               lp.endsWith(".test.js") || lp.endsWith(".spec.js");
    }

    private static List<String> filterTestFiles(List<String> files) {
        List<String> result = new ArrayList<>();
        for (String f : files) {
            if (isTestFile(f)) result.add(f);
        }
        return result;
    }

    private static String join(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append("`").append(items.get(i)).append("`");
        }
        return sb.toString();
    }
}
