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
         args = new String[]{"init","-f","-p","/Users/wugao-pc/Desktop/Project/codegraph4j"};
               int exitCode = new CommandLine(new CodeGraphCli()).execute(args);
               args = new String[]{"index","-p","/Users/wugao-pc/Desktop/Project/codegraph4j","--force"};
               exitCode = new CommandLine(new CodeGraphCli()).execute(args);
           System.exit(exitCode);
    }
       
}
/**
     SELECT 
    e.id, e.kind,
    e.source, src.name AS source_name,
    e.target, tgt.name AS target_name
FROM edges e
LEFT JOIN nodes src ON e.source = src.id
LEFT JOIN nodes tgt ON e.target = tgt.id
WHERE e.kind = 'CALLS' and src.name is ='handleSearch'
ORDER BY e.id
LIMIT 100
 */
