package com.codegraph.context;

import com.codegraph.core.Edge;
import com.codegraph.core.Node;
import com.codegraph.core.types.EdgeKind;

import java.util.*;

/**
 * Random Walk with Restart (RWR) 图相关性计算器。
 * 对标 codegraph mcp/tools.ts 的 computeGraphRelevance() 方法。
 *
 * <p>从种子节点（用户查询命中的节点）出发，计算图中每个节点相对于种子的
 * 相关性得分。相关性通过结构连通性衡量，而非文本匹配——一个通过调用链
 * 连接到种子的文件得高分，仅有文本匹配但无调用关系的文件得分接近 0。
 *
 * <p>算法：迭代 25 次，α=0.25（重启概率），无向图邻接（双向可达），
 * 仅在 RANK_EDGES 类型的边上行走。
 */
public class GraphRelevanceComputer {

    /** 参与相关性计算的边类型 */
    private static final Set<String> RANK_EDGES = new HashSet<>(Arrays.asList(
        "calls", "references", "extends", "implements", "overrides",
        "instantiates", "returns", "type_of", "imports"
    ));

    private static final double ALPHA = 0.25;
    private static final int MAX_ITERATIONS = 25;

    /**
     * 计算每个节点相对于种子节点的相关性得分。
     *
     * @param nodeIds 所有候选节点 ID 列表（子图节点）
     * @param edges 子图中的边
     * @param seedIds 种子节点 ID 集合
     * @return nodeId → relevance score [0, 1]
     */
    public Map<String, Double> compute(Collection<String> nodeIds, List<Edge> edges, Set<String> seedIds) {
        Map<String, Double> out = new HashMap<>();
        List<String> idList = new ArrayList<>(nodeIds);
        int n = idList.size();
        if (n == 0) return out;

        // 构建 ID → index 映射
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < n; i++) {
            idx.put(idList.get(i), i);
        }

        // 构建邻接表
        @SuppressWarnings("unchecked")
        List<Integer>[] adj = new List[n];
        for (int i = 0; i < n; i++) adj[i] = new ArrayList<>();
        for (Edge e : edges) {
            if (!RANK_EDGES.contains(e.getKind().getValue())) continue;
            Integer i = idx.get(e.getSource());
            Integer j = idx.get(e.getTarget());
            if (i == null || j == null || i == j) continue;
            adj[i].add(j);
            adj[j].add(i); // 无向 — 双向可达
        }

        // 初始化重启向量（均匀分布在种子节点上）
        double[] r = new double[n];
        double rsum = 0;
        for (String sid : seedIds) {
            Integer i = idx.get(sid);
            if (i != null) { r[i] = 1; rsum += 1; }
        }
        if (rsum == 0) { for (int i = 0; i < n; i++) { r[i] = 1; rsum += 1; } }
        for (int i = 0; i < n; i++) r[i] /= rsum;

        // 迭代 RWR
        double[] s = Arrays.copyOf(r, n);
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            double[] next = new double[n];
            for (int i = 0; i < n; i++) {
                double si = s[i];
                if (si == 0) { next[i] += si; continue; }
                List<Integer> neighbors = adj[i];
                int d = neighbors.size();
                if (d == 0) {
                    next[i] += si; // dangling 节点：保留质量
                    continue;
                }
                double share = si / d;
                for (int j : neighbors) next[j] += share;
            }
            for (int i = 0; i < n; i++) {
                s[i] = (1 - ALPHA) * next[i] + ALPHA * r[i];
            }
        }

        // 收集结果
        for (int i = 0; i < n; i++) {
            out.put(idList.get(i), s[i]);
        }
        return out;
    }
}
