package com.codegraph.cli;

import com.codegraph.cli.commands.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "codegraph",
    version = "1.0.0-SNAPSHOT",
    description = "CodeGraph Java Edition - Semantic code knowledge graph for AI coding assistants",
    subcommands = {
        InitCommand.class,
        IndexCommand.class,
        StatusCommand.class,
        ServeCommand.class
    }
)
public class CodeGraphCli {
    
    public static void main(String[] args) {
        int exitCode = new CommandLine(new CodeGraphCli()).execute(args);
        System.exit(exitCode);
    }
}