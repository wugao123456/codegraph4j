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
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Git 钩子管理器 — 在文件监听不可用时安装 Git 钩子作为备选方案。
 * 参照 codegraph git-hooks.ts。
 *
 * 默认钩子: post-commit, post-merge, post-checkout
 */
public class GitHooksManager {

    private static final Logger logger = LoggerFactory.getLogger(GitHooksManager.class);

    private static final String MARKER_BEGIN = "# >>> codegraph sync hook >>>";
    private static final String MARKER_END = "# <<< codegraph sync hook <<<";

    public enum GitHookName {
        POST_COMMIT("post-commit"),
        POST_MERGE("post-merge"),
        POST_CHECKOUT("post-checkout");

        private final String fileName;

        GitHookName(String fileName) {
            this.fileName = fileName;
        }

        public String getFileName() {
            return fileName;
        }
    }

    public static final List<GitHookName> DEFAULT_SYNC_HOOKS = Arrays.asList(
            GitHookName.POST_COMMIT,
            GitHookName.POST_MERGE,
            GitHookName.POST_CHECKOUT
    );

    /**
     * Git 钩子操作结果。
     */
    public static class GitHookResult {
        public List<GitHookName> installed;
        public Path hooksDir;
        public String skipped;

        public GitHookResult() {
            this.installed = new ArrayList<>();
        }

        public boolean isSuccess() {
            return skipped == null;
        }
    }

    /**
     * 检查是否为 Git 仓库。
     */
    public static boolean isGitRepo(Path projectRoot) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "rev-parse", "--is-inside-work-tree");
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = readAllBytes(process.getInputStream()).trim();
            int exitCode = process.waitFor();
            return exitCode == 0 && "true".equals(output);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取 git hooks 目录（考虑 core.hooksPath 和 worktree）。
     */
    public static Path gitHooksDir(Path projectRoot) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "rev-parse", "--git-path", "hooks");
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = readAllBytes(process.getInputStream()).trim();
            int exitCode = process.waitFor();
            if (exitCode != 0 || output.isEmpty()) return null;

            Path hooksPath = Paths.get(output);
            if (!hooksPath.isAbsolute()) {
                hooksPath = projectRoot.resolve(output).normalize();
            }
            return hooksPath;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 安装同步钩子（幂等：重复安装不会重复标记块）。
     */
    public static GitHookResult installGitSyncHook(Path projectRoot, List<GitHookName> hooks) {
        GitHookResult result = new GitHookResult();
        Path hooksDir = gitHooksDir(projectRoot);

        if (hooksDir == null) {
            result.skipped = "not a git repository";
            return result;
        }

        try {
            Files.createDirectories(hooksDir);
        } catch (IOException e) {
            result.skipped = "could not access the git hooks directory";
            logger.warn("无法创建 hooks 目录: {} — {}", hooksDir, e.getMessage());
            return result;
        }

        result.hooksDir = hooksDir;
        String block = markerBlock();

        for (GitHookName hook : hooks) {
            Path file = hooksDir.resolve(hook.getFileName());
            String content;

            if (Files.exists(file)) {
                try {
                    String existing = readFileToString(file);
                    String base = stripMarkerBlock(existing).replaceAll("\\s*$", "");
                    if (!base.isEmpty()) {
                        content = base + "\n\n" + block + "\n";
                    } else {
                        content = "#!/bin/sh\n" + block + "\n";
                    }
                } catch (IOException e) {
                    logger.warn("读取 hook 文件失败: {} — {}", file, e.getMessage());
                    continue;
                }
            } else {
                content = "#!/bin/sh\n" + block + "\n";
            }

            try {
                Files.write(file, content.getBytes(StandardCharsets.UTF_8));
                chmodExecutable(file);
                result.installed.add(hook);
            } catch (IOException e) {
                logger.warn("写入 hook 文件失败: {} — {}", file, e.getMessage());
            }
        }

        return result;
    }

    /**
     * 移除同步钩子。仅去除标记块，保留用户自定义内容。
     */
    public static GitHookResult removeGitSyncHook(Path projectRoot, List<GitHookName> hooks) {
        GitHookResult result = new GitHookResult();
        Path hooksDir = gitHooksDir(projectRoot);

        if (hooksDir == null) {
            result.skipped = "not a git repository";
            return result;
        }
        result.hooksDir = hooksDir;

        for (GitHookName hook : hooks) {
            Path file = hooksDir.resolve(hook.getFileName());
            if (!Files.exists(file)) continue;

            try {
                String original = readFileToString(file);
                if (!original.contains(MARKER_BEGIN)) continue;

                String stripped = stripMarkerBlock(original);
                if (isEffectivelyEmpty(stripped)) {
                    Files.delete(file);
                } else {
                    Files.write(file, (stripped.replaceAll("\\s*$", "") + "\n").getBytes(StandardCharsets.UTF_8));
                    chmodExecutable(file);
                }
                result.installed.add(hook);
            } catch (IOException e) {
                logger.warn("移除 hook 失败: {} — {}", file, e.getMessage());
            }
        }

        return result;
    }

    /**
     * 检查是否已安装同步钩子。
     */
    public static boolean isSyncHookInstalled(Path projectRoot, List<GitHookName> hooks) {
        Path hooksDir = gitHooksDir(projectRoot);
        if (hooksDir == null) return false;

        return hooks.stream().anyMatch(hook -> {
            Path file = hooksDir.resolve(hook.getFileName());
            try {
                return Files.exists(file) && readFileToString(file).contains(MARKER_BEGIN);
            } catch (IOException e) {
                return false;
            }
        });
    }

    // ===== 内部方法 =====

    /**
     * 生成标记块内容（Shell 脚本片段）。
     */
    private static String markerBlock() {
        return MARKER_BEGIN + "\n" +
                "# Keeps the CodeGraph index fresh while the live file watcher is off\n" +
                "# (e.g. WSL2 /mnt drives). Runs in the background so it never blocks git.\n" +
                "# Managed by codegraph; remove with `codegraph uninit` or delete this block.\n" +
                "if command -v codegraph4j >/dev/null 2>&1; then\n" +
                "  ( codegraph4j sync -q >/dev/null 2>&1 & ) >/dev/null 2>&1\n" +
                "fi\n" +
                MARKER_END;
    }

    /**
     * 去除标记块（含标记行本身）。
     */
    private static String stripMarkerBlock(String content) {
        StringBuilder sb = new StringBuilder();
        boolean inBlock = false;
        for (String line : content.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.equals(MARKER_BEGIN)) {
                inBlock = true;
                continue;
            }
            if (trimmed.equals(MARKER_END)) {
                inBlock = false;
                continue;
            }
            if (!inBlock) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 判断内容是否仅含 shebang 或空行。
     */
    private static boolean isEffectivelyEmpty(String content) {
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#!")) {
                return false;
            }
        }
        return true;
    }

    /**
     * 设置文件为可执行。
     */
    private static void chmodExecutable(Path file) {
        try {
            Set<PosixFilePermission> perms = new HashSet<>();
            perms.add(PosixFilePermission.OWNER_READ);
            perms.add(PosixFilePermission.OWNER_WRITE);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_READ);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_READ);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (Exception e) {
            // chmod 在某些平台不可用（如 Windows），忽略
        }
    }

    /**
     * 读取文件内容为字符串。
     */
    private static String readFileToString(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /**
     * 读取 InputStream 的全部字节并转为字符串。
     */
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
