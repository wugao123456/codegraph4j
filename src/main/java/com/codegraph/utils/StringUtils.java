package com.codegraph.utils;

/**
 * 字符串工具类。
 *
 * 统一项目中散落在 BaseTool、ExploreTool、测试类中的重复字符串处理方法。
 */
public final class StringUtils {

    private StringUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 截断字符串到指定长度，超出部分用 "..." 替代。
     */
    public static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * 转义 Markdown 特殊字符（反引号、星号）。
     */
    public static String escapeMarkdown(String s) {
        return s != null ? s.replace("`", "\\`").replace("*", "\\*") : "";
    }

    /**
     * 转义 JSON 字符串中的反斜杠和双引号。
     */
    public static String escapeJson(String s) {
        return s != null ? s.replace("\\", "\\\\").replace("\"", "\\\"") : "";
    }
}
