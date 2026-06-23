package com.codegraph.cli.commands;

import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;
import java.sql.SQLException;

@CommandLine.Command(
    name = "status",
    description = "Show CodeGraph status"
)
public class StatusCommand implements Runnable {
    
    private static final Logger logger = LoggerFactory.getLogger(StatusCommand.class);
    
    @CommandLine.Option(names = {"-p", "--project"}, 
        description = "Project root directory", 
        defaultValue = ".")
    private String projectRoot;

    @Override
    public void run() {
        logger.info("Checking status for project: {}", projectRoot);
        
        File dbFile = new File(projectRoot, ".codegraph/codegraph.sqlite");
        
        if (!dbFile.exists()) {
            System.out.println("CodeGraph not initialized for this project");
            System.out.println("Run 'codegraph init' to initialize");
            return;
        }
        
        try {
            DatabaseConnection db = new DatabaseConnection(dbFile.getAbsolutePath());
            db.open();
            
            QueryBuilder queries = new QueryBuilder(db);
            
            long nodeCount = queries.getNodeCount();
            long edgeCount = queries.getEdgeCount();
            
            db.close();
            
            System.out.println("CodeGraph Status");
            System.out.println("================");
            System.out.println("Project: " + projectRoot);
            System.out.println("Database: " + dbFile.getAbsolutePath());
            System.out.println("Nodes: " + nodeCount);
            System.out.println("Edges: " + edgeCount);
            
        } catch (SQLException e) {
            logger.error("Failed to check status", e);
            System.err.println("Error checking status: " + e.getMessage());
        }
    }
}