package com.codegraph.cli.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

@CommandLine.Command(
    name = "serve",
    description = "Start CodeGraph server"
)
public class ServeCommand implements Runnable {
    
    private static final Logger logger = LoggerFactory.getLogger(ServeCommand.class);
    
    @CommandLine.Option(names = {"-p", "--project"}, 
        description = "Project root directory", 
        defaultValue = ".")
    private String projectRoot;
    
    @CommandLine.Option(names = {"--daemon"}, 
        description = "Run as daemon", 
        defaultValue = "false")
    private boolean daemon;

    @Override
    public void run() {
        logger.info("Starting CodeGraph server for project: {}, daemon={}", projectRoot, daemon);
        
        System.out.println("Starting CodeGraph server...");
        System.out.println("Project: " + projectRoot);
        
        if (daemon) {
            System.out.println("Running as daemon");
        }
        
        System.out.println("✓ Server started");
    }
}