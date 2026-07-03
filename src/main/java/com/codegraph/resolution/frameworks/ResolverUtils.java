package com.codegraph.resolution.frameworks;

import com.codegraph.core.types.Language;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 框架解析器共享工具方法。
 *
 * 提取三个 FrameworkResolver 实现（SpringResolver / DubboResolver / OpenFeignResolver）
 * 中重复的辅助方法，消除代码重复。
 */
public final class ResolverUtils {

    private ResolverUtils() {
        // 工具类，禁止实例化
    }

    /** Java 与 Kotlin 语言常量，供所有 JVM 框架解析器复用 */
    public static final List<Language> JAVA_KOTLIN_LANGS =
        Collections.unmodifiableList(Arrays.asList(Language.JAVA, Language.KOTLIN));

    /**
     * 根据字符位置计算行号（从 1 开始）。
     * 原在 SpringResolver / DubboResolver / OpenFeignResolver 中各有逐字相同的私有方法。
     *
     * @param content  文件内容
     * @param position 字符位置（0-based）
     * @return 行号（1-based）
     */
    public static int getLineNumber(String content, int position) {
        int line = 1;
        for (int i = 0; i < position && i < content.length(); i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }

    /**
     * 提取方法的代码体（从方法声明行开始取 30 行）。
     * 原在 DubboResolver / OpenFeignResolver 中各有逐字相同的私有方法。
     *
     * @param content         文件内容
     * @param methodStartLine 方法声明行号（1-based）
     * @return 方法体文本，若行号无效则返回 null
     */
    public static String extractMethodBody(String content, int methodStartLine) {
        String[] lines = content.split("\n");
        if (methodStartLine < 1 || methodStartLine > lines.length) return null;
        StringBuilder body = new StringBuilder();
        for (int i = methodStartLine - 1; i < Math.min(lines.length, methodStartLine + 30); i++) {
            body.append(lines[i]).append("\n");
        }
        return body.toString();
    }

    /**
     * 从完全限定名中提取简单类名。
     * 原在 SpringResolver / ImportResolver 中各有相同的私有方法，
     * 且 DubboResolver 中有内联的相同逻辑。
     *
     * @param qualifiedName 完全限定名（如 com.example.MyClass）
     * @return 简单类名（如 MyClass），若为 null 则返回 null
     */
    public static String extractSimpleName(String qualifiedName) {
        if (qualifiedName == null) return null;
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot >= 0 ? qualifiedName.substring(lastDot + 1) : qualifiedName;
    }
}
