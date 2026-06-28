package com.codegraph.sync;

import java.nio.file.Path;

/**
 * 监听策略 — 决定文件监听器是否应该运行。
 * 参照 codegraph watch-policy.ts。
 *
 * 优先级：
 * 1. CODEGRAPH_NO_WATCH=1 → 禁用（最高优先级）
 * 2. CODEGRAPH_FORCE_WATCH=1 → 强制启用
 * 3. WSL + /mnt/[a-z] 驱动器 → 禁用
 */
public class WatchPolicy {

    /**
     * 返回禁用原因，null 表示可以启用。
     */
    public static String watchDisabledReason(Path projectRoot) {
        // 1. CODEGRAPH_NO_WATCH 显式禁用
        if ("1".equals(System.getenv("CODEGRAPH_NO_WATCH"))) {
            return "CODEGRAPH_NO_WATCH=1 is set";
        }

        // 2. CODEGRAPH_FORCE_WATCH 强制启用
        if ("1".equals(System.getenv("CODEGRAPH_FORCE_WATCH"))) {
            return null;
        }

        // 3. WSL + /mnt/* 驱动器
        if (isWsl() && isWindowsDriveMount(projectRoot)) {
            return "project is on a WSL /mnt/ drive, where file watching is too slow to be reliable";
        }

        return null;
    }

    /**
     * 检测是否为 WSL 环境。
     */
    public static boolean isWsl() {
        // 通过环境变量检测 WSL
        return System.getenv("WSL_DISTRO_NAME") != null ||
                System.getenv("WSL_INTEROP") != null;
    }

    /**
     * 检测是否为 Windows 驱动器挂载（/mnt/[a-z]）。
     */
    public static boolean isWindowsDriveMount(Path projectRoot) {
        String path = projectRoot.toString().replace('\\', '/');
        return path.matches("(?i)^/mnt/[a-z](/|$).*");
    }
}
