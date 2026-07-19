package com.codegraph.config;

import picocli.CommandLine;

/**
 * picocli @Mixin — 统一 6 个命令类中重复定义的 -p, --project 选项。
 */
public class ProjectOption {

    @CommandLine.Option(names = {"-p", "--project"},
        description = "Project root directory",
        defaultValue = ".")
    public String projectRoot;
}
