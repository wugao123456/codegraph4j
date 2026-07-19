package com.codegraph.cli.commands;

import com.codegraph.config.ProjectOption;
import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;
import com.codegraph.sync.FileWatcher;
import com.codegraph.sync.SyncOrchestrator;
import com.codegraph.sync.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@CommandLine.Command(
    name = "index",
    description = "Index project files"
)
public class IndexCommand implements Runnable {
    
    private static final Logger logger = LoggerFactory.getLogger(IndexCommand.class);
    
    @CommandLine.Mixin
    private ProjectOption projectOpt = new ProjectOption();
    
    @CommandLine.Option(names = {"--force"}, 
        description = "Force re-index all files", 
        defaultValue = "false")
    private boolean force;
    
    @CommandLine.Option(names = {"--watch"}, 
        description = "Watch for file changes", 
        defaultValue = "false")
    private boolean watch;
    
    @Override
    public void run() {
        logger.info("========== 开始索引流程 ==========");
        logger.info("项目路径: {}", projectOpt.projectRoot);
        logger.info("强制重新索引: {}, 监听模式: {}", force, watch);
        
        Path projectPath = Paths.get(projectOpt.projectRoot).toAbsolutePath();
        File dbFile = new File(projectPath.toFile(), ".codegraph/codegraph4j.db");
        
        logger.info("数据库文件: {}", dbFile.getAbsolutePath());
        
        if (!dbFile.exists()) {
            System.err.println("Error: CodeGraph not initialized. Run 'init' first.");
            logger.error("数据库文件不存在: {}", dbFile.getAbsolutePath());
            return;
        }
        
        System.out.println("Indexing project: " + projectPath);
        
        try (DatabaseConnection db = new DatabaseConnection(dbFile.getAbsolutePath())) {
            db.open();
            QueryBuilder queryBuilder = new QueryBuilder(db);
            
            SyncOrchestrator orchestrator = new SyncOrchestrator();
            SyncResult result = orchestrator.sync(projectPath, queryBuilder, force, 
                fileName -> System.out.println("  Indexed: " + fileName));
            
            db.close();
            
            System.out.println();
            System.out.println("========== 索引完成 ==========");
            System.out.println("✓ Indexing completed in " + result.getDurationMs() + "ms");
            System.out.println("  检查文件数: " + result.getFilesChecked());
            System.out.println("  新增文件: " + result.getFilesAdded());
            System.out.println("  修改文件: " + result.getFilesModified());
            System.out.println("  删除文件: " + result.getFilesRemoved());
            System.out.println("  更新节点: " + result.getNodesUpdated());
            System.out.println("============================");
            
            logger.info("========== 索引完成 ==========");
            logger.info("checked={}, added={}, modified={}, removed={}, nodesUpdated={}, durationMs={}", 
                result.getFilesChecked(), result.getFilesAdded(), result.getFilesModified(),
                result.getFilesRemoved(), result.getNodesUpdated(), result.getDurationMs());
            
            if (watch) {
                System.out.println();
                startWatch(projectPath);
            }
            
        } catch (Exception e) {
            logger.error("索引流程失败", e);
            System.err.println("Error: " + e.getMessage());
        }
    }
    
    private void startWatch(Path projectPath) {
        System.out.println("Watching for file changes...");
        System.out.println("(Press Ctrl+C to stop)");
        
        CountDownLatch latch = new CountDownLatch(1);
        
        FileWatcher watcher = new FileWatcher(projectPath, () -> {
            try {
                File dbFile = new File(projectPath.toFile(), ".codegraph/codegraph4j.db");
                try (DatabaseConnection db = new DatabaseConnection(dbFile.getAbsolutePath())) {
                    db.open();
                    QueryBuilder queryBuilder = new QueryBuilder(db);
                    SyncOrchestrator orchestrator = new SyncOrchestrator();
                    SyncResult result = orchestrator.sync(projectPath, queryBuilder, force, null);
                    db.close();
                    return result;
                }
            } catch (Exception e) {
                logger.error("Watch sync failed", e);
                throw new RuntimeException(e);
            }
        });
        
        watcher.setOnSyncComplete(result -> {
            System.out.println("[watch] Sync completed: " + 
                result.getFilesChanged() + " files changed, " + 
                result.getDurationMs() + "ms");
        });
        
        watcher.setOnSyncError(error -> {
            System.err.println("[watch] Sync error: " + error.getMessage());
        });
        
        watcher.setOnDegraded(reason -> {
            System.err.println("[watch] Watcher degraded: " + reason);
        });
        
        boolean started = watcher.start();
        if (!started) {
            System.out.println("File watcher could not start. Use 'codegraph4j sync' to manually sync.");
            return;
        }
        
        // Keep the main thread alive
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            watcher.stop();
            latch.countDown();
        }));
        
        try {
            latch.await();
        } catch (InterruptedException e) {
            watcher.stop();
            Thread.currentThread().interrupt();
        }
    }
}
