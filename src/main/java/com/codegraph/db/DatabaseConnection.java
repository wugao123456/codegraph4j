package com.codegraph.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseConnection implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseConnection.class);
    
    private Connection connection;
    private String databasePath;
    private boolean walEnabled;

    public DatabaseConnection(String databasePath) {
        this.databasePath = databasePath;
    }

    public void open() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found", e);
        }
        
        String jdbcUrl = "jdbc:sqlite:" + databasePath;
        connection = DriverManager.getConnection(jdbcUrl);
        
        if (!walEnabled) {
            enableWAL();
        }
        
        connection.setAutoCommit(false);
        
        logger.info("Database opened: {}", databasePath);
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
                logger.info("Database closed: {}", databasePath);
            } catch (SQLException e) {
                logger.warn("Failed to close database: {}", e.getMessage());
            }
        }
    }

    private void enableWAL() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA synchronous=NORMAL");
            walEnabled = true;
            logger.debug("WAL mode enabled");
        }
    }

    public void beginTransaction() throws SQLException {
        connection.setAutoCommit(false);
    }

    public void commit() throws SQLException {
        connection.commit();
    }

    public void rollback() {
        try {
            connection.rollback();
        } catch (SQLException e) {
            logger.warn("Failed to rollback transaction: {}", e.getMessage());
        }
    }

    public Connection getConnection() {
        return connection;
    }

    public String getDatabasePath() {
        return databasePath;
    }

    public boolean isOpen() {
        return connection != null && !isConnectionClosed();
    }

    private boolean isConnectionClosed() {
        try {
            return connection.isClosed();
        } catch (SQLException e) {
            return true;
        }
    }

    public void executeScript(String script) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(script);
        }
    }
}