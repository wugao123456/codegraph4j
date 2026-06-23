package com.codegraph.cli.commands;

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
    description = "Initialize CodeGraph for a project"
)
public class InitCommand implements Runnable {
    
    private static final Logger logger = LoggerFactory.getLogger(InitCommand.class);
    
    @CommandLine.Option(names = {"-p", "--project"}, 
        description = "Project root directory", 
        defaultValue = ".")
    private String projectRoot;
    
    @CommandLine.Option(names = {"-f", "--force"}, 
        description = "Overwrite existing database", 
        defaultValue = "false")
    private boolean force;

    @Override
    public void run() {
        logger.info("Initializing CodeGraph for project: {}", projectRoot);
        
        File projectDir = new File(projectRoot);
        if (!projectDir.exists()) {
            System.err.println("Project directory does not exist: " + projectRoot);
            return;
        }
        
        File dbDir = new File(projectDir, ".codegraph");
        if (!dbDir.exists()) {
            boolean created = dbDir.mkdirs();
            if (!created) {
                System.err.println("Failed to create .codegraph directory");
                return;
            }
        }
        
        File dbFile = new File(dbDir, "codegraph.sqlite");
        if (dbFile.exists() && !force) {
            System.err.println("Database already exists: " + dbFile.getAbsolutePath());
            System.err.println("Use -f/--force to overwrite");
            return;
        }
        
        try {
            DatabaseConnection db = new DatabaseConnection(dbFile.getAbsolutePath());
            db.open();
            
            SchemaManager schemaManager = new SchemaManager(db);
            if (!schemaManager.schemaExists()) {
                schemaManager.initSchema();
                System.out.println("✓ Database schema initialized");
            } else {
                System.out.println("✓ Database already initialized");
            }
            
            db.close();
            
            System.out.println("✓ CodeGraph initialized successfully");
            System.out.println("  Project: " + projectRoot);
            System.out.println("  Database: " + dbFile.getAbsolutePath());
            
        } catch (SQLException | IOException e) {
            logger.error("Failed to initialize CodeGraph", e);
            System.err.println("Error initializing CodeGraph: " + e.getMessage());
        }
    }
}