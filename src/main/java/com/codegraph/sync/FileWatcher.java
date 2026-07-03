package com.codegraph.sync;

import com.codegraph.utils.FileFilterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;

/**
 * 文件监听器 — 监听项目目录的文件变更并触发去抖同步。
 * 使用 java.nio.file.WatchService，参照 codegraph FileWatcher。
 *
 * 平台策略：
 * - macOS/Windows: 递归注册根目录
 * - Linux: 逐目录注册
 */
public class FileWatcher {

    private static final Logger logger = LoggerFactory.getLogger(FileWatcher.class);

    /** 锁竞争最大重试次数 */
    private static final int MAX_LOCK_RETRIES = 5;
    /** 锁重试最大延迟（毫秒） */
    private static final long MAX_LOCK_RETRY_DELAY_MS = 30_000;
    /** Linux 上最大目录监听数 */
    private static final int DEFAULT_MAX_DIR_WATCHES = 50_000;
    /** 默认去抖延迟（毫秒） */
    private static final long DEFAULT_DEBOUNCE_MS = 2000;

    private final Path projectRoot;
    private final Callable<SyncResult> syncFn;
    private final long debounceMs;
    private final List<String> excludePatterns;

    private WatchService watchService;
    private final Map<WatchKey, Path> watchKeys = new ConcurrentHashMap<WatchKey, Path>();
    private final Map<String, PendingFileInfo> pendingFiles = new ConcurrentHashMap<String, PendingFileInfo>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "codegraph-watch");
                    t.setDaemon(true);
                    return t;
                }
            });

    private ScheduledFuture<?> debounceFuture;
    private Future<?> watchLoopFuture;
    private int lockRetryCount;
    private volatile String degradedReason;
    private volatile boolean stopped;
    private volatile boolean syncing;
    private long syncStartedMs;
    private boolean ready;

    // 回调
    private java.util.function.Consumer<SyncResult> onSyncComplete;
    private java.util.function.Consumer<Throwable> onSyncError;
    private java.util.function.Consumer<String> onDegraded;

    public FileWatcher(Path projectRoot, Callable<SyncResult> syncFn) {
        this(projectRoot, syncFn, DEFAULT_DEBOUNCE_MS);
    }

    public FileWatcher(Path projectRoot, Callable<SyncResult> syncFn, long debounceMs) {
        this.projectRoot = projectRoot;
        this.syncFn = syncFn;
        this.debounceMs = debounceMs;
        this.excludePatterns = new ArrayList<>();
        this.excludePatterns.add(".git");
        this.excludePatterns.add(".codegraph");
        this.excludePatterns.add("node_modules");
        this.excludePatterns.add("target");
        this.excludePatterns.add("build");
        this.excludePatterns.add("dist");
        this.excludePatterns.add(".DS_Store");
        this.excludePatterns.add(".iml");
    }

    public void setOnSyncComplete(java.util.function.Consumer<SyncResult> onSyncComplete) {
        this.onSyncComplete = onSyncComplete;
    }

    public void setOnSyncError(java.util.function.Consumer<Throwable> onSyncError) {
        this.onSyncError = onSyncError;
    }

    public void setOnDegraded(java.util.function.Consumer<String> onDegraded) {
        this.onDegraded = onDegraded;
    }

    /**
     * 启动文件监听。返回 true 表示启动成功。
     */
    public boolean start() {
        if (watchService != null) return true;
        stopped = false;
        degradedReason = null;
        lockRetryCount = 0;

        String disabledReason = WatchPolicy.watchDisabledReason(projectRoot);
        if (disabledReason != null) {
            logger.debug("文件监听已禁用: {}", disabledReason);
            return false;
        }

        try {
            watchService = FileSystems.getDefault().newWatchService();

            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("mac") || os.contains("win")) {
                registerRecursive(projectRoot);
            } else {
                registerPerDirectory(projectRoot);
            }

            pendingFiles.clear();
            ready = true;

            watchLoopFuture = scheduler.submit(new Runnable() {
                @Override
                public void run() {
                    watchLoop();
                }
            });

            logger.info("文件监听已启动: projectRoot={}, debounceMs={}, watchedDirs={}",
                    projectRoot, debounceMs, watchKeys.size());
            return true;
        } catch (IOException e) {
            logger.warn("无法启动文件监听: {}", e.getMessage());
            degrade("无法启动 WatchService: " + e.getMessage());
            return false;
        }
    }

    /**
     * 停止文件监听。
     */
    public void stop() {
        stopped = true;

        if (debounceFuture != null) {
            debounceFuture.cancel(false);
            debounceFuture = null;
        }

        if (watchLoopFuture != null) {
            watchLoopFuture.cancel(true);
            watchLoopFuture = null;
        }

        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                logger.debug("关闭 WatchService 出错: {}", e.getMessage());
            }
            watchService = null;
        }

        watchKeys.clear();
        pendingFiles.clear();
        ready = false;

        logger.debug("文件监听已停止");
    }

    private void registerRecursive(Path dir) throws IOException {
        WatchKey key = dir.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        watchKeys.put(key, dir);
    }

    private void registerPerDirectory(Path dir) throws IOException {
        if (stopped || degradedReason != null) return;

        String rel = projectRoot.relativize(dir).toString();
        if (isAlwaysIgnored(rel)) return;

        if (watchKeys.size() >= getMaxDirWatches()) {
            logger.warn("目录监听数已达上限 ({})，跳过剩余目录", getMaxDirWatches());
            return;
        }

        try {
            WatchKey key = dir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            watchKeys.put(key, dir);

            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path subDir, BasicFileAttributes attrs) throws IOException {
                    if (subDir.equals(dir)) return FileVisitResult.CONTINUE;
                    String subRel = projectRoot.relativize(subDir).toString();
                    if (isAlwaysIgnored(subRel)) return FileVisitResult.SKIP_SUBTREE;
                    try {
                        WatchKey subKey = subDir.register(watchService,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_MODIFY,
                                StandardWatchEventKinds.ENTRY_DELETE);
                        watchKeys.put(subKey, subDir);
                        if (watchKeys.size() >= getMaxDirWatches()) {
                            return FileVisitResult.TERMINATE;
                        }
                    } catch (IOException e) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            logger.debug("注册目录监听失败: {} — {}", dir, e.getMessage());
        }
    }

    private void watchLoop() {
        while (!stopped && watchService != null) {
            WatchKey key;
            try {
                key = watchService.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (key == null) continue;

            Path dir = watchKeys.get(key);
            if (dir == null) {
                key.cancel();
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                Path name = (Path) event.context();
                Path child = dir.resolve(name);
                Path relPath;

                try {
                    relPath = projectRoot.relativize(child);
                } catch (IllegalArgumentException e) {
                    continue;
                }

                String relStr = relPath.toString();

                if (isAlwaysIgnored(relStr)) continue;

                // 新增目录：Linux 上需要注册新监听
                if (kind == StandardWatchEventKinds.ENTRY_CREATE &&
                        Files.isDirectory(child) &&
                        !isRecursivePlatform()) {
                    try {
                        WatchKey newKey = child.register(watchService,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_MODIFY,
                                StandardWatchEventKinds.ENTRY_DELETE);
                        watchKeys.put(newKey, child);
                    } catch (IOException e) {
                        // 忽略
                    }
                    continue;
                }

                if (FileFilterUtils.isSourceFile(relStr)) {
                    handleChange(relStr);
                }
            }

            if (!key.reset()) {
                watchKeys.remove(key);
            }
        }
    }

    private void handleChange(String relPath) {
        logger.debug("检测到文件变更: {}", relPath);

        if (ready) {
            long now = System.currentTimeMillis();
            PendingFileInfo existing = pendingFiles.get(relPath);
            PendingFileInfo info = new PendingFileInfo();
            info.firstSeenMs = existing != null ? existing.firstSeenMs : now;
            info.lastSeenMs = now;
            pendingFiles.put(relPath, info);
        }

        scheduleSync();
    }

    private synchronized void scheduleSync() {
        if (debounceFuture != null) {
            debounceFuture.cancel(false);
        }
        debounceFuture = scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                flush();
            }
        }, debounceMs, TimeUnit.MILLISECONDS);
    }

    private synchronized void scheduleRetrySync(long delayMs) {
        if (debounceFuture != null) {
            debounceFuture.cancel(false);
        }
        debounceFuture = scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                flush();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private synchronized void flush() {
        if (syncing || stopped) return;

        syncStartedMs = System.currentTimeMillis();
        syncing = true;

        try {
            SyncResult result = syncFn.call();
            lockRetryCount = 0;

            // 移除已处理文件的 pending 记录
            Iterator<Map.Entry<String, PendingFileInfo>> it = pendingFiles.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, PendingFileInfo> entry = it.next();
                if (entry.getValue().lastSeenMs <= syncStartedMs) {
                    it.remove();
                }
            }

            if (onSyncComplete != null) {
                onSyncComplete.accept(result);
            }
        } catch (Exception e) {
            if (isLockUnavailableError(e)) {
                lockRetryCount++;
                logger.debug("同步跳过：文件锁不可用 (retry {}/{})", lockRetryCount, MAX_LOCK_RETRIES);

                if (lockRetryCount > MAX_LOCK_RETRIES) {
                    degrade("CodeGraph file lock held by another process past the retry budget; " +
                            "auto-sync disabled. Run `codegraph4j sync` to refresh.");
                }
            } else {
                lockRetryCount = 0;
                Throwable error = e instanceof RuntimeException && e.getCause() != null
                        ? e.getCause() : e;
                logger.warn("监听同步失败: {}", error.getMessage());
                if (onSyncError != null) {
                    onSyncError.accept(error);
                }
            }
        } finally {
            syncing = false;

            if (!pendingFiles.isEmpty() && !stopped) {
                if (lockRetryCount > 0) {
                    long retryDelayMs = Math.min(
                            debounceMs * (1L << Math.max(0, lockRetryCount - 1)),
                            MAX_LOCK_RETRY_DELAY_MS);
                    scheduleRetrySync(retryDelayMs);
                } else {
                    scheduleSync();
                }
            }
        }
    }

    private synchronized void degrade(String reason) {
        if (degradedReason != null) return;
        degradedReason = reason;
        logger.warn("文件监听已降级: {}", reason);
        if (onDegraded != null) {
            onDegraded.accept(reason);
        }
        stop();
    }

    public boolean isActive() {
        return watchService != null && !stopped;
    }

    public boolean isDegraded() {
        return degradedReason != null;
    }

    public String getDegradedReason() {
        return degradedReason;
    }

    /**
     * 获取待同步文件快照。
     */
    public List<PendingFile> getPendingFiles() {
        List<PendingFile> result = new ArrayList<>();
        for (Map.Entry<String, PendingFileInfo> entry : pendingFiles.entrySet()) {
            PendingFile pf = new PendingFile();
            pf.path = entry.getKey();
            pf.firstSeenMs = entry.getValue().firstSeenMs;
            pf.lastSeenMs = entry.getValue().lastSeenMs;
            pf.indexing = syncing && syncStartedMs >= entry.getValue().lastSeenMs;
            result.add(pf);
        }
        return result;
    }

    private boolean isAlwaysIgnored(String relPath) {
        String top = relPath.split("[/\\\\]")[0];
        return ".git".equals(top) ||
                top.startsWith(".codegraph") ||
                "node_modules".equals(top) ||
                "target".equals(top) ||
                "build".equals(top) ||
                "dist".equals(top);
    }

    private boolean isRecursivePlatform() {
        String os = System.getProperty("os.name").toLowerCase();
        return os.contains("mac") || os.contains("win");
    }

    private int getMaxDirWatches() {
        String raw = System.getenv("CODEGRAPH_MAX_DIR_WATCHES");
        if (raw != null && raw.matches("\\d+")) {
            int n = Integer.parseInt(raw);
            if (n > 0) return n;
        }
        return DEFAULT_MAX_DIR_WATCHES;
    }

    private boolean isLockUnavailableError(Exception e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("lock") || msg.contains("Lock"));
    }

    private static class PendingFileInfo {
        long firstSeenMs;
        long lastSeenMs;
    }

    /**
     * 待同步文件信息。
     */
    public static class PendingFile {
        public String path;
        public long firstSeenMs;
        public long lastSeenMs;
        public boolean indexing;
    }
}
