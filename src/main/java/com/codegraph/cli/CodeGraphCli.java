package com.codegraph.cli;

import com.codegraph.cli.commands.*;
import com.codegraph.installer.InstallCommand;
import com.codegraph.installer.UninstallCommand;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "codegraph4j",
    version = "1.0.0-SNAPSHOT",
    description = "CodeGraph4j - Java 代码语义知识图谱，为 AI 编码助手提供代码理解能力",
    subcommands = {
        InitCommand.class,
        IndexCommand.class,
        StatusCommand.class,
        SyncCommand.class,
        ServeCommand.class,
        InstallCommand.class,
        UninstallCommand.class
    }
)
public class CodeGraphCli {
    
    public static void main(String[] args) {
        int exitCode = new CommandLine(new CodeGraphCli()).execute(args);
        System.exit(exitCode);
    }
}