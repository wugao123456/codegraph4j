package com.codegraph.db;

import com.codegraph.core.Edge;
import com.codegraph.core.FileRecord;
import com.codegraph.core.Node;
import com.codegraph.core.types.EdgeKind;
import com.codegraph.core.types.Language;
import com.codegraph.core.types.NodeKind;
import com.codegraph.core.types.Visibility;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class QueryBuilder {
    private static final Logger logger = LoggerFactory.getLogger(QueryBuilder.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final DatabaseConnection db;

    public QueryBuilder(DatabaseConnection db) {
        this.db = db;
    }

    public void insertNode(Node node) throws SQLException {
        String sql = "INSERT OR REPLACE INTO nodes (" +
                "id, kind, name, qualified_name, file_path, language, " +
                "start_line, end_line, start_column, end_column, " +
                "docstring, signature, visibility, is_exported, is_async, " +
                "is_static, is_abstract, decorators, type_parameters, return_type, updated_at" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        
        try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
            stmt.setString(1, node.getId());
            stmt.setString(2, node.getKind().name());
            stmt.setString(3, node.getName());
            stmt.setString(4, node.getQualifiedName());
            stmt.setString(5, node.getFilePath());
            stmt.setString(6, node.getLanguage().name());
            stmt.setInt(7, node.getStartLine());
            stmt.setInt(8, node.getEndLine());
            stmt.setInt(9, node.getStartColumn());
            stmt.setInt(10, node.getEndColumn());
            stmt.setString(11, node.getDocstring());
            stmt.setString(12, node.getSignature());
            stmt.setString(13, node.getVisibility() != null ? node.getVisibility().name() : null);
            stmt.setInt(14, node.isExported() ? 1 : 0);
            stmt.setInt(15, node.isAsync() ? 1 : 0);
            stmt.setInt(16, node.isStatic() ? 1 : 0);
            stmt.setInt(17, node.isAbstract() ? 1 : 0);
            stmt.setString(18, serializeList(node.getDecorators()));
            stmt.setString(19, serializeList(node.getTypeParameters()));
            stmt.setString(20, node.getReturnType());
            stmt.setLong(21, node.getUpdatedAt());
            
            stmt.executeUpdate();
        }
    }

    public void insertEdge(Edge edge) throws SQLException {
        String sql = "INSERT INTO edges (source, target, kind, metadata, line, col, provenance) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        
        try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
            stmt.setString(1, edge.getSource());
            stmt.setString(2, edge.getTarget());
            stmt.setString(3, edge.getKind().name());
            stmt.setString(4, serializeObject(edge.getMetadata()));
            stmt.setInt(5, edge.getLine());
            stmt.setInt(6, edge.getColumn());
            stmt.setString(7, edge.getProvenance());
            
            stmt.executeUpdate();
        }
    }

    public Node getNode(String id) throws SQLException {
        String sql = "SELECT * FROM nodes WHERE id = ?";
        
        try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
            stmt.setString(1, id);
            
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return mapResultSetToNode(rs);
            }
        }
        
        return null;
    }

    public List<Node> searchNodes(String query) throws SQLException {
        List<Node> nodes = new ArrayList<>();
        
        String sql = "SELECT * FROM nodes WHERE " +
                "LOWER(name) LIKE ? OR " +
                "LOWER(qualified_name) LIKE ? OR " +
                "LOWER(docstring) LIKE ?";
        
        String likePattern = "%" + query.toLowerCase() + "%";
        
        try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
            stmt.setString(1, likePattern);
            stmt.setString(2, likePattern);
            stmt.setString(3, likePattern);
            
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                nodes.add(mapResultSetToNode(rs));
            }
        }
        
        return nodes;
    }

    public List<Node> getNodesInFile(String filePath) throws SQLException {
        List<Node> nodes = new ArrayList<>();
        
        String sql = "SELECT * FROM nodes WHERE file_path = ?";
        
        try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
            stmt.setString(1, filePath);
            
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                nodes.add(mapResultSetToNode(rs));
            }
        }
        
        return nodes;
    }

    public List<Edge> getEdgesBySource(String sourceId) throws SQLException {
        List<Edge> edges = new ArrayList<>();
        
        String sql = "SELECT * FROM edges WHERE source = ?";
        
        try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
            stmt.setString(1, sourceId);
            
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                edges.add(mapResultSetToEdge(rs));
            }
        }
        
        return edges;
    }

    public List<Edge> getEdgesByTarget(String targetId) throws SQLException {
        List<Edge> edges = new ArrayList<>();
        
        String sql = "SELECT * FROM edges WHERE target = ?";
        
        try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
            stmt.setString(1, targetId);
            
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                edges.add(mapResultSetToEdge(rs));
            }
        }
        
        return edges;
    }

    public int deleteNodesByFile(String filePath) throws SQLException {
        String sql = "DELETE FROM nodes WHERE file_path = ?";
        
        try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
            stmt.setString(1, filePath);
            return stmt.executeUpdate();
        }
    }

    public int deleteEdgesBySourceOrTarget(String nodeId) throws SQLException {
        String sql = "DELETE FROM edges WHERE source = ? OR target = ?";
        
        try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
            stmt.setString(1, nodeId);
            stmt.setString(2, nodeId);
            return stmt.executeUpdate();
        }
    }

    public long getNodeCount() throws SQLException {
        String sql = "SELECT COUNT(*) FROM nodes";
        
        try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0;
        }
    }

    public long getEdgeCount() throws SQLException {
        String sql = "SELECT COUNT(*) FROM edges";
        
        try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0;
        }
    }
    
    public FileRecord getFile(String path) throws SQLException {
        String sql = "SELECT * FROM files WHERE path = ?";
        
        try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
            stmt.setString(1, path);
            
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                FileRecord file = new FileRecord();
                file.setFilePath(rs.getString("path"));
                file.setHash(rs.getString("content_hash"));
                file.setLanguage(Language.valueOf(rs.getString("language")));
                file.setSize(rs.getLong("size"));
                file.setMtime(rs.getLong("modified_at"));
                file.setIndexedAt(rs.getLong("indexed_at"));
                return file;
            }
        }
        
        return null;
    }
    
    public void insertOrUpdateFile(FileRecord file) throws SQLException {
        String sql = "INSERT OR REPLACE INTO files (" +
                "path, content_hash, language, size, modified_at, indexed_at, node_count, errors" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        
        try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
            stmt.setString(1, file.getFilePath());
            stmt.setString(2, file.getHash());
            stmt.setString(3, file.getLanguage().name());
            stmt.setLong(4, file.getSize());
            stmt.setLong(5, file.getMtime());
            stmt.setLong(6, file.getIndexedAt());
            stmt.setInt(7, 0); // node_count placeholder
            stmt.setString(8, null); // errors placeholder
            
            stmt.executeUpdate();
        }
    }

    private Node mapResultSetToNode(ResultSet rs) throws SQLException {
        Node node = new Node();
        node.setId(rs.getString("id"));
        node.setKind(NodeKind.valueOf(rs.getString("kind")));
        node.setName(rs.getString("name"));
        node.setQualifiedName(rs.getString("qualified_name"));
        node.setFilePath(rs.getString("file_path"));
        node.setLanguage(Language.valueOf(rs.getString("language")));
        node.setStartLine(rs.getInt("start_line"));
        node.setEndLine(rs.getInt("end_line"));
        node.setStartColumn(rs.getInt("start_column"));
        node.setEndColumn(rs.getInt("end_column"));
        node.setDocstring(rs.getString("docstring"));
        node.setSignature(rs.getString("signature"));
        
        String visibilityStr = rs.getString("visibility");
        node.setVisibility(visibilityStr != null ? Visibility.valueOf(visibilityStr) : null);
        
        node.setExported(rs.getInt("is_exported") == 1);
        node.setAsync(rs.getInt("is_async") == 1);
        node.setStatic(rs.getInt("is_static") == 1);
        node.setAbstract(rs.getInt("is_abstract") == 1);
        
        node.setDecorators(deserializeList(rs.getString("decorators")));
        node.setTypeParameters(deserializeList(rs.getString("type_parameters")));
        node.setReturnType(rs.getString("return_type"));
        node.setUpdatedAt(rs.getLong("updated_at"));
        
        return node;
    }

    private Edge mapResultSetToEdge(ResultSet rs) throws SQLException {
        Edge edge = new Edge();
        edge.setSource(rs.getString("source"));
        edge.setTarget(rs.getString("target"));
        edge.setKind(EdgeKind.valueOf(rs.getString("kind")));
        edge.setMetadata((Map<String, Object>) deserializeObject(rs.getString("metadata")));
        edge.setLine(rs.getInt("line"));
        edge.setColumn(rs.getInt("col"));
        edge.setProvenance(rs.getString("provenance"));
        
        return edge;
    }

    private String serializeList(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialize list: {}", e.getMessage());
            return null;
        }
    }

    private List<String> deserializeList(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, 
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            logger.warn("Failed to deserialize list: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String serializeObject(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialize object: {}", e.getMessage());
            return null;
        }
    }

    private Object deserializeObject(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to deserialize object: {}", e.getMessage());
            return null;
        }
    }
}