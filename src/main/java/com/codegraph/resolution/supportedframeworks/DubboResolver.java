package com.codegraph.resolution.supportedframeworks;

import com.codegraph.core.Node;
import com.codegraph.core.types.Language;
import com.codegraph.core.types.NodeKind;
import com.codegraph.core.types.Visibility;
import com.codegraph.resolution.ResolutionContext;
import com.codegraph.resolution.frameworks.FrameworkExtractionResult;
import com.codegraph.resolution.frameworks.FrameworkResolver;
import com.codegraph.resolution.frameworks.ResolverUtils;
import com.codegraph.resolution.frameworks.UnresolvedRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dubbo 框架解析器。
 *
 * 功能：
 * 1. 检测项目是否使用 Apache Dubbo / Alibaba Dubbo
 * 2. 从 @DubboService 注解的类中提取服务提供者端点（ROUTE 节点）
 * 3. 从 @DubboReference 注解的字段中提取服务消费者引用
 *
 * Dubbo 典型用法：
 * <pre>
 * // 服务接口（API 模块）
 * public interface UserService {
 *     UserDTO getUser(Long id);
 * }
 *
 * // 服务提供者（Provider）
 * @DubboService
 * public class UserServiceImpl implements UserService {
 *     public UserDTO getUser(Long id) { ... }
 * }
 *
 * // 服务消费者（Consumer）
 * @Service
 * public class OrderService {
 *     @DubboReference
 *     private UserService userService;  // 远程调用注入点
 *
 *     public void process() {
 *         userService.getUser(1L);  // 实际远程 RPC 调用
 *     }
 * }
 * </pre>
 */
public class DubboResolver implements FrameworkResolver {

    private static final Logger logger = LoggerFactory.getLogger(DubboResolver.class);

    // @DubboService 注解正则
    private static final Pattern DUBBO_SERVICE_PATTERN = Pattern.compile(
        "@DubboService\\b|@Service\\s*\\(\\s*(?:interfaceClass|interfaceName)\\s*="
    );

    // @DubboReference 注解正则：匹配 @DubboReference private XxxService xxxService;
    private static final Pattern DUBBO_REFERENCE_PATTERN = Pattern.compile(
        "@(?:Dubbo)?Reference\\s*(?:\\([^)]*\\))?\\s*\\n?\\s*" +
        "(?:private|protected|public)\\s+(\\w+(?:Service|Rpc|Api))\\s+(\\w+)\\s*;"
    );

    // @DubboService 类注解 + 类名提取
    private static final Pattern DUBBO_SERVICE_CLASS_PATTERN = Pattern.compile(
        "@(?:Dubbo)?Service\\s*(?:\\([^)]*\\))?\\s*\\n?\\s*" +
        "public\\s+class\\s+(\\w+)\\s+(?:extends\\s+\\w+\\s+)?implements\\s+([\\w.,\\s]+)"
    );

    // @DubboReference 简化检测（只匹配注解存在）
    private static final Pattern DUBBO_REFERENCE_DETECT = Pattern.compile(
        "@DubboReference\\b|@Reference\\s*\\(\\s*(?:version|group|timeout|check)"
    );

    // 检测 pom.xml 中的 Dubbo 依赖
    private static final Pattern DUBBO_POM_PATTERN = Pattern.compile(
        "org\\.apache\\.dubbo|com\\.alibaba\\.dubbo|dubbo-spring-boot|dubbo-dependencies"
    );

    // 检测 dubbo XML 配置
    private static final Pattern DUBBO_XML_PATTERN = Pattern.compile(
        "dubbo:reference|dubbo:service|xmlns:dubbo"
    );

    // 方法声明正则（用于提取 Dubbo 服务暴露的方法）
    private static final Pattern METHOD_SIGNATURE_PATTERN = Pattern.compile(
        "^\\s*(?:public|private|protected)\\s+" +
        "(?:static\\s+)?" +
        "(?:synchronized\\s+)?" +
        "(?:<[^>]+>\\s+)?" +
        "([\\w.<>\\[\\],\\s]+)\\s+" +   // 返回类型
        "(\\w+)\\s*\\([^)]*\\)",          // 方法名
        Pattern.MULTILINE
    );

    @Override
    public String getName() {
        return "dubbo";
    }

    @Override
    public List<Language> getLanguages() {
        return ResolverUtils.JAVA_KOTLIN_LANGS;
    }

    @Override
    public boolean detect(ResolutionContext context) {
        // 策略 1：检查 pom.xml 中的 Dubbo 依赖
        String pomContent = context.readFile("pom.xml");
        if (pomContent != null && DUBBO_POM_PATTERN.matcher(pomContent).find()) {
            logger.info("[DubboResolver] 检测到 Dubbo 依赖 (pom.xml)");
            return true;
        }

        // 策略 2：检查 XML 配置文件（Spring XML 方式）
        List<String> allFiles = context.getAllFiles();
        for (String filePath : allFiles) {
            if (filePath.endsWith(".xml")) {
                String content = context.readFile(filePath);
                if (content != null && DUBBO_XML_PATTERN.matcher(content).find()) {
                    logger.info("[DubboResolver] 检测到 Dubbo XML 配置 ({})", filePath);
                    return true;
                }
            }
        }

        // 策略 3：扫描 Java 文件查找 Dubbo 注解
        int scanned = 0;
        for (String filePath : allFiles) {
            if (!filePath.endsWith(".java")) continue;
            String content = context.readFile(filePath);
            if (content != null) {
                if (DUBBO_SERVICE_PATTERN.matcher(content).find()) {
                    logger.info("[DubboResolver] 检测到 @DubboService/@Service 注解 ({})", filePath);
                    return true;
                }
                if (DUBBO_REFERENCE_DETECT.matcher(content).find()) {
                    logger.info("[DubboResolver] 检测到 @DubboReference/@Reference 注解 ({})", filePath);
                    return true;
                }
            }
            if (++scanned > 100) break;
        }
        return false;
    }

    @Override
    public FrameworkExtractionResult extract(String filePath, String content, ResolutionContext context) {
        FrameworkExtractionResult result = new FrameworkExtractionResult();

        if (!filePath.endsWith(".java")) {
            return result;
        }

        // 步骤 A：提取 @DubboService 服务提供者端点
        extractDubboServices(filePath, content, result);

        // 步骤 B：提取 @DubboReference 消费者引用
        extractDubboReferences(filePath, content, result, context);

        return result;
    }

    /**
     * 提取 @DubboService 类中暴露的 RPC 方法为 ROUTE 节点。
     */
    private void extractDubboServices(String filePath, String content, FrameworkExtractionResult result) {
        Matcher classMatcher = DUBBO_SERVICE_CLASS_PATTERN.matcher(content);
        while (classMatcher.find()) {
            String className = classMatcher.group(1);
            String implementsClause = classMatcher.group(2);

            if (implementsClause == null) continue;

            // 解析实现的接口名（可能有多个，逗号分隔）
            String[] interfaces = implementsClause.split(",");
            String serviceInterface = interfaces[0].trim();
            serviceInterface = ResolverUtils.extractSimpleName(serviceInterface);

            int classStart = classMatcher.start();

            // 提取该类中所有 public 方法作为 Dubbo 服务端点
            Matcher methodMatcher = METHOD_SIGNATURE_PATTERN.matcher(content);
            while (methodMatcher.find()) {
                if (methodMatcher.start() < classStart) continue;

                String returnType = methodMatcher.group(1).trim();
                String methodName = methodMatcher.group(2);

                // 跳过构造函数（方法名等于类名）
                if (methodName.equals(className)) continue;
                // 跳过 Object 方法
                if (isObjectMethod(methodName)) continue;

                int line = ResolverUtils.getLineNumber(content, methodMatcher.start());

                Node routeNode = new Node();
                routeNode.setKind(NodeKind.ROUTE);
                routeNode.setName("DUBBO " + serviceInterface + "." + methodName);
                routeNode.setQualifiedName(filePath + ":DUBBO " + serviceInterface + "." + methodName);
                routeNode.setId("ROUTE:DUBBO:" + serviceInterface + "." + methodName);
                routeNode.setFilePath(filePath);
                routeNode.setLanguage(Language.JAVA);
                routeNode.setStartLine(line);
                routeNode.setEndLine(line);
                routeNode.setVisibility(Visibility.PUBLIC);
                routeNode.setReturnType(returnType);
                routeNode.setDecorators(Arrays.asList(
                    "@DubboService",
                    "interface=" + serviceInterface
                ));

                result.addNode(routeNode);
                logger.debug("[DubboResolver] 提取 Dubbo 服务方法: {}.{} (line {})",
                    serviceInterface, methodName, line);
            }
        }
    }

    /**
     * 提取 @DubboReference 消费者字段注入点，生成 UnresolvedRef。
     */
    private void extractDubboReferences(String filePath, String content, FrameworkExtractionResult result,
                                        ResolutionContext context) {
        Matcher matcher = DUBBO_REFERENCE_PATTERN.matcher(content);
        boolean found = false;
        while (matcher.find()) {
            String fieldType = matcher.group(1);   // 如 UserService
            String fieldName = matcher.group(2);   // 如 userService
            found = true;

            // 从 DB 获取当前文件中所有已有的 METHOD 节点
            List<Node> fileNodes = context.getNodesInFile(filePath);
            logger.debug("[DubboResolver] 文件 {} 从DB获取到 {} 个节点, 字段={}", filePath, fileNodes.size(), fieldName);

            int refCount = 0;
            for (Node node : fileNodes) {
                if (node.getKind() != NodeKind.METHOD) continue;

                String methodBody = ResolverUtils.extractMethodBody(content, node.getStartLine());
                if (methodBody != null && methodBody.contains(fieldName + ".")) {
                    UnresolvedRef ref = new UnresolvedRef();
                    ref.setFromNodeId(node.getId());  // 使用 DB 中的真实节点 ID
                    ref.setReferenceName(fieldType);
                    ref.setReferenceKind("calls");
                    ref.setFilePath(filePath);
                    ref.setLine(node.getStartLine());
                    ref.setColumn(0);

                    result.addReference(ref);
                    refCount++;
                    logger.debug("[DubboResolver] Dubbo 引用: {} -> {} (line {}, id={})",
                        node.getName(), fieldType, node.getStartLine(), node.getId());
                }
            }
            logger.info("[DubboResolver] 文件 {} 生成 {} 个引用", filePath, refCount);
        }
        if (!found) {
            logger.debug("[DubboResolver] 文件 {} 未找到 @DubboReference 字段", filePath);
        }
    }

    @Override
    public String resolve(String refName, String refKind, String sourceFile, ResolutionContext context) {
        if (!"calls".equals(refKind)) return null;

        logger.debug("[DubboResolver] resolve: refName={}, refKind={}, sourceFile={}", refName, refKind, sourceFile);

        // refName 是 Dubbo 服务接口的类型名（如 UserService）
        // 查找所有 ROUTE 节点，匹配 DUBBO 前缀 + 接口名
        List<Node> allNodes;
        try {
            allNodes = context.getQueries().getAllNodes();
        } catch (Exception e) {
            return null;
        }

        logger.info("[DubboResolver] 扫描全部 {} 个节点寻找 DUBBO ROUTE 匹配", allNodes.size());
        for (Node node : allNodes) {
            if (node.getKind() != NodeKind.ROUTE) continue;
            if (node.getName() == null) continue;

            // 名称格式："DUBBO {interfaceName}.{methodName}"
            if (node.getName().startsWith("DUBBO " + refName + ".")) {
                logger.info("[DubboResolver] 解析 Dubbo ROUTE 引用: {} -> {}", refName, node.getId());
                return node.getId();
            }
        }

        // 备选：通过 qualifiedName 匹配
        for (Node node : allNodes) {
            if (node.getKind() == NodeKind.ROUTE &&
                node.getQualifiedName() != null &&
                node.getQualifiedName().contains(":DUBBO " + refName + ".")) {
                logger.info("[DubboResolver] 解析 Dubbo 引用 (qn): {} -> {}", refName, node.getId());
                return node.getId();
            }
        }

        logger.warn("[DubboResolver] 未解析到 Dubbo 引用: {}", refName);
        return null;
    }

    /**
     * 判断是否是 java.lang.Object 的方法。
     */
    private boolean isObjectMethod(String methodName) {
        return "equals".equals(methodName) ||
            "hashCode".equals(methodName) ||
            "toString".equals(methodName) ||
            "clone".equals(methodName) ||
            "finalize".equals(methodName) ||
            "getClass".equals(methodName) ||
            "notify".equals(methodName) ||
            "notifyAll".equals(methodName) ||
            "wait".equals(methodName);
    }
}
