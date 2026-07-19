package com.codegraph.cli.commands;

import com.codegraph.config.ProjectOption;
import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.SchemaManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;

@CommandLine.Command(
    name = "init",
    description = "Initialize CodeGraph4j database"
)
public class InitCommand implements Runnable {
    
    private static final Logger logger = LoggerFactory.getLogger(InitCommand.class);
    
    @CommandLine.Mixin
    private ProjectOption projectOpt = new ProjectOption();
    
    @CommandLine.Option(names = {"-f", "--force"}, 
        description = "Overwrite existing database", 
        defaultValue = "false")
    private boolean force;

    @Override
    public void run() {
        logger.info("Initializing CodeGraph4j for project: {}", projectOpt.projectRoot);
        
        File projectDir = new File(projectOpt.projectRoot);
        
        // 边界检查：项目目录不存在
        if (!projectDir.exists()) {
            System.err.println("Error: Project directory does not exist: " + projectOpt.projectRoot);
            System.exit(1);
            return;
        }
        
        // 边界检查：项目路径不是目录
        if (!projectDir.isDirectory()) {
            System.err.println("Error: Project path is not a directory: " + projectOpt.projectRoot);
            System.exit(1);
            return;
        }
        
        File dbDir = new File(projectDir, ".codegraph");
        
        // 边界检查：.codegraph 已存在但不是目录
        if (dbDir.exists() && !dbDir.isDirectory()) {
            System.err.println("Error: .codegraph exists but is not a directory: " + dbDir.getAbsolutePath());
            System.exit(1);
            return;
        }
        
        if (!dbDir.exists()) {
            boolean created = dbDir.mkdirs();
            if (!created) {
                System.err.println("Error: Failed to create .codegraph directory: " + dbDir.getAbsolutePath());
                System.exit(1);
                return;
            }
        }
        
        File dbFile = new File(dbDir, "codegraph4j.db");
        if (dbFile.exists()) {
            if (!force) {
                System.err.println("Database already exists: " + dbFile.getAbsolutePath());
                System.err.println("Use -f/--force to overwrite");
                System.exit(1);
                return;
            }
            // 强制删除现有数据库
            boolean deleted = dbFile.delete();
            if (!deleted) {
                System.err.println("Error: Failed to delete existing database: " + dbFile.getAbsolutePath());
                System.err.println("Hint: The file may be locked by another process");
                System.exit(1);
                return;
            }
            System.out.println("✓ Existing database deleted");
        }
        
        // 使用 try-with-resources 确保数据库连接正确关闭
        try (DatabaseConnection db = new DatabaseConnection(dbFile.getAbsolutePath())) {
            db.open();
            
            SchemaManager schemaManager = new SchemaManager(db);
            if (!schemaManager.schemaExists()) {
                schemaManager.initSchema();
                System.out.println("✓ Database schema initialized");
            } else {
                System.out.println("✓ Database schema ready");
            }
            
            System.out.println("✓ CodeGraph4j initialized successfully");
            System.out.println("  Project: " + projectDir.getAbsolutePath());
            System.out.println("  Database: " + dbFile.getAbsolutePath());
            
        } catch (SQLException e) {
            logger.error("Failed to initialize CodeGraph4j", e);
            System.err.println("Error: Database error - " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            logger.error("Failed to initialize CodeGraph4j", e);
            System.err.println("Error: I/O error - " + e.getMessage());
            System.exit(1);
        }
    }
}
