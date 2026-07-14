package com.codegraph.context;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 搜索工具方法 — 对标 codegraph search/query-utils.ts。
 * <p>提供 FTS 搜索词提取、词干变体生成、路径相关性评分、
 * 测试文件检测等工具方法，供 ContextBuilder 的混合搜索管道使用。
 */
public class SearchUtils {

    // ========== 常用词黑名单 ==========

    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
        // 英文常用词
        "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
        "of", "with", "by", "from", "is", "it", "that", "this", "are", "was",
        "be", "has", "had", "have", "do", "does", "did", "will", "would", "could",
        "should", "may", "might", "can", "shall", "not", "no", "all", "each",
        "every", "how", "what", "where", "when", "who", "which", "why",
        "i", "me", "my", "we", "our", "you", "your", "he", "she", "they",
        "show", "give", "tell",
        "been", "done", "made", "used", "using", "work", "works", "found",
        "also", "into", "then", "than", "just", "more", "some", "such",
        "over", "only", "out", "its", "so", "up", "as", "if",
        "look", "need", "needs", "want", "happen", "happens",
        "affect", "affected", "break", "breaks", "failing",
        "implemented", "implement",
        // 代码特定噪音词
        "code", "file", "files", "function", "method", "class", "type",
        "fix", "bug", "called"
    ));

    // ========== 词干变体 ==========

    /**
     * 生成搜索词的词干变体，对标 TS getStemVariants()。
     * <p>通过去除常见英语后缀来扩展搜索范围：
     * "caching" → ["cach", "cache"]，匹配 CacheBuilder；
     * "eviction" → ["evict"]，匹配 evictEntries。
     *
     * @param term 待处理的搜索词（小写）
     * @return 词干变体列表（不包含原词）
     */
    public static List<String> getStemVariants(String term) {
        Set<String> variants = new LinkedHashSet<>();
        String t = term.toLowerCase();

        // -ing: caching→cach/cache, handling→handl/handle, running→run
        if (t.endsWith("ing") && t.length() > 5) {
            String base = t.substring(0, t.length() - 3);
            variants.add(base);
            variants.add(base + "e");
            if (base.length() >= 2 && base.charAt(base.length() - 1) == base.charAt(base.length() - 2)) {
                variants.add(base.substring(0, base.length() - 1));
            }
        }

        // -tion/-sion: eviction→evict, expression→express
        if ((t.endsWith("tion") || t.endsWith("sion")) && t.length() > 5) {
            variants.add(t.substring(0, t.length() - 3));
        }

        // -ment: management→manage
        if (t.endsWith("ment") && t.length() > 6) {
            variants.add(t.substring(0, t.length() - 4));
        }

        // -ies: entries→entry
        if (t.endsWith("ies") && t.length() > 4) {
            variants.add(t.substring(0, t.length() - 3) + "y");
        }
        // -es: processes→process, classes→class
        else if (t.endsWith("es") && t.length() > 4) {
            variants.add(t.substring(0, t.length() - 2));
        }
        // -s: errors→error (skip -ss endings like "class")
        else if (t.endsWith("s") && !t.endsWith("ss") && t.length() > 4) {
            variants.add(t.substring(0, t.length() - 1));
        }

        // -ed: handled→handle/handl, propagated→propagate, carried→carry
        if (t.endsWith("ed") && !t.endsWith("eed") && t.length() > 4) {
            variants.add(t.substring(0, t.length() - 1));
            variants.add(t.substring(0, t.length() - 2));
            if (t.endsWith("ied") && t.length() > 5) {
                variants.add(t.substring(0, t.length() - 3) + "y");
            }
        }

        // -er: builder→build/builde, handler→handl/handle, getter→get
        if (t.endsWith("er") && t.length() > 4) {
            String base = t.substring(0, t.length() - 2);
            variants.add(base);
            variants.add(base + "e");
            if (base.length() >= 2 && base.charAt(base.length() - 1) == base.charAt(base.length() - 2)) {
                variants.add(base.substring(0, base.length() - 1));
            }
        }

        List<String> result = new ArrayList<>();
        for (String v : variants) {
            if (v.length() >= 3 && !v.equals(t)) {
                result.add(v);
            }
        }
        return result;
    }

    // ========== 搜索词提取 ==========

    /** CamelCase / PascalCase 复合词模式 */
    private static final Pattern COMPOUND_PATTERN =
        Pattern.compile("\\b([a-zA-Z][a-zA-Z0-9]*(?:[A-Z][a-z]+)+|[A-Z][a-z]+(?:[A-Z][a-z]*)+)\\b");

    /** snake_case / SCREAMING_SNAKE 模式 */
    private static final Pattern SNAKE_PATTERN =
        Pattern.compile("\\b([a-zA-Z][a-zA-Z0-9]*(?:_[a-zA-Z0-9]+)+)\\b");

    /**
     * 从自然语言查询中提取有意义的搜索词，对标 TS extractSearchTerms()。
     * <p>拆分 CamelCase、snake_case、dot.notation，过滤停用词，
     * 生成词干变体以扩展 FTS 匹配范围。
     *
     * @param query 自然语言查询
     * @return 搜索词列表（已去重、已小写化）
     */
    public static List<String> extractSearchTerms(String query) {
        return extractSearchTerms(query, true);
    }

    /**
     * 从自然语言查询中提取搜索词。
     *
     * @param query        自然语言查询
     * @param includeStems 是否生成词干变体
     * @return 搜索词列表
     */
    public static List<String> extractSearchTerms(String query, boolean includeStems) {
        Set<String> tokens = new LinkedHashSet<>();

        // 1. 提取复合标识符（CamelCase/PascalCase）
        // COMPOUND_PATTERN 匹配两种模式：
        //   - 模式1: 小写开头 + 多个大写字母分隔的单词（CamelCase）
        //     如 "userService", "getUserId", "parseJsonString"
        //   - 模式2: 大写开头 + 多个大写字母分隔的单词（PascalCase）
        //     如 "UserService", "UserId", "JsonParser"
        //
        // 示例：
        //   查询: "UserService login"
        //     → 匹配 "UserService" → tokens.add("userservice")
        //
        //   查询: "getUserId method"
        //     → 匹配 "getUserId" → tokens.add("getuserid")
        //
        //   查询: "parseJsonString"
        //     → 匹配 "parseJsonString" → tokens.add("parsejsonstring")
        //
        // 长度限制 ≥3：过滤掉 "Id", "URL" 等过短的复合词
        java.util.regex.Matcher m = COMPOUND_PATTERN.matcher(query);
        while (m.find()) {
            String word = m.group(1);
            if (word != null && word.length() >= 3) {
                tokens.add(word.toLowerCase());
            }
        }

        // 2. 提取 snake_case / SCREAMING_SNAKE_CASE
        // SNAKE_PATTERN 匹配以下划线分隔的标识符：
        //   - snake_case: 小写字母 + 下划线 + 小写字母，如 "user_service", "get_user_id"
        //   - SCREAMING_SNAKE_CASE: 大写字母 + 下划线 + 大写字母，如 "MAX_SIZE", "DEFAULT_CONFIG"
        //   - 混合大小写: 如 "User_Service", "parse_JSON"
        //
        // 示例：
        //   查询: "user_service login"
        //     → 匹配 "user_service" → tokens.add("user_service")
        //
        //   查询: "MAX_SIZE constant"
        //     → 匹配 "MAX_SIZE" → tokens.add("max_size")
        //
        //   查询: "get_user_id method"
        //     → 匹配 "get_user_id" → tokens.add("get_user_id")
        //
        //   查询: "parse_JSON_string"
        //     → 匹配 "parse_JSON_string" → tokens.add("parse_json_string")
        //
        // 长度限制 ≥3：过滤掉 "id", "url" 等过短的 snake_case 词
        m = SNAKE_PATTERN.matcher(query);
        while (m.find()) {
            String word = m.group(1);
            if (word != null && word.length() >= 3) {
                tokens.add(word.toLowerCase());
            }
        }

        // 3. 拆分 CamelCase → 单词序列
        // 两个正则表达式配合使用：
        //   ① ([a-z])([A-Z]) → 在小写字母和大写字母之间插入空格
        //   ② ([A-Z]+)([A-Z][a-z]) → 在连续大写字母序列后、大写+小写之前插入空格
        //
        // 示例：
        //   查询: "parseJSONString"
        //     ① 处理: "parseJSONString" → "parse JSONString"
        //     ② 处理: "parse JSONString" → "parse JSON String"
        //
        //   查询: "getHTTPRequest"
        //     ① 处理: "getHTTPRequest" → "get HTTPRequest"
        //     ② 处理: "get HTTPRequest" → "get HTTP Request"
        //
        //   查询: "UserID"
        //     ① 处理: "UserID" → "UserID"（无变化，没有小写后跟大写）
        //     ② 处理: "UserID" → "User ID"（连续大写 "ID" 后接... 不，这里 ID 是结尾）
        //     → 实际结果: "UserID"（需要后续拆分）
        String camelSplit = query
            .replaceAll("([a-z])([A-Z])", "$1 $2")
            .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");

        // 4. 替换下划线和点为空格
        // [_.]+ 匹配一个或多个下划线(_)或点(.)，替换为空格
        // 这样 snake_case 和 dot.notation 都会被拆分为独立单词
        //
        // 示例：
        //   "user_service" → "user service"
        //   "com.example.UserService" → "com example UserService"
        //   "parse_JSON_data" → "parse JSON data"
        //   "file.path/to/file" → "file path to file"
        String normalized = camelSplit.replaceAll("[_.]+", " ");

        String[] words = normalized.split("[^a-zA-Z0-9]+");
        for (String word : words) {
            if (word.isEmpty()) continue;
            String lower = word.toLowerCase();
            if (lower.length() < 3) continue;
            if (STOP_WORDS.contains(lower)) continue;
            tokens.add(lower);
        }

        // 6. 生成词干变体
        if (includeStems) {
            Set<String> stems = new LinkedHashSet<>();
            for (String token : tokens) {
                for (String variant : getStemVariants(token)) {
                    if (!tokens.contains(variant) && !STOP_WORDS.contains(variant)) {
                        stems.add(variant);
                    }
                }
            }
            tokens.addAll(stems);
        }

        return new ArrayList<>(tokens);
    }

    // ========== 路径相关性评分 ==========

    /**
     * 计算文件路径与查询的相关性得分，对标 TS scorePathRelevance()。
     * <p>高得分的路径更可能包含用户关心的代码。
     *
     * @param filePath 文件路径
     * @param query    用户查询
     * @return 相关性得分（可为负值）
     */
    public static int scorePathRelevance(String filePath, String query) {
        // 空值保护：任一参数为空则无相关性
        if (filePath == null || query == null) return 0;

        // 路径预处理：统一转小写，便于不区分大小写的匹配
        String pathLower = filePath.toLowerCase();
        // 提取文件名（路径中最后一个 '/' 之后的部分），例如 "/src/main/java/MyClass.java" → "myclass.java"
        String fileName = filePath.substring(filePath.lastIndexOf('/') + 1).toLowerCase();
        // 提取目录名（路径中最后一个 '/' 之前的部分），例如 "/src/main/java/MyClass.java" → "/src/main/java"
        String dirName;
        int lastSep = filePath.lastIndexOf('/');
        if (lastSep >= 0) {
            dirName = filePath.substring(0, lastSep).toLowerCase();
        } else {
            dirName = "";
        }
        int score = 0;
        // 将查询按空格分割为多个词，逐个词评分
        String[] queryWords = query.split("\\s+");
        for (String word : queryWords) {
            if (word.isEmpty()) continue;
            // 提取该词的子 token（第二个参数 false 表示不含词干变体）
            // 例如 "authentication" 提取出 ["authentication"]，而非 ["authentication", "auth", "authentic"]
            // 这样可以避免因词干扩展导致的分数膨胀
            List<String> subTokens = extractSearchTerms(word, false);
            if (subTokens.isEmpty()) continue;
            boolean matched = false;
            // 三级匹配策略（按优先级从高到低），每个查询词只匹配最高级别的匹配，不重复加分：
            // 1. 文件名匹配（最高优先级，+10分）：查询词出现在文件名中，最相关
            // 2. 目录名匹配（中等优先级，+5分）：查询词出现在目录路径中，次相关
            // 3. 完整路径匹配（最低优先级，+3分）：查询词出现在任意路径位置
            // 精确文件名匹配（最高分）
            for (String t : subTokens) {
                if (fileName.contains(t)) { score += 10; matched = true; break; }
            }
            // 已匹配文件名，跳过后续检查（每个词只取最高匹配级别）
            if (matched) continue;
            // 目录匹配（次高优先级）
            for (String t : subTokens) {
                if (dirName.contains(t)) { score += 5; matched = true; break; }
            }
            // 已匹配目录，跳过后续检查
            if (matched) continue;
            // 一般路径匹配（最低优先级）
            for (String t : subTokens) {
                if (pathLower.contains(t)) { score += 3; break; }
            }
        }
        // 测试文件降权：如果是测试文件但查询词不涉及测试，减去15分
        // 例如：查询 "UserService" 时，"UserServiceTest.java" 会被降权，避免测试文件排在前面
        if (isTestFile(filePath) && !mentionsTests(query)) {
            score -= 15;
        }
        return score;
    }

    // ========== 测试文件检测 ==========

    /**
     * 检测文件路径是否为测试文件，对标 TS isTestFile()。
     */
    public static boolean isTestFile(String filePath) {
        if (filePath == null) return false;
        String lower = filePath.toLowerCase();
        String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);
        String lowerName = fileName.toLowerCase();

        // 文件名模式
        if (lowerName.startsWith("test_") || lowerName.startsWith("test.")) return true;
        if (lowerName.matches(".*[._-](test|tests|spec|specs)\\.[a-z0-9]+$")) return true;
        if (fileName.matches(".*(?:Test|Tests|TestCase|Tester|Spec|Specs)\\.[A-Za-z0-9]+$")) return true;

        // 目录模式
        if (lower.contains("/tests/") || lower.contains("/test/")
            || lower.contains("/__tests__/") || lower.contains("/spec/")
            || lower.contains("/specs/") || lower.contains("/testlib/")
            || lower.contains("/testing/")
            || lower.startsWith("test/") || lower.startsWith("tests/")
            || lower.startsWith("spec/") || lower.startsWith("specs/")) {
            return true;
        }
        if (filePath.matches("(?:^|/)[A-Za-z0-9]*(?:Test|Tests|Spec)/.*")) return true;

        // 非生产目录
        return matchesNonProductionDir(lower);
    }

    private static boolean matchesNonProductionDir(String lowerPath) {
        String[] dirs = {"integration", "sample", "samples", "example", "examples",
            "fixture", "fixtures", "benchmark", "benchmarks", "demo", "demos"};
        for (String dir : dirs) {
            if (lowerPath.contains("/" + dir + "/") || lowerPath.startsWith(dir + "/")) {
                return true;
            }
        }
        return false;
    }

    private static boolean mentionsTests(String query) {
        if (query == null) return false;
        String lc = query.toLowerCase();
        return lc.contains("test") || lc.contains("spec");
    }

    // ========== 区分性标识符检测 ==========

    /**
     * 检测一个 token 是否看起来像代码标识符（而非普通英文单词），对标 TS isDistinctiveIdentifier()。
     * <p>用于判断精确匹配是否应获得"用户明确命名此符号"的豁免。
     */
    public static boolean isDistinctiveIdentifier(String token) {
        if (token == null || token.isEmpty()) return false;
        // snake_case / SCREAMING_SNAKE / 含数字 → 故意的标识符
        if (token.matches(".*[_0-9].*")) return true;
        // 首字母之后出现大写 → camelCase/PascalCase 边界或缩写
        if (token.length() > 1 && token.substring(1).matches(".*[A-Z].*")) return true;
        return false;
    }
}
