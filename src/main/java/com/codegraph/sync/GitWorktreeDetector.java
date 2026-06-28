package com.codegraph.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Git Worktree 检测器 — 检测跨 worktree 的索引借用问题。
 * 参照 codegraph worktree.ts。
 */
public class GitWorktreeDetector {

    private static final Logger logger = LoggerFactory.getLogger(GitWorktreeDetector.class);

    /**
     * Worktree 索引不匹配信息。
     */
    public static class WorktreeIndexMismatch {
        /** 命令运行所在的 git working tree */
        public String worktreeRoot;
        /** 正在使用的 .codegraph 索引所属的 working tree */
        public String indexRoot;
    }

    /**
     * 获取目录对应的 git worktree 根路径。
     */
    public static Path gitWorktreeRoot(Path dir) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "rev-parse", "--show-toplevel");
            pb.directory(dir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = readAllBytes(process.getInputStream()).trim();
            int exitCode = process.waitFor();
            if (exitCode == 0 && !output.isEmpty()) {
                return realpath(Paths.get(output));
            }
        } catch (Exception e) {
            // git 不可用或路径不是 git 仓库
        }
        return null;
    }

    /**
     * 检测 worktree 索引借用。
     */
    public static WorktreeIndexMismatch detectWorktreeIndexMismatch(Path startPath, Path indexRoot) {
        Path worktreeRoot = gitWorktreeRoot(startPath);
        if (worktreeRoot == null) return null;

        Path resolvedIndexRoot = realpath(indexRoot);
        if (worktreeRoot.equals(resolvedIndexRoot)) return null;

        Path indexWorktreeRoot = gitWorktreeRoot(resolvedIndexRoot);
        if (!resolvedIndexRoot.equals(indexWorktreeRoot)) return null;

        WorktreeIndexMismatch m = new WorktreeIndexMismatch();
        m.worktreeRoot = worktreeRoot.toString();
        m.indexRoot = resolvedIndexRoot.toString();
        return m;
    }

    /**
     * 多行警告信息。
     */
    public static String worktreeMismatchWarning(WorktreeIndexMismatch m) {
        return "This CodeGraph index belongs to a different git working tree.\n" +
                "  Running in: " + m.worktreeRoot + "\n" +
                "  Index from: " + m.indexRoot + "\n" +
                "Results reflect that tree's code (often a different branch), not this worktree — " +
                "symbols changed only here are missing. Run \"codegraph4j init\" in this worktree " +
                "for a worktree-local index.";
    }

    /**
     * 紧凑的单行提示。
     */
    public static String worktreeMismatchNotice(WorktreeIndexMismatch m) {
        return "⚠ CodeGraph results below come from a different git worktree (" + m.indexRoot + "), " +
                "not where you're working (" + m.worktreeRoot + ") — they may reflect another branch, " +
                "and symbols changed only here are missing. Run \"codegraph4j init\" here for a " +
                "worktree-local index.";
    }

    /**
     * 解析符号链接以获得真实路径。
     */
    private static Path realpath(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    private static String readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[4096];
        int nRead;
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
