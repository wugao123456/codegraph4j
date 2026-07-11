package com.codegraph.cli;

import java.io.File;

import org.slf4j.LoggerFactory;

import com.codegraph.cli.commands.*;
import com.codegraph.config.CodeGraphConfig;
import com.codegraph.installer.InstallCommand;
import com.codegraph.installer.UninstallCommand;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
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

    private CodeGraphConfig config;

    public static void main(String[] args) {
      //  args = new String[]{"index","-p","/Users/wugao-pc/Desktop/Project/codegraph4j","--force"};
        String projectPath = extractProjectPath(args);

        CodeGraphCli cli = new CodeGraphCli();
        cli.config = new CodeGraphConfig(projectPath, null);
        cli.setupFileLogging();

        int exitCode = new CommandLine(cli).execute(args);
        System.exit(exitCode);
    }

    /**
     * 从命令行参数中提取 -p / --project 指定的项目路径。
     * 若未指定，默认使用当前工作目录。
     */
    private static String extractProjectPath(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("-p".equals(args[i]) || "--project".equals(args[i])) {
                return args[i + 1];
            }
        }
        return System.getProperty("user.dir", ".");
    }

    /**
     * 设置文件日志，输出到 projectPath/.codegraph/logs/codegraph4j-mcp.log。
     * 在 main 中调用，确保所有后续日志都写入文件。
     *
     * <p>日志目录由 CodeGraphConfig 动态决定，日志包和级别从 logback.xml 读取。
     * 此方法只追加文件输出目标，不修改任何 logger 的级别。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void setupFileLogging() {
        if (config == null) return;

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        // 如果已存在同名的 MCP_FILE appender，跳过重复创建
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        if (rootLogger.getAppender("MCP_FILE") != null) {
            return;
        }

        File logDir = config.getLogDir();
        logDir.mkdirs();

        String logPath = new File(logDir, "codegraph4j-mcp.log").getAbsolutePath();

        RollingFileAppender fileAppender = new RollingFileAppender();
        fileAppender.setName("MCP_FILE");
        fileAppender.setContext(context);
        fileAppender.setFile(logPath);
        fileAppender.setAppend(true);

        TimeBasedRollingPolicy rollingPolicy = new TimeBasedRollingPolicy();
        rollingPolicy.setContext(context);
        rollingPolicy.setFileNamePattern(logPath + ".%d{yyyy-MM-dd}");
        rollingPolicy.setMaxHistory(5);
        rollingPolicy.setParent(fileAppender);
        rollingPolicy.start();

        fileAppender.setRollingPolicy(rollingPolicy);

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
        encoder.start();

        fileAppender.setEncoder(encoder);
        fileAppender.start();

        // 遍历 logback.xml 中所有已配置了 STDOUT appender 的 logger，
        // 给它们追加文件 appender。包名和日志级别从 logback.xml 读取，此处不硬编码。
        for (Logger logger : context.getLoggerList()) {
            if (logger.getAppender("STDOUT") != null) {
                logger.addAppender(fileAppender);
            }
        }
    }

}
