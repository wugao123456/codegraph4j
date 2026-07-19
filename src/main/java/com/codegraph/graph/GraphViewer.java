package com.codegraph.graph;

import com.codegraph.db.DatabaseConnection;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * CodeGraph 图数据可视化器。
 * 对标 viewer.ts 的 buildGraphViewData()，从 SQLite 索引库查询节点和边数据，
 * 返回结构化的 JSON 数据供前端 vis-network 渲染。
 */
public class GraphViewer {

    /** 节点类型颜色映射（与 viewer.ts 一致） */
    public static final Map<String, String> KIND_COLORS = new LinkedHashMap<>();
    static {
        KIND_COLORS.put("file", "#6b7280");
        KIND_COLORS.put("module", "#6b7280");
        KIND_COLORS.put("class", "#f59e0b");
        KIND_COLORS.put("struct", "#f59e0b");
        KIND_COLORS.put("interface", "#eab308");
        KIND_COLORS.put("trait", "#eab308");
        KIND_COLORS.put("protocol", "#eab308");
        KIND_COLORS.put("function", "#3b82f6");
        KIND_COLORS.put("method", "#2563eb");
        KIND_COLORS.put("property", "#06b6d4");
        KIND_COLORS.put("field", "#06b6d4");
        KIND_COLORS.put("variable", "#10b981");
        KIND_COLORS.put("constant", "#10b981");
        KIND_COLORS.put("enum", "#a855f7");
        KIND_COLORS.put("enum_member", "#a855f7");
        KIND_COLORS.put("type_alias", "#8b5cf6");
        KIND_COLORS.put("namespace", "#94a3b8");
        KIND_COLORS.put("parameter", "#94a3b8");
        KIND_COLORS.put("import", "#cbd5e1");
        KIND_COLORS.put("export", "#cbd5e1");
        KIND_COLORS.put("route", "#ef4444");
        KIND_COLORS.put("component", "#ec4899");
    }
    public static final String DEFAULT_COLOR = "#94a3b8";

    private static final Set<String> NOISY_EDGE_KINDS = new HashSet<>(Arrays.asList(
        "imports", "exports", "references"
    ));

    // ===== 数据模型 =====

    public static class ViewNode {
        public String id;
        public String label;
        public String title;
        public String group;
        public String color;
        public int value;
        public String file;

        public ViewNode() {}
        public ViewNode(String id, String label, String title, String group,
                        String color, int value, String file) {
            this.id = id;
            this.label = label;
            this.title = title;
            this.group = group;
            this.color = color;
            this.value = value;
            this.file = file;
        }
    }

    public static class ViewEdge {
        public String from;
        public String to;
        public String label;
        public String arrows;
        public String title;

        public ViewEdge() {}
        public ViewEdge(String from, String to, String label, String arrows, String title) {
            this.from = from;
            this.to = to;
            this.label = label;
            this.arrows = arrows;
            this.title = title;
        }
    }

    public static class GraphViewStats {
        public int totalNodes;
        public int totalEdges;
        public List<String> kinds;

        public GraphViewStats() {}
        public GraphViewStats(int totalNodes, int totalEdges, List<String> kinds) {
            this.totalNodes = totalNodes;
            this.totalEdges = totalEdges;
            this.kinds = kinds;
        }
    }

    public static class GraphViewData {
        public List<ViewNode> nodes;
        public List<ViewEdge> edges;
        public GraphViewStats stats;

        public GraphViewData() {}
        public GraphViewData(List<ViewNode> nodes, List<ViewEdge> edges, GraphViewStats stats) {
            this.nodes = nodes;
            this.edges = edges;
            this.stats = stats;
        }
    }

    /**
     * 构建图数据，对标 viewer.ts 的 buildGraphViewData()。
     *
     * @param db         数据库连接
     * @param symbol     聚焦符号名（可选）
     * @param file       文件名过滤（可选）
     * @param includeImports 是否包含 import/export/references 边
     * @param maxNodes   最大节点数（默认 250）
     * @param degree     BFS 扩展度数（默认 3）
     */
    public static GraphViewData buildViewData(DatabaseConnection db,
                                               String symbol, String file,
                                               boolean includeImports, int maxNodes, int degree)
            throws SQLException {

        if (maxNodes <= 0) maxNodes = 250;
        if (degree <= 0) degree = 3;

        // Edge-kind filter
        StringBuilder edgeFilter = new StringBuilder();
        List<String> edgeParams = new ArrayList<>();
        if (!includeImports) {
            List<String> placeholders = new ArrayList<>();
            for (String kind : NOISY_EDGE_KINDS) {
                placeholders.add("?");
                edgeParams.add(kind);
            }
            edgeFilter.append(" AND kind NOT IN (").append(String.join(",", placeholders)).append(")");
        }
        String edgeFilterStr = edgeFilter.toString();

        List<NodeRow> nodeRows = new ArrayList<>();
        Set<String> nodeIds = new LinkedHashSet<>();

        if (symbol != null && !symbol.isEmpty()) {
            // 按符号名查找 seed 节点
            String nodeSql = "SELECT id, kind, name, qualified_name, file_path, start_line FROM nodes WHERE name = ? OR qualified_name = ? LIMIT 1";
            try (PreparedStatement ps = db.getConnection().prepareStatement(nodeSql)) {
                ps.setString(1, symbol);
                ps.setString(2, symbol);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    String seedId = rs.getString("id");
                    nodeIds.add(seedId);
                } else {
                    throw new RuntimeException("No symbol found matching '" + symbol + "'");
                }
            }

            // BFS 多度数扩展
            Set<String> visited = new LinkedHashSet<>(nodeIds);
            for (int d = 0; d < degree && visited.size() < maxNodes; d++) {
                Set<String> currentLevel = new LinkedHashSet<>(visited);
                String placeholders = repeat("?", currentLevel.size(), ",");
                String edgeSql = "SELECT source, target FROM edges WHERE (source IN (" + placeholders + ") OR target IN (" + placeholders + "))" + edgeFilterStr;
                try (PreparedStatement ps = db.getConnection().prepareStatement(edgeSql)) {
                    int idx = 1;
                    for (String id : currentLevel) ps.setString(idx++, id);
                    for (String id : currentLevel) ps.setString(idx++, id);
                    for (String p : edgeParams) ps.setString(idx++, p);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        visited.add(rs.getString("source"));
                        visited.add(rs.getString("target"));
                        if (visited.size() >= maxNodes) break;
                    }
                }
            }
            nodeIds = visited;

            // 批量获取节点
            nodeRows = fetchNodesByIds(db, nodeIds);
        } else if (file != null && !file.isEmpty()) {
            // 按文件名 LIKE 过滤
            String seedSql = "SELECT id, kind, name, qualified_name, file_path, start_line FROM nodes WHERE file_path LIKE ? LIMIT ?";
            try (PreparedStatement ps = db.getConnection().prepareStatement(seedSql)) {
                ps.setString(1, "%" + file + "%");
                ps.setInt(2, maxNodes);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    nodeRows.add(readNodeRow(rs));
                    nodeIds.add(rs.getString("id"));
                }
            }

            // BFS 多度数扩展
            if (!nodeIds.isEmpty()) {
                Set<String> visited = new LinkedHashSet<>(nodeIds);
                for (int d = 0; d < degree && visited.size() < maxNodes; d++) {
                    Set<String> currentLevel = new LinkedHashSet<>(visited);
                    String placeholders = repeat("?", currentLevel.size(), ",");
                    String edgeSql = "SELECT source, target FROM edges WHERE (source IN (" + placeholders + ") OR target IN (" + placeholders + "))" + edgeFilterStr;
                    try (PreparedStatement ps = db.getConnection().prepareStatement(edgeSql)) {
                        int idx = 1;
                        for (String id : currentLevel) ps.setString(idx++, id);
                        for (String id : currentLevel) ps.setString(idx++, id);
                        for (String p : edgeParams) ps.setString(idx++, p);
                        ResultSet rs = ps.executeQuery();
                        while (rs.next()) {
                            visited.add(rs.getString("source"));
                            visited.add(rs.getString("target"));
                            if (visited.size() >= maxNodes) break;
                        }
                    }
                }
                nodeIds = visited;
                nodeRows = fetchNodesByIds(db, nodeIds);
            }
        } else {
            // 全图模式：按 degree 降序，排除 parameter/import
            String fullSql = "SELECT n.id, n.kind, n.name, n.qualified_name, n.file_path, n.start_line, " +
                "(SELECT COUNT(*) FROM edges e WHERE e.source = n.id OR e.target = n.id) AS degree " +
                "FROM nodes n WHERE n.kind NOT IN ('parameter','import') " +
                "ORDER BY degree DESC LIMIT ?";
            try (PreparedStatement ps = db.getConnection().prepareStatement(fullSql)) {
                ps.setInt(1, maxNodes);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    nodeRows.add(readNodeRow(rs));
                    nodeIds.add(rs.getString("id"));
                }
            }
        }

        if (nodeIds.isEmpty()) {
            throw new RuntimeException("No matching nodes found.");
        }

        // 获取选中节点之间的边
        List<EdgeRow> edgeRows = new ArrayList<>();
        if (nodeIds.size() > 0 && nodeIds.size() <= 5000) {
            String placeholders = repeat("?", nodeIds.size(), ",");
            String edgeSql = "SELECT source, target, kind FROM edges WHERE source IN (" + placeholders +
                ") AND target IN (" + placeholders + ")" + edgeFilterStr;
            try (PreparedStatement ps = db.getConnection().prepareStatement(edgeSql)) {
                int idx = 1;
                for (String id : nodeIds) ps.setString(idx++, id);
                for (String id : nodeIds) ps.setString(idx++, id);
                for (String p : edgeParams) ps.setString(idx++, p);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    edgeRows.add(new EdgeRow(
                        rs.getString("source"),
                        rs.getString("target"),
                        rs.getString("kind")
                    ));
                }
            }
        }

        // 计算节点 degree（用于 sizing）
        Map<String, Integer> nodeDegree = new HashMap<>();
        for (String id : nodeIds) nodeDegree.put(id, 0);
        for (EdgeRow e : edgeRows) {
            nodeDegree.put(e.source, nodeDegree.getOrDefault(e.source, 0) + 1);
            nodeDegree.put(e.target, nodeDegree.getOrDefault(e.target, 0) + 1);
        }

        // 构建 ViewNode 列表
        List<ViewNode> nodes = new ArrayList<>();
        for (NodeRow r : nodeRows) {
            int d = nodeDegree.getOrDefault(r.id, 0);
            nodes.add(new ViewNode(
                r.id,
                r.name,
                r.qualified_name + "\n" + r.file_path + ":" + r.start_line + "\nkind: " + r.kind + "\nconnections: " + d,
                r.kind,
                KIND_COLORS.getOrDefault(r.kind, DEFAULT_COLOR),
                Math.max(5, Math.min(40, 5 + d * 3)),
                r.file_path
            ));
        }

        // 构建 ViewEdge 列表
        List<ViewEdge> edges = new ArrayList<>();
        for (EdgeRow e : edgeRows) {
            edges.add(new ViewEdge(e.source, e.target, e.kind, "to", e.kind));
        }

        // 统计信息
        Set<String> kinds = new LinkedHashSet<>();
        for (ViewNode n : nodes) kinds.add(n.group);
        GraphViewStats stats = new GraphViewStats(nodes.size(), edges.size(), new ArrayList<>(kinds));

        return new GraphViewData(nodes, edges, stats);
    }

    // ===== 内部辅助 =====

    private static class NodeRow {
        String id, kind, name, qualified_name, file_path;
        int start_line;
    }

    private static class EdgeRow {
        String source, target, kind;
        EdgeRow(String source, String target, String kind) {
            this.source = source; this.target = target; this.kind = kind;
        }
    }

    private static NodeRow readNodeRow(ResultSet rs) throws SQLException {
        NodeRow r = new NodeRow();
        r.id = rs.getString("id");
        r.kind = rs.getString("kind");
        r.name = rs.getString("name");
        r.qualified_name = rs.getString("qualified_name");
        r.file_path = rs.getString("file_path");
        r.start_line = rs.getInt("start_line");
        return r;
    }

    private static List<NodeRow> fetchNodesByIds(DatabaseConnection db, Set<String> ids) throws SQLException {
        List<NodeRow> rows = new ArrayList<>();
        if (ids.isEmpty()) return rows;
        String placeholders = repeat("?", ids.size(), ",");
        String sql = "SELECT id, kind, name, qualified_name, file_path, start_line FROM nodes WHERE id IN (" + placeholders + ") AND kind NOT IN ('import','export')";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            int idx = 1;
            for (String id : ids) ps.setString(idx++, id);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) rows.add(readNodeRow(rs));
        }
        return rows;
    }

    private static String repeat(String s, int count, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(sep);
            sb.append(s);
        }
        return sb.toString();
    }
}
