package com.codegraph.extraction;

import com.codegraph.cli.CodeGraphCli;

import picocli.CommandLine;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * TreeSitter Java 解析器单元测试（从旧 JavaParser 迁移）
 */
public class InitTest {


    public static void  main(String[] args) {
         args = new String[]{"init","-f","-p","/Users/wugao-pc/Desktop/Project/stream"};
               int exitCode = new CommandLine(new CodeGraphCli()).execute(args);
               args = new String[]{"index","-p","/Users/wugao-pc/Desktop/Project/stream"};
               exitCode = new CommandLine(new CodeGraphCli()).execute(args);
           System.exit(exitCode);
    }
       
}
    