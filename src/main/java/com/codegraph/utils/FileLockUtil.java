package com.codegraph.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class FileLockUtil {
    private static final Logger logger = LoggerFactory.getLogger(FileLockUtil.class);
    
    private FileChannel channel;
    private FileLock lock;
    private Path filePath;
    private boolean shared;

    public static FileLockUtil tryLock(String filePath, boolean shared) {
        return tryLock(new File(filePath).toPath(), shared);
    }

    public static FileLockUtil tryLock(Path filePath, boolean shared) {
        try {
            FileLockResult result = tryAcquireLock(filePath, shared);
            if (result.success) {
                FileLockUtil fileLock = new FileLockUtil();
                fileLock.channel = result.channel;
                fileLock.lock = result.lock;
                fileLock.filePath = filePath;
                fileLock.shared = shared;
                return fileLock;
            }
        } catch (IOException e) {
            logger.debug("Failed to acquire lock on {}: {}", filePath, e.getMessage());
        }
        return null;
    }

    private static FileLockResult tryAcquireLock(Path filePath, boolean shared) throws IOException {
        FileLockResult result = new FileLockResult();
        
        StandardOpenOption[] options = shared 
            ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE}
            : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE};
        
        FileChannel channel = FileChannel.open(filePath, options);
        result.channel = channel;
        
        try {
            FileLock lock = channel.tryLock();
            if (lock != null) {
                result.lock = lock;
                result.success = true;
            }
        } catch (java.nio.channels.OverlappingFileLockException e) {
            channel.close();
        }
        
        return result;
    }

    private FileLockUtil() {
    }

    public void release() {
        if (lock != null) {
            try {
                lock.release();
            } catch (IOException e) {
                logger.warn("Failed to release lock: {}", e.getMessage());
            }
            lock = null;
        }
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                logger.warn("Failed to close channel: {}", e.getMessage());
            }
            channel = null;
        }
    }

    public boolean isValid() {
        return lock != null && lock.isValid() && channel != null;
    }

    public Path getFilePath() {
        return filePath;
    }

    public boolean isShared() {
        return shared;
    }

    private static class FileLockResult {
        boolean success;
        FileChannel channel;
        FileLock lock;
    }
}