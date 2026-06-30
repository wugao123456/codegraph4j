package com.codegraph.mcp;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ExploreLogRollingTest {

    @Test
    public void testRotateExploreLogs() throws Exception {
        String testProjectPath = System.getProperty("java.io.tmpdir") + "/codegraph_test_logs";
        Path logsDir = Paths.get(testProjectPath, ".codegraph", "logs");
        
        cleanup(logsDir);
        Files.createDirectories(logsDir);
        
        try {
            for (int i = 1; i <= 5; i++) {
                String timestamp = String.format("20240101_00000%02d_000", i);
                String fileName = "explore_" + timestamp + "_test_query_" + i + ".md";
                Path logFile = logsDir.resolve(fileName);
                Files.write(logFile, ("Test content " + i).getBytes());
            }
            
            try (Stream<Path> stream = Files.list(logsDir)) {
                List<Path> logFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("explore_"))
                    .collect(Collectors.toList());
                assertEquals(5, logFiles.size());
            }
            
            Method rotateMethod = MCPToolHandler.class.getDeclaredMethod("rotateExploreLogs", Path.class);
            rotateMethod.setAccessible(true);
            rotateMethod.invoke(null, logsDir);
            
            try (Stream<Path> stream = Files.list(logsDir)) {
                List<Path> logFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("explore_"))
                    .sorted()
                    .collect(Collectors.toList());
                
                assertEquals(3, logFiles.size());
                assertEquals("explore_20240101_0000003_000_test_query_3.md", logFiles.get(0).getFileName().toString());
                assertEquals("explore_20240101_0000004_000_test_query_4.md", logFiles.get(1).getFileName().toString());
                assertEquals("explore_20240101_0000005_000_test_query_5.md", logFiles.get(2).getFileName().toString());
            }
            
            System.out.println("✅ rotateExploreLogs 测试通过！");
            
        } finally {
            cleanup(logsDir);
        }
    }
    
    @Test
    public void testWriteExploreLog() throws Exception {
        String testProjectPath = System.getProperty("java.io.tmpdir") + "/codegraph_test_logs";
        Path logsDir = Paths.get(testProjectPath, ".codegraph", "logs");
        
        cleanup(logsDir);
        
        try {
            Object handler = MCPToolHandler.class.getDeclaredConstructor(String.class, 
                    com.codegraph.db.DatabaseConnection.class, 
                    com.codegraph.db.QueryBuilder.class)
                    .newInstance(testProjectPath, null, null);
            
            Method writeLogMethod = MCPToolHandler.class.getDeclaredMethod("writeExploreLog", String.class, String.class);
            writeLogMethod.setAccessible(true);
            
            writeLogMethod.invoke(handler, "test_query", "Test explore content");
            
            assertTrue(Files.exists(logsDir));
            
            try (Stream<Path> stream = Files.list(logsDir)) {
                List<Path> logFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("explore_"))
                    .collect(Collectors.toList());
                
                assertEquals(1, logFiles.size());
                
                Path logFile = logFiles.get(0);
                assertTrue(logFile.getFileName().toString().startsWith("explore_"));
                assertTrue(logFile.getFileName().toString().endsWith("_test_query.md"));
                
                String content = new String(Files.readAllBytes(logFile));
                assertTrue(content.contains("# CodeGraph Explore Result"));
                assertTrue(content.contains("test_query"));
                assertTrue(content.contains("Test explore content"));
            }
            
            System.out.println("✅ writeExploreLog 测试通过！");
            
        } finally {
            cleanup(logsDir);
        }
    }
    
    @Test
    public void testWriteAndRollMultipleLogs() throws Exception {
        String testProjectPath = System.getProperty("java.io.tmpdir") + "/codegraph_test_logs";
        Path logsDir = Paths.get(testProjectPath, ".codegraph", "logs");
        
        cleanup(logsDir);
        
        try {
            Object handler = MCPToolHandler.class.getDeclaredConstructor(String.class, 
                    com.codegraph.db.DatabaseConnection.class, 
                    com.codegraph.db.QueryBuilder.class)
                    .newInstance(testProjectPath, null, null);
            
            Method writeLogMethod = MCPToolHandler.class.getDeclaredMethod("writeExploreLog", String.class, String.class);
            writeLogMethod.setAccessible(true);
            
            for (int i = 1; i <= 5; i++) {
                writeLogMethod.invoke(handler, "query_" + i, "Content for query " + i);
                Thread.sleep(50);
            }
            
            try (Stream<Path> stream = Files.list(logsDir)) {
                List<Path> logFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("explore_"))
                    .sorted()
                    .collect(Collectors.toList());
                
                assertEquals(3, logFiles.size());
                System.out.println("写入5个日志后，滚动保留了" + logFiles.size() + "个文件");
                for (Path file : logFiles) {
                    System.out.println("  - " + file.getFileName());
                }
            }
            
            System.out.println("✅ 日志写入和滚动综合测试通过！");
            
        } finally {
            cleanup(logsDir);
        }
    }
    
    private void cleanup(Path logsDir) {
        if (Files.exists(logsDir)) {
            try (Stream<Path> stream = Files.list(logsDir)) {
                stream.filter(Files::isRegularFile)
                      .filter(p -> p.getFileName().toString().startsWith("explore_"))
                      .forEach(p -> {
                          try { Files.delete(p); } catch (IOException e) {}
                      });
            } catch (IOException e) {}
        }
    }
    
    public static void main(String[] args) throws Exception {
        ExploreLogRollingTest test = new ExploreLogRollingTest();
        
        System.out.println("=== 测试1: rotateExploreLogs ===");
        test.testRotateExploreLogs();
        System.out.println();
        
        System.out.println("=== 测试2: writeExploreLog ===");
        test.testWriteExploreLog();
        System.out.println();
        
        System.out.println("=== 测试3: writeAndRollMultipleLogs ===");
        test.testWriteAndRollMultipleLogs();
        System.out.println();
        
        System.out.println("=== 所有测试完成 ===");
    }
}