package com.codegraph.cli.commands;

import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;
import com.codegraph.sync.SyncOrchestrator;
import com.codegraph.sync.SyncResult;
import com.codegraph.utils.FileLockUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * sync 命令 — 增量同步索引。
 * 对账文件系统与数据库，仅处理变更的文件。
 */
@CommandLine.Command(
        name = "sync",
        description = "Incrementally sync the code graph index"
)
public class SyncCommand implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(SyncCommand.class);

    @CommandLine.Mixin
    private ProjectOption projectOpt = new ProjectOption();

    @CommandLine.Option(names = {"-q", "--quiet"},
            description = "Quiet mode (for git hook triggers)",
            defaultValue = "false")
    private boolean quiet;

    @Override
    public void run() {
        Path projectPath = Paths.get(projectOpt.projectRoot).toAbsolutePath();
        File dbFile = new File(projectPath.toFile(), ".codegraph/codegraph4j.db");

        if (!dbFile.exists()) {
            if (!quiet) {
                System.err.println("Error: CodeGraph not initialized. Run 'init' first.");
            }
            logger.error("数据库文件不存在: {}", dbFile.getAbsolutePath());
            return;
        }

        // 获取跨进程文件锁
        Path lockFile = projectPath.resolve(".codegraph/codegraph.lock");
        FileLockUtil lock = FileLockUtil.tryLock(lockFile, false);
        if (lock == null) {
            if (!quiet) {
                System.err.println("Error: Could not acquire file lock. Another sync may be in progress.");
            }
            logger.warn("无法获取文件锁，可能有另一个同步进程正在运行");
            return;
        }

        try (DatabaseConnection db = new DatabaseConnection(dbFile.getAbsolutePath())) {
            db.open();
            QueryBuilder queryBuilder = new QueryBuilder(db);

            if (!quiet) {
                System.out.println("Syncing project: " + projectPath);
            }

            SyncOrchestrator orchestrator = new SyncOrchestrator();
            SyncResult result = orchestrator.sync(
                    projectPath, queryBuilder, false,
                    quiet ? null : fileName -> {
                        // 进度回调（静默模式下不输出）
                    }
            );

            db.close();

            if (!quiet) {
                System.out.println();
                System.out.println("========== 同步完成 ==========");
                System.out.println("✓ Sync completed in " + result.getDurationMs() + "ms");
                System.out.println("  检查文件数: " + result.getFilesChecked());
                System.out.println("  新增文件: " + result.getFilesAdded());
                System.out.println("  修改文件: " + result.getFilesModified());
                System.out.println("  删除文件: " + result.getFilesRemoved());
                System.out.println("  更新节点: " + result.getNodesUpdated());
                System.out.println("==============================");
            }

            logger.info("同步完成: checked={}, added={}, modified={}, removed={}, nodesUpdated={}, durationMs={}",
                    result.getFilesChecked(), result.getFilesAdded(), result.getFilesModified(),
                    result.getFilesRemoved(), result.getNodesUpdated(), result.getDurationMs());

        } catch (Exception e) {
            logger.error("同步失败", e);
            if (!quiet) {
                System.err.println("Error: " + e.getMessage());
            }
        } finally {
            lock.release();
        }
    }
}
