package com.codegraph.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

public class SchemaManager {
    private static final Logger logger = LoggerFactory.getLogger(SchemaManager.class);
    private static final Pattern COMMENT_PATTERN = Pattern.compile("--.*$", Pattern.MULTILINE);
    
    private final DatabaseConnection db;

    public SchemaManager(DatabaseConnection db) {
        this.db = db;
    }

    public void initSchema() throws SQLException, IOException {
        logger.info("Initializing database schema");
        
        String schemaSql = loadSchemaFromResources();
        
        try {
            db.beginTransaction();
            db.executeScript(schemaSql);
            db.commit();
            logger.info("Schema initialized successfully");
        } catch (SQLException e) {
            db.rollback();
            throw e;
        }
    }

    private String loadSchemaFromResources() throws IOException {
        InputStream is = getClass().getClassLoader().getResourceAsStream("db/schema.sql");
        if (is == null) {
            throw new IOException("schema.sql not found in resources");
        }
        
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        
        return sb.toString();
    }

    public int getCurrentVersion() throws SQLException {
        String sql = "SELECT MAX(version) FROM schema_versions";
        try (Statement stmt = db.getConnection().createStatement()) {
            java.sql.ResultSet rs = stmt.executeQuery(sql);
            if (rs.next()) {
                return rs.getInt(1);
            }
            return 0;
        }
    }

    public boolean schemaExists() throws SQLException {
        String sql = "SELECT name FROM sqlite_master WHERE type='table' AND name='nodes'";
        try (Statement stmt = db.getConnection().createStatement()) {
            java.sql.ResultSet rs = stmt.executeQuery(sql);
            return rs.next();
        }
    }

    public void createIndexes() throws SQLException {
        logger.info("Creating database indexes");
        
        String[] indexSql = {
            "CREATE INDEX IF NOT EXISTS idx_nodes_kind ON nodes(kind)",
            "CREATE INDEX IF NOT EXISTS idx_nodes_name ON nodes(name)",
            "CREATE INDEX IF NOT EXISTS idx_nodes_qualified_name ON nodes(qualified_name)",
            "CREATE INDEX IF NOT EXISTS idx_nodes_file_path ON nodes(file_path)",
            "CREATE INDEX IF NOT EXISTS idx_nodes_language ON nodes(language)",
            "CREATE INDEX IF NOT EXISTS idx_nodes_file_line ON nodes(file_path, start_line)",
            "CREATE INDEX IF NOT EXISTS idx_nodes_lower_name ON nodes(lower(name))",
            "CREATE INDEX IF NOT EXISTS idx_edges_kind ON edges(kind)",
            "CREATE INDEX IF NOT EXISTS idx_edges_source_kind ON edges(source, kind)",
            "CREATE INDEX IF NOT EXISTS idx_edges_target_kind ON edges(target, kind)",
            "CREATE INDEX IF NOT EXISTS idx_files_language ON files(language)",
            "CREATE INDEX IF NOT EXISTS idx_files_modified_at ON files(modified_at)",
            "CREATE INDEX IF NOT EXISTS idx_unresolved_from_node ON unresolved_refs(from_node_id)",
            "CREATE INDEX IF NOT EXISTS idx_unresolved_name ON unresolved_refs(reference_name)",
            "CREATE INDEX IF NOT EXISTS idx_unresolved_file_path ON unresolved_refs(file_path)",
            "CREATE INDEX IF NOT EXISTS idx_unresolved_from_name ON unresolved_refs(from_node_id, reference_name)",
            "CREATE INDEX IF NOT EXISTS idx_edges_provenance ON edges(provenance)"
        };
        
        try {
            db.beginTransaction();
            try (Statement stmt = db.getConnection().createStatement()) {
                for (String sql : indexSql) {
                    stmt.execute(sql);
                }
            }
            db.commit();
            logger.info("Indexes created successfully");
        } catch (SQLException e) {
            db.rollback();
            throw e;
        }
    }
}