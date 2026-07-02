package com.codegraph.mcp.tools;

import com.codegraph.context.*;
import com.codegraph.core.Edge;
import com.codegraph.core.Node;
import com.codegraph.core.types.EdgeKind;
import com.codegraph.core.types.NodeKind;
import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;
import com.codegraph.graph.GraphQueryManager;
import com.codegraph.graph.GraphTraverser;
import com.codegraph.graph.GraphTraverser.CalleeInfo;
import com.codegraph.graph.GraphTraverser.CallerInfo;
import com.codegraph.mcp.MCPTransport.ToolCallResult;
import com.codegraph.mcp.MCPTransport.ToolDefinition;
import com.codegraph.utils.MarkdownUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * codegraph_explore — 主要探索工具。
 * 接收自然语言查询，通过 8 步流水线返回最相关的代码段、关系图和爆炸半径分析。
 */
public class ExploreTool extends BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(ExploreTool.class);

    private static final int EXPLORE_LOG_MAX_FILES;
    private static final DateTimeFormatter EXPLORE_LOG_DATE_FORMATTER =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    static {
        int maxFiles = 10;
        String env = System.getenv("CODEGRAPH_EXPLORE_LOG_MAX_FILES");
        if (env != null && !env.isEmpty()) {
            try {
                maxFiles = Integer.parseInt(env.trim());
                if (maxFiles < 1) maxFiles = 1;
            } catch (NumberFormatException ignored) {}
        }
        EXPLORE_LOG_MAX_FILES = maxFiles;
    }

    public ExploreTool(DatabaseConnection db, QueryBuilder queries,
                       GraphTraverser traverser, GraphQueryManager graphQueryMgr,
                       String projectPath) {
        super(db, queries, traverser, graphQueryMgr, projectPath);
    }

    @Override
    public String getName() {
        return "codegraph_explore";
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", prop("string", "Natural language question, symbol name, or file path to explore"));
        props.put("maxFiles", propWithDefault("integer", "Max files to include", 12));
        props.put("projectPath", prop("string", "Project root directory (optional)"));
        schema.put("properties", props);
        schema.put("required", Arrays.asList("query"));
        return new ToolDefinition("codegraph_explore",
            "Primary tool. Ask natural language questions or explore symbols/files. " +
            "Returns grouped source code with line numbers, call paths, and impact radius.",
            schema);
    }

    @Override
    public ToolCallResult execute(Map<String, Object> args) {
        try {
            return handleExplore(args);
        } catch (Exception e) {
            logger.error("Explore failed", e);
            return error("Explore failed: " + e.getMessage());
        }
    }

    private ToolCallResult handleExplore(Map<String, Object> args) throws SQLException {
        String query = requireArg(args, "query");
        long startTime = System.currentTimeMillis();
        logger.info("[codegraph_explore] 开始处理查询: query=\"{}\"", query);

        // Step 1: 自适应输出预算
        int fileCount = countIndexedFiles();
        ExploreOutputBudget budget = ExploreOutputBudget.getForFileCount(fileCount);
        int maxFiles = clamp(intArg(args, "maxFiles", budget.defaultMaxFiles), 1, 20);
        logger.info("[codegraph_explore] Step 1 - 自适应预算: fileCount={}, maxFiles={}, maxOutputChars={}",
            fileCount, maxFiles, budget.maxOutputChars);

        ContextBuilder ctxBuilder = new ContextBuilder(queries);
        ContextBuilder.FindOptions opts = new ContextBuilder.FindOptions();
        opts.searchLimit = 8;
        opts.traversalDepth = 3;
        opts.maxNodes = 200;

        // Step 2: 混合搜索获取子图
        Subgraph subgraph = ctxBuilder.findRelevantContext(query, opts);
        if (subgraph.nodes.isEmpty()) {
            logger.info("[codegraph_explore] Step 2 - 未找到相关代码: query=\"{}\"", query);
            return text("No relevant code found for \"" + query + "\"");
        }
        logger.info("[codegraph_explore] Step 2 - 混合搜索完成: nodes={}, edges={}, roots={}",
            subgraph.nodes.size(), subgraph.edges.size(), subgraph.roots.size());

        // Step 3: 图感知粘合
        Set<String> glueNodeIds = new LinkedHashSet<>();
        Set<String> subgraphFiles = new LinkedHashSet<>();
        for (Node n : subgraph.nodes.values()) {
            if (n.getFilePath() != null) subgraphFiles.add(n.getFilePath());
        }
        final int GLUE_NODE_CAP = 60;
        for (String rootId : subgraph.roots) {
            if (glueNodeIds.size() >= GLUE_NODE_CAP) break;
            List<CallerInfo> callers = traverser.getCallers(rootId, 1);
            List<CalleeInfo> callees = traverser.getCallees(rootId, 1);
            for (CallerInfo ci : callers) {
                if (ci.node == null) continue;
                if (glueNodeIds.size() >= GLUE_NODE_CAP) break;
                if (subgraph.nodes.containsKey(ci.node.getId())) continue;
                if (!subgraphFiles.contains(ci.node.getFilePath())) continue;
                subgraph.addNode(ci.node);
                glueNodeIds.add(ci.node.getId());
            }
            for (CalleeInfo ci : callees) {
                if (ci.node == null) continue;
                if (glueNodeIds.size() >= GLUE_NODE_CAP) break;
                if (subgraph.nodes.containsKey(ci.node.getId())) continue;
                if (!subgraphFiles.contains(ci.node.getFilePath())) continue;
                subgraph.addNode(ci.node);
                glueNodeIds.add(ci.node.getId());
            }
        }
        logger.info("[codegraph_explore] Step 3 - 图感知粘合完成: glueNodes={}, subgraphFiles={}",
            glueNodeIds.size(), subgraphFiles.size());

        // Step 4: 命名符号播种
        Set<String> namedSeedIds = new LinkedHashSet<>();
        List<String> tokens = ctxBuilder.extractSymbols(query);
        for (String token : tokens) {
            if (namedSeedIds.size() >= 40) break;
            try {
                List<Node> byQName = queries.getNodesByQualifiedName(token);
                if (byQName.isEmpty()) byQName = queries.getNodesByName(token);
                if (byQName.isEmpty()) byQName = queries.searchNodes(token);
                for (Node n : byQName) {
                    if (namedSeedIds.size() >= 40) break;
                    if (!subgraph.nodes.containsKey(n.getId())) {
                        subgraph.addNode(n);
                    }
                    namedSeedIds.add(n.getId());
                }
            } catch (SQLException ignored) {}
        }
        logger.info("[codegraph_explore] Step 4 - 命名符号播种完成: tokens={}, namedSeeds={}",
            tokens.size(), namedSeedIds.size());

        // Step 5: 节点按文件分组 + 评分
        Map<String, FileGroup> fileGroups = new LinkedHashMap<>();
        Set<String> entryNodeIds = new LinkedHashSet<>();
        for (String id : subgraph.roots) entryNodeIds.add(id);
        entryNodeIds.addAll(namedSeedIds);

        Set<String> connectedToEntry = new HashSet<>();
        for (Edge e : subgraph.edges) {
            if (entryNodeIds.contains(e.getSource())) connectedToEntry.add(e.getTarget());
            if (entryNodeIds.contains(e.getTarget())) connectedToEntry.add(e.getSource());
        }

        for (Node n : subgraph.nodes.values()) {
            if (n.getKind() == NodeKind.IMPORT || n.getKind() == NodeKind.EXPORT) continue;
            if (isConfigLeafNode(n)) continue;
            FileGroup group = fileGroups.computeIfAbsent(
                n.getFilePath() != null ? n.getFilePath() : "", k -> new FileGroup());
            group.nodes.add(n);
            if (namedSeedIds.contains(n.getId())) {
                group.score += 50;
            } else if (entryNodeIds.contains(n.getId())) {
                group.score += 10;
            } else if (connectedToEntry.contains(n.getId())) {
                group.score += 3;
            } else {
                group.score += 1;
            }
        }

        List<Map.Entry<String, FileGroup>> relevantFiles = new ArrayList<>();
        for (Map.Entry<String, FileGroup> e : fileGroups.entrySet()) {
            if (e.getValue().score >= 3) relevantFiles.add(e);
        }

        int beforeFilterSize = relevantFiles.size();
        if (budget.excludeLowValueFiles && !mentionsTests(query)) {
            List<Map.Entry<String, FileGroup>> nonLow = new ArrayList<>();
            for (Map.Entry<String, FileGroup> e : relevantFiles) {
                if (!isLowValue(e.getKey())) nonLow.add(e);
            }
            if (nonLow.size() >= 2) relevantFiles = nonLow;
        }
        logger.info("[codegraph_explore] Step 5 - 文件分组评分完成: fileGroups={}, relevantFiles={}, 过滤掉={}",
            fileGroups.size(), relevantFiles.size(), beforeFilterSize - relevantFiles.size());

        // Step 6: RWR 图相关性
        Map<String, Double> nodeRwr = new GraphRelevanceComputer().compute(
            subgraph.nodes.keySet(), subgraph.edges, entryNodeIds);
        Map<String, Double> fileGraphScore = new HashMap<>();
        double maxGraph = 0;
        for (Node n : subgraph.nodes.values()) {
            double val = fileGraphScore.getOrDefault(n.getFilePath(), 0.0) + nodeRwr.getOrDefault(n.getId(), 0.0);
            fileGraphScore.put(n.getFilePath(), val);
            if (val > maxGraph) maxGraph = val;
        }

        Set<String> uniqueTerms = new HashSet<>();
        for (String t : query.toLowerCase().split("\\s+")) {
            if (t.length() >= 3) uniqueTerms.add(t);
        }
        Map<String, Integer> fileTermHits = new HashMap<>();
        for (Map.Entry<String, FileGroup> e : relevantFiles) {
            String fp = e.getKey();
            String hay = fp.toLowerCase() + " " + joinNodeNames(e.getValue().nodes);
            int hits = 0;
            for (String t : uniqueTerms) if (hay.contains(t)) hits++;
            fileTermHits.put(fp, hits);
        }

        Set<String> centralFiles = new LinkedHashSet<>();
        List<Map.Entry<String, Double>> scored = new ArrayList<>(fileGraphScore.entrySet());
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        int centralCount = 0;
        for (Map.Entry<String, Double> e : scored) {
            if (centralCount >= 2) break;
            if (e.getValue() > 0 && fileTermHits.getOrDefault(e.getKey(), 0) >= 1) {
                centralFiles.add(e.getKey());
                centralCount++;
            }
        }

        Set<String> entryFiles = new LinkedHashSet<>();
        for (String id : entryNodeIds) {
            Node n = subgraph.nodes.get(id);
            if (n != null && n.getFilePath() != null) entryFiles.add(n.getFilePath());
        }

        int beforeGateSize = relevantFiles.size();
        if (maxGraph > 0) {
            List<Map.Entry<String, FileGroup>> gated = new ArrayList<>();
            for (Map.Entry<String, FileGroup> e : relevantFiles) {
                String fp = e.getKey();
                double gs = fileGraphScore.getOrDefault(fp, 0.0);
                if (gs >= maxGraph * 0.06 || centralFiles.contains(fp) ||
                    entryFiles.contains(fp) || fileTermHits.getOrDefault(fp, 0) >= 2) {
                    gated.add(e);
                }
            }
            if (gated.size() >= 2) relevantFiles = gated;
        }
        logger.info("[codegraph_explore] Step 6 - RWR图相关性完成: centralFiles={}, entryFiles={}, 门控后文件数={}, maxGraph={}",
            centralFiles.size(), entryFiles.size(), relevantFiles.size(), String.format("%.4f", maxGraph));

        // Step 7: 文件排序
        final double maxGraphFinal = maxGraph;
        relevantFiles.sort((a, b) -> {
            String ap = a.getKey(), bp = b.getKey();
            double ags = fileGraphScore.getOrDefault(ap, 0.0), bgs = fileGraphScore.getOrDefault(bp, 0.0);
            int ah = fileTermHits.getOrDefault(ap, 0), bh = fileTermHits.getOrDefault(bp, 0);
            boolean aa = namedSeedIds.stream().anyMatch(id -> {
                Node n = subgraph.nodes.get(id);
                return n != null && ap.equals(n.getFilePath());
            });
            boolean ba = namedSeedIds.stream().anyMatch(id -> {
                Node n = subgraph.nodes.get(id);
                return n != null && bp.equals(n.getFilePath());
            });
            if (aa != ba) return ba ? 1 : -1;
            boolean ac = (entryFiles.contains(ap) || centralFiles.contains(ap)) && ah >= 2;
            boolean bc = (entryFiles.contains(bp) || centralFiles.contains(bp)) && bh >= 2;
            if (ac != bc) return bc ? 1 : -1;
            if (Math.abs(ags - bgs) > maxGraphFinal * 0.01) return Double.compare(bgs, ags);
            if (ah != bh) return bh - ah;
            boolean al = isLowValue(ap), bl = isLowValue(bp);
            if (al != bl) return al ? 1 : -1;
            boolean ag = isGeneratedFile(ap), bg = isGeneratedFile(bp);
            if (ag != bg) return ag ? 1 : -1;
            if (a.getValue().score != b.getValue().score) return b.getValue().score - a.getValue().score;
            return b.getValue().nodes.size() - a.getValue().nodes.size();
        });
        logger.info("[codegraph_explore] Step 7 - 文件排序完成: 待输出文件数={}", relevantFiles.size());

        // Step 8: 构建输出段落
        List<String> lines = new ArrayList<>();
        lines.add("**Exploration: " + escape(query) + "**");
        lines.add("");
        lines.add("Found " + subgraph.nodes.size() + " symbols across " + fileGroups.size() + " files.");
        lines.add("");

        String blastRadius = new BlastRadiusBuilder().build(subgraph, queries, traverser);
        if (!blastRadius.isEmpty()) lines.add(blastRadius);

        if (budget.includeRelationships) {
            List<Edge> sigEdges = new ArrayList<>();
            for (Edge e : subgraph.edges) {
                if (e.getKind() != EdgeKind.CONTAINS) sigEdges.add(e);
            }
            if (!sigEdges.isEmpty()) {
                lines.add("**Relationships**");
                lines.add("");
                Map<String, List<String[]>> byKind = new LinkedHashMap<>();
                for (Edge e : sigEdges) {
                    Node src = subgraph.nodes.get(e.getSource());
                    Node tgt = subgraph.nodes.get(e.getTarget());
                    if (src == null || tgt == null) continue;
                    byKind.computeIfAbsent(e.getKind().getValue(), k -> new ArrayList<>())
                          .add(new String[]{src.getName(), tgt.getName()});
                }
                for (Map.Entry<String, List<String[]>> ke : byKind.entrySet()) {
                    int cap = budget.maxEdgesPerRelationshipKind;
                    List<String[]> shown = ke.getValue().subList(0, Math.min(cap, ke.getValue().size()));
                    lines.add("**" + ke.getKey() + ":**");
                    for (String[] e : shown) lines.add("- " + e[0] + " \u2192 " + e[1]);
                    if (ke.getValue().size() > cap) lines.add("- ... and " + (ke.getValue().size() - cap) + " more");
                    lines.add("");
                }
            }
        }

        lines.add("**Source Code**");
        lines.add("");
        lines.add("> The code below is the **verbatim, current on-disk source** of these files \u2014 re-read from disk on this call and line-numbered, byte-for-byte identical to what the Read tool returns.");
        lines.add("");

        ContextBuilder.FlowInfo flow = ctxBuilder.buildFlowFromNamedSymbols(query);
        Set<String> pathNodeIds = flow.pathNodeIds;
        Set<String> namedNodeIds = flow.namedNodeIds;
        Set<String> uniqueNamedIds = flow.uniqueNamedNodeIds;

        int totalChars = joinStrings(lines).length();
        int filesIncluded = 0;
        boolean anyTrimmed = false;

        Map<String, Boolean> siblingSuperCache = new HashMap<>();
        Map<String, Boolean> superManyCache = new HashMap<>();
        final int MIN_SIBLINGS = 3;

        for (Map.Entry<String, FileGroup> entry : relevantFiles) {
            if (filesIncluded >= maxFiles) break;

            String filePath = entry.getKey();
            FileGroup group = entry.getValue();

            boolean fileNecessary = group.nodes.stream().anyMatch(n ->
                entryNodeIds.contains(n.getId()) || pathNodeIds.contains(n.getId()) || namedNodeIds.contains(n.getId()));
            if (!fileNecessary && totalChars > budget.maxOutputChars * 0.9) continue;

            Path absPath = Paths.get(filePath);
            if (!absPath.isAbsolute()) absPath = Paths.get(projectPath, filePath);
            if (!Files.exists(absPath)) continue;

            List<String> fileLines;
            try {
                fileLines = Files.readAllLines(absPath);
            } catch (IOException e) {
                continue;
            }

            String lang = detectLanguage(filePath);

            boolean hasSpineNode = group.nodes.stream().anyMatch(n -> pathNodeIds.contains(n.getId()));
            boolean isPolySib = !hasSpineNode && isPolymorphicSibling(group.nodes, siblingSuperCache, MIN_SIBLINGS);
            boolean spareNamed = group.nodes.stream().anyMatch(n -> uniqueNamedIds.contains(n.getId()));
            boolean definesPolySuper = definesPolymorphicSupertype(group.nodes, superManyCache, MIN_SIBLINGS);
            boolean spared = spareNamed && !definesPolySuper;

            int namedBodyChars = 0;
            for (Node n : group.nodes) {
                if (isCallable(n.getKind().getValue()) && (pathNodeIds.contains(n.getId()) || namedNodeIds.contains(n.getId()))) {
                    if (n.getStartLine() > 0 && n.getEndLine() > n.getStartLine()) {
                        namedBodyChars += String.join("\n", fileLines.subList(
                            Math.max(0, n.getStartLine() - 1), Math.min(fileLines.size(), n.getEndLine()))).length();
                    }
                }
            }
            boolean onSpineGodFile = hasSpineNode && namedBodyChars > budget.maxCharsPerFile
                && group.nodes.stream().anyMatch(n -> isCallable(n.getKind().getValue()) && uniqueNamedIds.contains(n.getId()) && !pathNodeIds.contains(n.getId()));

            if ((onSpineGodFile || (!hasSpineNode && isPolySib && !spared))) {
                String skeleton = renderSkeleton(group.nodes, fileLines, pathNodeIds, namedNodeIds, uniqueNamedIds, budget, lang);
                if (!skeleton.isEmpty()) {
                    String tag = !pathNodeIds.isEmpty() && !namedNodeIds.isEmpty()
                        ? "focused (the methods you named in full, the rest as signatures \u2014 codegraph_explore a signature for its body; do NOT Read)"
                        : "skeleton (signatures only \u2014 codegraph_explore a name for its full body; do NOT Read)";
                    lines.add(fileSectionHeader(filePath, tag));
                    lines.add("");
                    lines.add("```" + lang);
                    lines.add(skeleton);
                    lines.add("```");
                    lines.add("");
                    totalChars += skeleton.length() + 120;
                    filesIncluded++;
                    continue;
                }
            }

            boolean isCentral = centralFiles.contains(filePath);
            int WHOLE_FILE_MAX_LINES = isCentral ? 280 : 220;
            int WHOLE_FILE_MAX_CHARS = isCentral
                ? Math.min(Math.max(0, budget.maxOutputChars - totalChars - 200), (int)(budget.maxCharsPerFile * 1.5))
                : budget.maxCharsPerFile * 3;

            if (fileLines.size() <= WHOLE_FILE_MAX_LINES && fileLines.size() * 80 <= WHOLE_FILE_MAX_CHARS) {
                if (!fileNecessary && totalChars + fileLines.size() * 80 + 200 > budget.maxOutputChars) {
                    anyTrimmed = true;
                    continue;
                }
                String body = String.join("\n", fileLines);
                String numbered = numberSourceLines(body, 1);
                String names = extractSymbolNames(group.nodes, budget.maxSymbolsInFileHeader);
                lines.add(fileSectionHeader(filePath, names));
                lines.add("");
                lines.add("```" + lang);
                lines.add(numbered);
                lines.add("```");
                lines.add("");
                totalChars += numbered.length() + 200;
                filesIncluded++;
                continue;
            }

            String clusters = renderClusters(group.nodes, fileLines, subgraph, entryNodeIds, glueNodeIds, connectedToEntry, pathNodeIds, namedNodeIds, budget, lang, filePath);
            if (!clusters.isEmpty()) {
                lines.add(clusters);
                totalChars += clusters.length();
                filesIncluded++;
            }
        }

        if (budget.includeCompletenessSignal) {
            if (anyTrimmed || filesIncluded < relevantFiles.size()) {
                lines.add("*Some files were trimmed or omitted due to output budget limits.*");
                lines.add("");
            }
        }
        if (budget.includeBudgetNote) {
            lines.add("*Explore output budget: " + totalChars + "/" + budget.maxOutputChars + " chars, " + filesIncluded + "/" + maxFiles + " files.*");
        }

        String resultText = joinStrings(lines);
        long elapsed = System.currentTimeMillis() - startTime;
        logger.info("[codegraph_explore] 处理完成: query=\"{}\", 输出字符数={}, 文件数={}, 耗时={}ms",
            query, resultText.length(), filesIncluded, elapsed);
        ToolCallResult result = text(resultText);
        MarkdownUtils.writeMarkdownToFile(result, "codegraph_explore", query, projectPath);
        return result;
    }

    // ===== 渲染辅助 =====

    private static int clamp(int val, int lo, int hi) {
        return Math.max(lo, Math.min(hi, val));
    }

    private static String escape(String s) {
        return s != null ? s.replace("`", "\\`").replace("*", "\\*") : "";
    }

    private static String joinStrings(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append("\n");
        return sb.toString();
    }

    private static String joinNodeNames(Collection<Node> nodes) {
        StringBuilder sb = new StringBuilder();
        for (Node n : nodes) sb.append(n.getName().toLowerCase()).append(" ");
        return sb.toString();
    }

    private static String detectLanguage(String filePath) {
        if (filePath == null) return "";
        if (filePath.endsWith(".java")) return "java";
        if (filePath.endsWith(".kt") || filePath.endsWith(".kts")) return "kotlin";
        if (filePath.endsWith(".ts") || filePath.endsWith(".tsx")) return "typescript";
        if (filePath.endsWith(".js") || filePath.endsWith(".jsx") || filePath.endsWith(".mjs")) return "javascript";
        if (filePath.endsWith(".py")) return "python";
        if (filePath.endsWith(".go")) return "go";
        if (filePath.endsWith(".rs")) return "rust";
        if (filePath.endsWith(".rb")) return "ruby";
        if (filePath.endsWith(".php")) return "php";
        if (filePath.endsWith(".swift")) return "swift";
        if (filePath.endsWith(".cs")) return "csharp";
        if (filePath.endsWith(".cpp") || filePath.endsWith(".cc") || filePath.endsWith(".cxx") || filePath.endsWith(".c")) return "cpp";
        if (filePath.endsWith(".h") || filePath.endsWith(".hpp")) return "cpp";
        if (filePath.endsWith(".scala")) return "scala";
        if (filePath.endsWith(".lua")) return "lua";
        if (filePath.endsWith(".dart")) return "dart";
        if (filePath.endsWith(".vue")) return "vue";
        if (filePath.endsWith(".svelte")) return "svelte";
        if (filePath.endsWith(".astro")) return "astro";
        return "";
    }

    private static boolean isCallable(String kind) {
        return "method".equalsIgnoreCase(kind) || "function".equalsIgnoreCase(kind)
            || "component".equalsIgnoreCase(kind) || "constructor".equalsIgnoreCase(kind);
    }

    private static String fileSectionHeader(String filePath, String suffix) {
        String fileName = filePath.contains("/") ? filePath.substring(filePath.lastIndexOf('/') + 1) : filePath;
        return "**" + fileName + "** \u2014 " + suffix + " \u2014 `" + filePath + "`";
    }

    private static String numberSourceLines(String body, int startLine) {
        String[] rawLines = body.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rawLines.length; i++) {
            sb.append(startLine + i).append("`\t").append(rawLines[i]).append("\n");
        }
        return sb.toString();
    }

    private static String extractSymbolNames(Collection<Node> nodes, int max) {
        Set<String> names = new LinkedHashSet<>();
        for (Node n : nodes) {
            if (n.getKind() != NodeKind.IMPORT && n.getKind() != NodeKind.EXPORT) {
                names.add(n.getName());
            }
            if (names.size() >= max) break;
        }
        List<String> list = new ArrayList<>(names);
        String joined = String.join(", ", list);
        int omitted = names.size() - list.size();
        if (omitted > 0) joined += ", +" + omitted + " more";
        return joined;
    }

    private static boolean mentionsTests(String query) {
        if (query == null) return false;
        String lc = query.toLowerCase();
        return lc.contains("test") || lc.contains("testing")
            || lc.contains("spec") || lc.contains("verify");
    }

    private static boolean isConfigLeafNode(Node n) {
        if (n.getKind() == NodeKind.CONSTANT || n.getKind() == NodeKind.FIELD) {
            String name = n.getName() != null ? n.getName().toLowerCase() : "";
            String qname = n.getQualifiedName() != null ? n.getQualifiedName().toLowerCase() : "";
            return name.startsWith("${") || qname.contains(".properties")
                || qname.contains(".yml") || qname.contains(".yaml");
        }
        return false;
    }

    private static boolean isLowValue(String path) {
        if (path == null) return false;
        String lp = path.toLowerCase();
        return lp.contains("/tests/") || lp.contains("/spec/") || lp.contains("/__tests__/")
            || lp.contains("/test/") || lp.contains("/specs/")
            || path.endsWith("_test.go") || path.endsWith("_test.py")
            || path.endsWith("_spec.rb") || path.endsWith("_test.rb")
            || path.endsWith(".test.ts") || path.endsWith(".spec.ts")
            || path.endsWith(".test.tsx") || path.endsWith(".spec.tsx")
            || path.endsWith(".test.js") || path.endsWith(".spec.js")
            || path.endsWith(".test.java") || path.endsWith(".spec.java")
            || path.endsWith("_test.java") || path.endsWith("_spec.java")
            || path.endsWith("_tests.java") || path.endsWith("_test.kt")
            || path.endsWith("_spec.kt") || lp.contains("/icons/") || lp.contains("/i18n/");
    }

    private static boolean isGeneratedFile(String path) {
        if (path == null) return false;
        String lp = path.toLowerCase();
        return lp.contains(".pb.") || lp.endsWith(".pulsar.go")
            || lp.endsWith("_mocks.go") || lp.endsWith("_mock.go")
            || lp.contains("/generated/") || lp.contains("/generated_src/")
            || lp.endsWith("_generated.java") || lp.endsWith("_generated.kt")
            || lp.contains("generated_") && (lp.endsWith(".java") || lp.endsWith(".kt"));
    }

    private boolean isPolymorphicSibling(List<Node> nodes, Map<String, Boolean> cache, int minSiblings) {
        for (Node n : nodes) {
            try {
                List<Edge> outgoing = queries.getOutgoingEdges(n.getId());
                for (Edge e : outgoing) {
                    if (e.getKind() != EdgeKind.EXTENDS && e.getKind() != EdgeKind.IMPLEMENTS) continue;
                    String target = e.getTarget();
                    Boolean cached = cache.get(target);
                    if (cached != null) { if (cached) return true; continue; }
                    try {
                        List<Edge> incoming = queries.getIncomingEdges(target);
                        int count = 0;
                        for (Edge ie : incoming) {
                            if (ie.getKind() == EdgeKind.EXTENDS || ie.getKind() == EdgeKind.IMPLEMENTS) count++;
                        }
                        boolean many = count >= minSiblings;
                        cache.put(target, many);
                        if (many) return true;
                    } catch (SQLException ignored) {}
                }
            } catch (SQLException ignored) {}
        }
        return false;
    }

    private boolean definesPolymorphicSupertype(List<Node> nodes, Map<String, Boolean> cache, int minSiblings) {
        for (Node n : nodes) {
            String kn = n.getKind().getValue();
            if (!kn.equals("class") && !kn.equals("interface") && !kn.equals("struct")
                && !kn.equals("trait") && !kn.equals("protocol") && !kn.equals("type_alias")
                && !kn.equals("enum")) continue;
            Boolean cached = cache.get(n.getId());
            if (cached != null) return cached;
            try {
                List<Edge> incoming = queries.getIncomingEdges(n.getId());
                int count = 0;
                for (Edge e : incoming) {
                    if (e.getKind() == EdgeKind.EXTENDS || e.getKind() == EdgeKind.IMPLEMENTS) count++;
                }
                boolean many = count >= minSiblings;
                cache.put(n.getId(), many);
                if (many) return true;
            } catch (SQLException ignored) {}
        }
        return false;
    }

    private String renderSkeleton(Collection<Node> nodes, List<String> fileLines,
            Set<String> pathNodeIds, Set<String> namedNodeIds, Set<String> uniqueNamedIds,
            ExploreOutputBudget budget, String lang) {

        List<Node> syms = new ArrayList<>();
        for (Node n : nodes) {
            if ((n.getKind() == NodeKind.IMPORT || n.getKind() == NodeKind.EXPORT) && n.getStartLine() <= 0) continue;
            syms.add(n);
        }
        syms.sort(Comparator.comparingInt(n -> n.getStartLine() > 0 ? n.getStartLine() : Integer.MAX_VALUE));

        Set<String> bodyIds = new LinkedHashSet<>();
        int bodyChars = 0;
        int bodyCap = (int)(budget.maxCharsPerFile * 1.5);

        final Set<String> CALLABLE_KINDS = new HashSet<>(Arrays.asList(
            "method", "function", "component", "constructor", "property"));

        for (Node n : syms) {
            String kn = n.getKind().getValue();
            int priority = !CALLABLE_KINDS.contains(kn) ? 99
                : pathNodeIds.contains(n.getId()) ? 0
                : uniqueNamedIds.contains(n.getId()) ? 1 : 99;
            if (priority >= 99) continue;
            if (n.getEndLine() < n.getStartLine()) continue;

            int sz = 0;
            try {
                sz = String.join("\n", fileLines.subList(
                    Math.max(0, n.getStartLine() - 1), Math.min(fileLines.size(), n.getEndLine()))).length();
            } catch (Exception e) { continue; }
            if (bodyChars + sz > bodyCap && !bodyIds.isEmpty()) continue;
            bodyIds.add(n.getId());
            bodyChars += sz;
        }

        List<String> skel = new ArrayList<>();
        int coveredUntil = 0;
        int sigCount = 0;
        int sigDropped = 0;
        int SIG_MAX = Math.max(12, budget.maxSymbolsInFileHeader * 2);

        for (Node n : syms) {
            if (n.getStartLine() > 0 && coveredUntil > 0 && n.getStartLine() <= coveredUntil) continue;

            if (bodyIds.contains(n.getId())) {
                int start = Math.max(0, n.getStartLine() - 1);
                int end = Math.min(fileLines.size(), n.getEndLine());
                String body = String.join("\n", fileLines.subList(start, end));
                skel.add(numberSourceLines(body, n.getStartLine()));
                coveredUntil = n.getEndLine();
            } else {
                int lineNo = n.getStartLine();
                for (int k = 0; k < 4 && lineNo + k < fileLines.size(); k++) {
                    String line = fileLines.get(lineNo - 1 + k);
                    if (line != null && line.contains(n.getName())) { lineNo = lineNo + k; break; }
                }
                if (lineNo <= coveredUntil) continue;
                if (sigCount >= SIG_MAX) { sigDropped++; continue; }
                String sig = fileLines.get(Math.min(lineNo - 1, fileLines.size() - 1));
                if (sig != null && !sig.trim().isEmpty()) {
                    skel.add(lineNo + "\t" + sig.trim());
                    sigCount++;
                }
            }
        }
        if (sigDropped > 0) skel.add("\u2026 +" + sigDropped + " more (signatures elided)");
        return String.join("\n", skel);
    }

    private String renderClusters(Collection<Node> nodes, List<String> fileLines,
            Subgraph subgraph, Set<String> entryNodeIds, Set<String> glueNodeIds,
            Set<String> connectedToEntry, Set<String> pathNodeIds, Set<String> namedNodeIds,
            ExploreOutputBudget budget, String lang, String filePath) {

        final Set<String> ENVELOPE_KINDS = new HashSet<>(Arrays.asList(
            "file", "module", "class", "struct", "interface", "enum",
            "namespace", "protocol", "trait", "component"));

        List<NodeRange> ranges = new ArrayList<>();
        for (Node n : nodes) {
            if (n.getStartLine() <= 0 || n.getEndLine() <= n.getStartLine()) continue;
            if (ENVELOPE_KINDS.contains(n.getKind().getValue())
                && (n.getEndLine() - n.getStartLine() + 1) > fileLines.size() * 0.5) continue;

            int importance = 1;
            if (entryNodeIds.contains(n.getId())) importance = 10;
            else if (namedNodeIds.contains(n.getId())) importance = 9;
            else if (glueNodeIds.contains(n.getId())) importance = 6;
            else if (connectedToEntry.contains(n.getId())) importance = 3;

            ranges.add(new NodeRange(n, importance, pathNodeIds.contains(n.getId())));
        }

        if (ranges.isEmpty()) return "";

        ranges.sort(Comparator.comparingInt(r -> r.startLine));

        List<NodeRange> merged = new ArrayList<>();
        for (NodeRange r : ranges) {
            if (!merged.isEmpty() && r.startLine - merged.get(merged.size() - 1).endLine <= budget.gapThreshold) {
                NodeRange last = merged.get(merged.size() - 1);
                last.endLine = Math.max(last.endLine, r.endLine);
                last.importance = Math.max(last.importance, r.importance);
                if (r.isSpine) last.isSpine = true;
            } else {
                merged.add(r);
            }
        }

        List<NodeRange> selected = new ArrayList<>();
        int bodyChars = 0;
        int bodyCap = budget.maxCharsPerFile * 3;
        for (NodeRange r : merged) {
            if (r.importance >= 3) {
                int sz = 0;
                try {
                    sz = String.join("\n", fileLines.subList(
                        Math.max(0, r.startLine - 1), Math.min(fileLines.size(), r.endLine))).length();
                } catch (Exception e) {}
                if (bodyChars + sz > bodyCap && !selected.isEmpty()) continue;
                selected.add(r);
                bodyChars += sz;
            }
        }
        if (selected.isEmpty()) return "";

        List<String> lines = new ArrayList<>();
        List<Node> allRangeNodes = new ArrayList<>();
        for (NodeRange r : merged) allRangeNodes.add(r.node);
        String names = extractSymbolNames(allRangeNodes, budget.maxSymbolsInFileHeader);
        lines.add(fileSectionHeader(filePath, "clustered"));
        lines.add("");

        int shown = 0;
        int maxClusters = budget.maxCharsPerFile / 40;
        for (NodeRange r : selected) {
            if (shown++ >= maxClusters) break;
            int start = Math.max(0, r.startLine - 1);
            int end = Math.min(fileLines.size(), r.endLine);
            String chunk = String.join("\n", fileLines.subList(start, end));
            lines.add("```" + lang);
            lines.add(numberSourceLines(chunk, r.startLine));
            lines.add("```");
            lines.add("");
        }

        return String.join("\n", lines);
    }

    private int countIndexedFiles() {
        try { return queries.getAllFiles().size(); }
        catch (SQLException e) { return 0; }
    }

    // ===== 内部类 =====

    private static class FileGroup {
        List<Node> nodes = new ArrayList<>();
        int score = 0;
    }

    private static class NodeRange {
        Node node;
        int startLine;
        int endLine;
        int importance;
        boolean isSpine;
        NodeRange(Node n, int importance, boolean isSpine) {
            this.node = n; this.startLine = n.getStartLine(); this.endLine = n.getEndLine();
            this.importance = importance; this.isSpine = isSpine;
        }
    }
}
