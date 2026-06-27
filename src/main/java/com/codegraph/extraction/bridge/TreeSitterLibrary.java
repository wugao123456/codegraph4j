package com.codegraph.extraction.bridge;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * tree-sitter 动态库加载器。
 *
 * 负责按优先级查找并加载:
 *   1. libtree-sitter.dylib/.so/.dll    — 核心解析引擎
 *   2. libtree-sitter-java.dylib/.so/.dll — Java 语法库
 *
 * 搜索路径优先级:
 *   1. 环境变量 TREESITTER_LIB_PATH / TREESITTER_JAVA_LIB_PATH
 *   2. /opt/homebrew/lib (macOS ARM64 Homebrew)
 *   3. /usr/local/lib   (macOS x86_64 / Linux)
 *   4. java.library.path (JVM 默认)
 */
public class TreeSitterLibrary {

    private static final Logger logger = LoggerFactory.getLogger(TreeSitterLibrary.class);

    private static volatile TreeSitterNative tsNative;
    private static volatile Pointer javaLanguage;
    /**
     * 必须持有 grammar 接口的强引用，防止 JNA Cleaner 线程在 GC 后卸载原生库。
     * 否则 javaLanguage 会变成悬空指针，导致 SIGSEGV。
     */
    private static volatile TreeSitterGrammar grammarRef;

    private TreeSitterLibrary() {}

    // ---- 核心库 ----

    public static synchronized TreeSitterNative getTreeSitter() {
        if (tsNative == null) {
            tsNative = loadTreeSitter();
        }
        return tsNative;
    }

    private static TreeSitterNative loadTreeSitter() {
        List<String> searchPaths = buildSearchPaths(
            "TREESITTER_LIB_PATH",
            "libtree-sitter"
        );

        for (String path : searchPaths) {
            try {
                logger.debug("Trying to load tree-sitter from: {}", path);
                TreeSitterNative lib = Native.load(path, TreeSitterNative.class);
                logger.info("Loaded tree-sitter core library from: {}", path);
                return lib;
            } catch (UnsatisfiedLinkError e) {
                logger.debug("Failed to load from {}: {}", path, e.getMessage());
            }
        }

        throw new UnsatisfiedLinkError(
            "Cannot find libtree-sitter. Please install tree-sitter (brew install tree-sitter) " +
            "or set TREESITTER_LIB_PATH environment variable."
        );
    }

    // ---- Java 语法库 ----

    public static synchronized Pointer getJavaLanguage() {
        if (javaLanguage == null) {
            javaLanguage = loadJavaLanguage();
        }
        return javaLanguage;
    }

    private static Pointer loadJavaLanguage() {
        List<String> searchPaths = buildSearchPaths(
            "TREESITTER_JAVA_LIB_PATH",
            "libtree-sitter-java"
        );

        for (String path : searchPaths) {
            try {
                logger.debug("Trying to load tree-sitter-java from: {}", path);

                // 使用自定义接口加载（仅导出 tree_sitter_java 符号）
                TreeSitterGrammar grammar = Native.load(path, TreeSitterGrammar.class);
                Pointer lang = grammar.tree_sitter_java();
                if (lang != null) {
                    grammarRef = grammar; // 持有强引用，防止原生库被 GC 卸载
                    logger.info("Loaded tree-sitter-java grammar from: {}", path);
                    return lang;
                }
            } catch (UnsatisfiedLinkError e) {
                logger.debug("Failed to load from {}: {}", path, e.getMessage());
            }
        }

        throw new UnsatisfiedLinkError(
            "Cannot find libtree-sitter-java. Please compile tree-sitter-java grammar " +
            "or set TREESITTER_JAVA_LIB_PATH environment variable."
        );
    }

    // ---- 辅助方法 ----

    /**
     * tree-sitter 语法库接口（仅导出 language 符号）。
     */
    private interface TreeSitterGrammar extends com.sun.jna.Library {
        Pointer tree_sitter_java();
    }

    /**
     * 构建搜索路径列表。
     */
    private static List<String> buildSearchPaths(String envVar, String libName) {
        // 1. 环境变量指定的路径
        String envPath = System.getenv(envVar);
        if (envPath != null && !envPath.isEmpty()) {
            return Arrays.asList(envPath);
        }

        String osName = System.getProperty("os.name", "").toLowerCase();
        String libSuffix;
        if (osName.contains("mac") || osName.contains("darwin")) {
            libSuffix = ".dylib";
        } else if (osName.contains("win")) {
            libSuffix = ".dll";
        } else {
            libSuffix = ".so";
        }

        String libFileName = libName + libSuffix;

        // 2. 常见安装路径
        return Arrays.asList(
            "/opt/homebrew/lib/" + libFileName,
            "/usr/local/lib/" + libFileName,
            "/usr/lib/" + libFileName,
            libFileName  // fallback to java.library.path
        );
    }

    /**
     * 重置已加载的库（用于测试或重新加载）。
     */
    public static synchronized void reset() {
        tsNative = null;
        javaLanguage = null;
        grammarRef = null;
    }
}
