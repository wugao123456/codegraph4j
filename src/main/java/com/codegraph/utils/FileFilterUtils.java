package com.codegraph.utils;

/**
 * 文件过滤工具类。
 *
 * 统一项目中散落在 SyncOrchestrator、FileWatcher、ExploreTool 中的
 * 重复文件类型判断和过滤逻辑。
 */
public final class FileFilterUtils {

    /** 支持的代码文件扩展名 */
    private static final String[] SOURCE_EXTENSIONS = {
        ".java", ".js", ".jsx", ".ts", ".tsx", ".mjs"
    };

    private FileFilterUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 判断是否为支持解析的源代码文件。
     */
    public static boolean isSourceFile(String filePath) {
        if (filePath == null) return false;
        String lower = filePath.toLowerCase();
        for (String ext : SOURCE_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /**
     * 判断是否为自动生成的代码文件。
     */
    public static boolean isGeneratedFile(String path) {
        if (path == null) return false;
        String lp = path.toLowerCase();
        return lp.contains(".pb.") || lp.endsWith(".pulsar.go")
            || lp.endsWith("_mocks.go") || lp.endsWith("_mock.go")
            || lp.contains("/generated/") || lp.contains("/generated_src/")
            || lp.endsWith("_generated.java") || lp.endsWith("_generated.kt")
            || (lp.contains("generated_") && (lp.endsWith(".java") || lp.endsWith(".kt")));
    }
}
