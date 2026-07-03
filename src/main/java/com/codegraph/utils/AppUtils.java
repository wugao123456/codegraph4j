package com.codegraph.utils;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 应用工具类，统一 ServeCommand 和 InstallCommand 中的 findJarPath 重复逻辑。
 */
public final class AppUtils {

    private AppUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 查找 shaded JAR 文件路径。排除 original、sources、javadoc。
     */
    public static String findJarPath() {
        String userDir = System.getProperty("user.dir");
        Path targetDir = Paths.get(userDir, "target");
        if (Files.isDirectory(targetDir)) {
            try (DirectoryStream<Path> stream =
                         Files.newDirectoryStream(targetDir, "*.jar")) {
                for (Path jar : stream) {
                    String name = jar.getFileName().toString();
                    if (!name.contains("sources")
                            && !name.contains("javadoc")
                            && !name.contains("original")) {
                        return jar.toAbsolutePath().toString();
                    }
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        return "codegraph4j.jar";
    }
}
