package com.codegraph.mcp;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 项目类型检测工具 — 判断当前项目是否为 Java 项目。
 */
public class ProjectDetector {

    /**
     * 检测指定路径是否为 Java 项目。
     * 判定依据：
     * <ul>
     *   <li>根目录存在 pom.xml / build.gradle / build.gradle.kts 构建文件</li>
     *   <li>根目录或子目录存在 .java 源文件（最多扫描 2 层）</li>
     * </ul>
     */
    public static boolean isJavaProject(String projectPath) {
        if (projectPath == null) return false;
        File root = new File(projectPath);
        if (!root.isDirectory()) return false;

        // 1. 构建文件检测（Maven / Gradle）
        if (new File(root, "pom.xml").exists()) return true;
        if (new File(root, "build.gradle").exists()) return true;
        if (new File(root, "build.gradle.kts").exists()) return true;

        // 2. 扫描 .java 源文件（最多 2 层深度）
        return hasJavaFiles(root.toPath(), 2);
    }

    private static boolean hasJavaFiles(Path dir, int maxDepth) {
        if (maxDepth < 0) return false;
        try {
            // 先检查当前目录
            File[] files = dir.toFile().listFiles();
            if (files == null) return false;
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".java")) {
                    return true;
                }
            }
            // 递归子目录
            if (maxDepth > 0) {
                for (File f : files) {
                    if (f.isDirectory() && !f.getName().startsWith(".")) {
                        if (hasJavaFiles(f.toPath(), maxDepth - 1)) return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }
}
