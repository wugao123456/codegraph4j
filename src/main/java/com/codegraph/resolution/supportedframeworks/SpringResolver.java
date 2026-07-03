package com.codegraph.resolution.supportedframeworks;

import com.codegraph.core.Node;
import com.codegraph.core.types.Language;
import com.codegraph.core.types.NodeKind;
import com.codegraph.core.types.Visibility;
import com.codegraph.resolution.ResolutionContext;
import com.codegraph.resolution.frameworks.FrameworkExtractionResult;
import com.codegraph.resolution.frameworks.FrameworkResolver;
import com.codegraph.resolution.frameworks.ResolverUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring 框架解析器。
 *
 * 功能：
 * 1. 检测 Spring Boot / Spring Framework 项目
 * 2. 从 @RequestMapping / @GetMapping 等注解中提取路由节点
 * 3. 从 @Value("${...}") 中提取配置键绑定
 * 4. 按 Spring 命名约定解析 Service/Controller/Repository 引用
 */
public class SpringResolver implements FrameworkResolver {

    private static final Logger logger = LoggerFactory.getLogger(SpringResolver.class);

    // 路由注解正则：匹配 @GetMapping("/path"), @PostMapping(value = "/path") 等
    private static final Pattern REQUEST_MAPPING_PATTERN = Pattern.compile(
        "@(Get|Post|Put|Delete|Patch|Request)Mapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\""
    );

    // @Value 注入正则：匹配 @Value("${key}") 或 @Value("${key:default}")
    private static final Pattern VALUE_ANNOTATION_PATTERN = Pattern.compile(
        "@Value\\s*\\(\\s*\"\\$\\{([^}:]+)(?::[^}]*)?\\}\"\\s*\\)"
    );

    // @ConfigurationProperties 正则
    private static final Pattern CONFIG_PROPERTIES_PATTERN = Pattern.compile(
        "@ConfigurationProperties\\s*\\(\\s*(?:prefix\\s*=\\s*)?\"([^\"]+)\""
    );

    // Spring 注解扫描（用于 detect）
    private static final Pattern SPRING_ANNOTATION_PATTERN = Pattern.compile(
        "@(SpringBootApplication|RestController|Controller|Service|Repository|Component|Bean|Autowired|Configuration)"
    );

    @Override
    public String getName() {
        return "spring";
    }

    @Override
    public List<Language> getLanguages() {
        return ResolverUtils.JAVA_KOTLIN_LANGS;
    }

    @Override
    public boolean detect(ResolutionContext context) {
        // 策略 1：检查项目构建文件（pom.xml / build.gradle）中的 Spring 依赖
        String pomContent = context.readFile("pom.xml");
        if (pomContent != null && (pomContent.contains("spring-boot") ||
            pomContent.contains("springframework"))) {
            logger.info("[SpringResolver] 检测到 Spring 框架 (pom.xml)");
            return true;
        }

        // 策略 2：扫描所有 Java 文件内容，查找 Spring 注解
        List<String> files = context.getAllFiles();
        for (String filePath : files) {
            if (filePath.endsWith(".java")) {
                String content = context.readFile(filePath);
                if (content != null && SPRING_ANNOTATION_PATTERN.matcher(content).find()) {
                    logger.info("[SpringResolver] 检测到 Spring 框架 (注解)");
                    return true;
                }
            }
            // 只扫描前 100 个文件，避免过大项目耗时过长
            if (files.indexOf(filePath) > 100) break;
        }
        return false;
    }

    @Override
    public FrameworkExtractionResult extract(String filePath, String content, ResolutionContext context) {
        FrameworkExtractionResult result = new FrameworkExtractionResult();

        if (!filePath.endsWith(".java")) {
            return result;
        }

        // 提取路由
        extractRoutes(filePath, content, result);

        // 提取 @Value 配置键绑定
        extractValueBindings(filePath, content, result);

        return result;
    }

    /**
     * 提取 @RequestMapping 路由节点。
     */
    private void extractRoutes(String filePath, String content, FrameworkExtractionResult result) {
        Matcher matcher = REQUEST_MAPPING_PATTERN.matcher(content);
        while (matcher.find()) {
            String httpMethod = matcher.group(1);
            String path = matcher.group(2);

            int pos = matcher.start();
            int line = ResolverUtils.getLineNumber(content, pos);

            Node routeNode = new Node();
            routeNode.setKind(NodeKind.ROUTE);
            routeNode.setName(httpMethod + " " + path);
            routeNode.setQualifiedName(filePath + ":" + httpMethod + " " + path);
            routeNode.setFilePath(filePath);
            routeNode.setLanguage(Language.JAVA);
            routeNode.setStartLine(line);
            routeNode.setEndLine(line);
            routeNode.setVisibility(Visibility.PUBLIC);

            result.addNode(routeNode);
            logger.debug("[SpringResolver] 提取路由: {} {} (line {})", httpMethod, path, line);
        }
    }

    /**
     * 提取 @Value("${key}") 配置绑定。
     */
    private void extractValueBindings(String filePath, String content, FrameworkExtractionResult result) {
        Matcher matcher = VALUE_ANNOTATION_PATTERN.matcher(content);
        while (matcher.find()) {
            String configKey = matcher.group(1);
            int pos = matcher.start();
            int line = ResolverUtils.getLineNumber(content, pos);

            Node configNode = new Node();
            configNode.setKind(NodeKind.CONSTANT);
            configNode.setName("${" + configKey + "}");
            configNode.setQualifiedName("config:" + configKey);
            configNode.setFilePath(filePath);
            configNode.setLanguage(Language.JAVA);
            configNode.setStartLine(line);
            configNode.setEndLine(line);
            configNode.setVisibility(Visibility.PUBLIC);

            result.addNode(configNode);
            logger.debug("[SpringResolver] 提取配置键: {} (line {})", configKey, line);
        }
    }

    @Override
    public String resolve(String refName, String refKind, String sourceFile, ResolutionContext context) {
        // 按 Spring 命名约定解析

        // 1. XxxService → 查找 *Service.java 文件中的节点
        if (refName.endsWith("Service")) {
            String simpleName = ResolverUtils.extractSimpleName(refName);
            List<Node> candidates = context.getNodesByName(simpleName);
            for (Node candidate : candidates) {
                if (candidate.getFilePath().contains("/service/") ||
                    candidate.getFilePath().contains("/impl/")) {
                    logger.debug("[SpringResolver] 按 Service 约定解析: {} -> {}", refName, candidate.getId());
                    return candidate.getId();
                }
            }
        }

        // 2. XxxController → 查找 *Controller.java 文件中的节点
        if (refName.endsWith("Controller")) {
            String simpleName = ResolverUtils.extractSimpleName(refName);
            List<Node> candidates = context.getNodesByName(simpleName);
            for (Node candidate : candidates) {
                if (candidate.getFilePath().contains("/controller/")) {
                    logger.debug("[SpringResolver] 按 Controller 约定解析: {} -> {}", refName, candidate.getId());
                    return candidate.getId();
                }
            }
        }

        // 3. XxxRepository → 查找 *Repository.java 文件中的节点
        if (refName.endsWith("Repository")) {
            String simpleName = ResolverUtils.extractSimpleName(refName);
            List<Node> candidates = context.getNodesByName(simpleName);
            for (Node candidate : candidates) {
                if (candidate.getFilePath().contains("/repository/") ||
                    candidate.getFilePath().contains("/dao/")) {
                    logger.debug("[SpringResolver] 按 Repository 约定解析: {} -> {}", refName, candidate.getId());
                    return candidate.getId();
                }
            }
        }

        return null;
    }
}
