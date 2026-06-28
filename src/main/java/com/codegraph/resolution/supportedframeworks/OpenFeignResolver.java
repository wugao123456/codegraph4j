package com.codegraph.resolution.supportedframeworks;

import com.codegraph.core.Edge;
import com.codegraph.core.Node;
import com.codegraph.core.types.EdgeKind;
import com.codegraph.core.types.Language;
import com.codegraph.core.types.NodeKind;
import com.codegraph.core.types.Visibility;
import com.codegraph.resolution.ResolutionContext;
import com.codegraph.resolution.frameworks.FrameworkExtractionResult;
import com.codegraph.resolution.frameworks.FrameworkResolver;
import com.codegraph.resolution.frameworks.UnresolvedRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenFeign 框架解析器。
 *
 * 功能：
 * 1. 检测项目是否使用 Spring Cloud OpenFeign
 * 2. 从 @FeignClient 注解的接口中提取远程调用端点（ROUTE 节点）
 * 3. 解析 @Autowired 注入的 Feign 客户端引用
 *
 * OpenFeign 典型用法：
 * <pre>
 * // 定义远程服务接口
 * @FeignClient(name = "user-service", path = "/api/users")
 * public interface UserClient {
 *     @GetMapping("/{id}")
 *     UserDTO getUser(@PathVariable("id") Long id);
 * }
 *
 * // 注入并使用
 * @Service
 * public class OrderService {
 *     @Autowired
 *     private UserClient userClient;  // 远程调用注入点
 *
 *     public void process() {
 *         userClient.getUser(1L);  // 实际远程调用
 *     }
 * }
 * </pre>
 */
public class OpenFeignResolver implements FrameworkResolver {

    private static final Logger logger = LoggerFactory.getLogger(OpenFeignResolver.class);

    // @FeignClient 注解正则：匹配 name/url/path 属性
    private static final Pattern FEIGN_CLIENT_PATTERN = Pattern.compile(
        "@FeignClient\\s*\\(\\s*" +
        "(?:[^)]*?)" +
        "(?:name\\s*=\\s*\"([^\"]+)\")?" +
        "(?:[^)]*?)" +
        "(?:path\\s*=\\s*\"([^\"]+)\")?" +
        "(?:[^)]*?)" +
        "(?:url\\s*=\\s*\"([^\"]+)\")?",
        Pattern.DOTALL
    );

    // HTTP 方法注解正则（同 SpringResolver）
    private static final Pattern REQUEST_MAPPING_PATTERN = Pattern.compile(
        "@(Get|Post|Put|Delete|Patch|Request)Mapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\""
    );

    // @FeignClient 检测正则（简化，只匹配注解存在）
    private static final Pattern FEIGN_CLIENT_DETECT = Pattern.compile(
        "@FeignClient\\s*\\("
    );

    // @Autowired 字段注入正则：匹配 @Autowired private XxxClient xxxClient;
    private static final Pattern AUTOWIRED_FIELD_PATTERN = Pattern.compile(
        "@Autowired\\s+\\n?\\s*private\\s+(\\w+(?:Client|Feign|Service))\\s+(\\w+)\\s*;"
    );

    // Feign 接口内带 @XxxMapping 的方法：提取 HTTP 方法、路径、方法名
    private static final Pattern FEIGN_ANNOTATED_METHOD = Pattern.compile(
        "@(Get|Post|Put|Delete|Patch|Request)Mapping\\s*\\([^)]*\"([^\"]+)\"[^)]*\\)" +
        "\\s*\\n?\\s*(?:[\\w<>\\[\\],\\s]+)\\s+(\\w+)\\s*\\("
    );

    // Feign 接口内无注解的默认方法签名（方法名提取）
    private static final Pattern FEIGN_DEFAULT_METHOD = Pattern.compile(
        "^\\s*(?:[\\w.<>]+)\\s+(\\w+)\\s*\\([^)]*\\)\\s*(?:throws\\s+[\\w,\\s]+)?\\s*;",
        Pattern.MULTILINE
    );

    // 调用点方法名提取：fieldName.methodName(
    private static final Pattern FEIGN_CALL_PATTERN = Pattern.compile(
        "(\\w+)\\.(\\w+)\\s*\\("
    );

    // 检测 pom.xml 中的 OpenFeign 依赖
    private static final Pattern FEIGN_POM_PATTERN = Pattern.compile(
        "spring-cloud-starter-openfeign|spring-cloud-openfeign|openfeign"
    );

    @Override
    public String getName() {
        return "openfeign";
    }

    @Override
    public List<Language> getLanguages() {
        return Arrays.asList(Language.JAVA, Language.KOTLIN);
    }

    @Override
    public boolean detect(ResolutionContext context) {
        // 策略 1：检查 pom.xml 中的 OpenFeign 依赖
        String pomContent = context.readFile("pom.xml");
        if (pomContent != null && FEIGN_POM_PATTERN.matcher(pomContent).find()) {
            logger.info("[OpenFeignResolver] 检测到 OpenFeign 依赖 (pom.xml)");
            return true;
        }

        // 策略 2：扫描 Java 文件查找 @FeignClient 注解
        List<String> files = context.getAllFiles();
        int scanned = 0;
        for (String filePath : files) {
            if (!filePath.endsWith(".java")) continue;
            String content = context.readFile(filePath);
            if (content != null && FEIGN_CLIENT_DETECT.matcher(content).find()) {
                logger.info("[OpenFeignResolver] 检测到 @FeignClient 注解 ({})", filePath);
                return true;
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

        // 步骤 A：提取 @FeignClient 接口的远程端点（ROUTE 节点）
        // 此阶段只提取节点定义，不建立引用边（跨文件引用在 postExtract 中处理）
        extractFeignRoutes(filePath, content, result);

        return result;
    }

    /**
     * 后处理：在所有文件提取完成后，扫描 @Autowired 注入并建立引用边。
     * 此时所有 ROUTE 节点均已入库，可正确解析跨文件引用。
     */
    @Override
    public List<Node> postExtract(ResolutionContext context) {
        List<Node> extraNodes = new ArrayList<>();
        List<String> allFiles = context.getAllFiles();
        int edgeCount = 0;

        for (String filePath : allFiles) {
            if (!filePath.endsWith(".java")) continue;
            String content = context.readFile(filePath);
            if (content == null) continue;

            // 扫描 @Autowired Feign 客户端注入
            edgeCount += resolveFeignInjections(filePath, content, context);
        }

        logger.info("[OpenFeignResolver] postExtract 完成，创建 {} 条 Feign 引用边", edgeCount);
        return extraNodes;
    }

    /**
     * 扫描并解析文件中的 @Autowired Feign 客户端注入，直接创建 REFERENCES 边。
     */
    private int resolveFeignInjections(String filePath, String content, ResolutionContext context) {
        int totalEdges = 0;
        Matcher matcher = AUTOWIRED_FIELD_PATTERN.matcher(content);
        while (matcher.find()) {
            String fieldType = matcher.group(1);   // 如 UserClient
            String fieldName = matcher.group(2);   // 如 userClient

            int line = getLineNumber(content, matcher.start());
            logger.info("[OpenFeignResolver] 发现 @Autowired 字段: type={}, name={}, file={}, line={}",
                fieldType, fieldName, filePath, line);

            // 验证该字段类型是否确实是 @FeignClient 接口
            boolean hasImport = hasFeignClientImport(content, fieldType);
            boolean hasInFile = hasFeignClientInFile(content, fieldType);
            boolean isClientType = fieldType.endsWith("Client") || fieldType.endsWith("Feign");
            if (!hasImport && !hasInFile && !isClientType) {
                logger.info("[OpenFeignResolver] 跳过非 Feign 字段: type={}, import={}, local={}, isClient={}",
                    fieldType, hasImport, hasInFile, isClientType);
                continue;
            }
            logger.info("[OpenFeignResolver] 确认为 Feign 客户端字段: type={}, name={}, import={}, local={}",
                fieldType, fieldName, hasImport, hasInFile);

            // 从 DB 获取当前文件中所有已有的 METHOD 节点
            List<Node> fileNodes = context.getNodesInFile(filePath);
            logger.info("[OpenFeignResolver] 文件 {} 从DB获取到 {} 个方法节点", filePath, fileNodes.size());
            int edgeCount = 0;
            for (Node node : fileNodes) {
                if (node.getKind() != NodeKind.METHOD) continue;

                String methodBody = extractMethodBody(content, node.getStartLine());
                if (methodBody != null && methodBody.contains(fieldName + ".")) {
                    // 从方法体中提取被调用的具体方法名
                    String calledMethod = extractCalledMethod(methodBody, fieldName);
                    logger.info("[OpenFeignResolver] 方法 {} 调用 {}.{}()，开始解析路由...",
                        node.getName(), fieldName, calledMethod != null ? calledMethod : "?");
                    // 解析目标 ROUTE 节点（精确匹配）
                    String targetId = resolveFeignTarget(fieldType, calledMethod, context);
                    if (targetId != null) {
                        Edge edge = new Edge();
                        edge.setSource(node.getId());
                        edge.setTarget(targetId);
                        edge.setKind(EdgeKind.REFERENCES);
                        edge.setLine(node.getStartLine());
                        edge.setColumn(0);
                        edge.setProvenance("framework:openfeign");

                        try {
                            context.getQueries().insertEdge(edge);
                            edgeCount++;
                            totalEdges++;
                            logger.info("[OpenFeignResolver] 边创建成功: {}.{}() → {} (targetId={})",
                                fieldName, calledMethod, fieldType, targetId);
                        } catch (Exception e) {
                            logger.warn("[OpenFeignResolver] 边插入失败: source={}, target={}, error={}",
                                node.getId(), targetId, e.getMessage());
                        }
                    } else {
                        logger.warn("[OpenFeignResolver] 解析失败: {}.{}() 未找到对应 ROUTE 节点",
                            fieldName, calledMethod);
                    }
                }
            }
            if (edgeCount == 0) {
                logger.info("[OpenFeignResolver] 文件 {} 中字段 {} 未生成任何边", filePath, fieldName);
            } else {
                logger.info("[OpenFeignResolver] 文件 {} 字段 {} 创建 {} 条 Feign 引用边",
                    filePath, fieldName, edgeCount);
            }
        }
        return totalEdges;
    }

    /**
     * 从方法体中提取对指定字段的具体方法调用名。
     * 如从 "userClient.getUserById(userId)" 中提取 "getUserById"。
     */
    private String extractCalledMethod(String methodBody, String fieldName) {
        Matcher m = FEIGN_CALL_PATTERN.matcher(methodBody);
        while (m.find()) {
            if (m.group(1).equals(fieldName)) {
                return m.group(2);
            }
        }
        logger.info("[OpenFeignResolver] 未能从方法体中提取调用方法名: fieldName={}", fieldName);
        return null;
    }

    /**
     * 解析 Feign 客户端的具体方法调用到对应的 ROUTE 节点 ID。
     *
     * @param typeName   Feign 客户端接口名（如 "UserClient"）
     * @param methodName 被调用的方法名（如 "getUserById"），为 null 时使用类型模糊匹配
     */
    private String resolveFeignTarget(String typeName, String methodName, ResolutionContext context) {
        logger.info("[OpenFeignResolver] 解析 Feign 路由: type={}, method={}", typeName, methodName);

        // 查找 Feign 接口文件内容并解析方法→路由映射
        String feignContent = findFeignInterfaceContent(typeName, context);
        if (feignContent == null) {
            logger.info("[OpenFeignResolver] 未找到 Feign 接口文件: type={}，回退到模糊匹配", typeName);
            return resolveFeignTargetByType(typeName, context);
        }
        logger.info("[OpenFeignResolver] 找到 Feign 接口文件: type={}", typeName);

        if (methodName == null) {
            logger.info("[OpenFeignResolver] 未提取到调用方法名，回退到模糊匹配");
            return resolveFeignTargetByType(typeName, context);
        }

        // 解析接口中的 basePath
        String basePath = extractBasePath(feignContent);
        logger.info("[OpenFeignResolver] 提取 basePath: '{}'", basePath);

        // 构建方法名 → 路由键的映射
        Map<String, String> methodRouteMap = parseFeignMethodRouteMap(feignContent, basePath);
        logger.info("[OpenFeignResolver] 方法路由映射: {} 个条目 -> {}",
            methodRouteMap.size(), methodRouteMap);

        String routeKey = methodRouteMap.get(methodName);
        if (routeKey == null) {
            logger.warn("[OpenFeignResolver] 方法 '{}' 不在映射中，可用方法: {}，回退到模糊匹配",
                methodName, methodRouteMap.keySet());
            return resolveFeignTargetByType(typeName, context);
        }

        // 精确匹配 ROUTE 节点
        String qualifiedName = typeName + ":" + routeKey;
        String targetId = findRouteByQualifiedName(qualifiedName, context);
        if (targetId != null) {
            logger.info("[OpenFeignResolver] 精确匹配成功: {}.{}() -> qualifiedName='{}', targetId={}",
                typeName, methodName, qualifiedName, targetId);
            return targetId;
        }

        logger.warn("[OpenFeignResolver] 精确匹配失败: qualifiedName='{}' 未找到对应 ROUTE 节点，回退到模糊匹配",
            qualifiedName);
        return resolveFeignTargetByType(typeName, context);
    }

    /**
     * 按类型名模糊匹配第一个 ROUTE 节点（回退策略）。
     */
    private String resolveFeignTargetByType(String typeName, ResolutionContext context) {
        List<Node> allNodes;
        try {
            allNodes = context.getQueries().getAllNodes();
        } catch (Exception e) {
            logger.warn("[OpenFeignResolver] 获取全部节点失败: {}", e.getMessage());
            return null;
        }

        logger.info("[OpenFeignResolver] 模糊匹配: 扫描 {} 个节点寻找 {} 的 ROUTE 节点", allNodes.size(), typeName);

        for (Node node : allNodes) {
            if (node.getKind() == NodeKind.ROUTE &&
                node.getDecorators() != null) {
                for (String decorator : node.getDecorators()) {
                    if (decorator.contains("@FeignClient") &&
                        node.getQualifiedName() != null &&
                        node.getQualifiedName().startsWith(typeName + ":")) {
                        logger.info("[OpenFeignResolver] 模糊匹配(装饰器): {} -> qualifiedName='{}', targetId={}",
                            typeName, node.getQualifiedName(), node.getId());
                        return node.getId();
                    }
                }
            }
        }

        for (Node node : allNodes) {
            if (node.getKind() == NodeKind.ROUTE &&
                node.getQualifiedName() != null &&
                node.getQualifiedName().startsWith(typeName + ":")) {
                logger.info("[OpenFeignResolver] 模糊匹配(qualifiedName): {} -> qualifiedName='{}', targetId={}",
                    typeName, node.getQualifiedName(), node.getId());
                return node.getId();
            }
        }

        logger.warn("[OpenFeignResolver] 模糊匹配失败: 未找到类型 {} 的任何 ROUTE 节点", typeName);
        return null;
    }

    /**
     * 扫描所有文件，找到指定 Feign 接口类型的源文件内容。
     */
    private String findFeignInterfaceContent(String typeName, ResolutionContext context) {
        List<String> allFiles = context.getAllFiles();
        for (String filePath : allFiles) {
            if (!filePath.endsWith(".java")) continue;
            String content = context.readFile(filePath);
            if (content != null && hasFeignClientInFile(content, typeName)) {
                logger.info("[OpenFeignResolver] 找到 Feign 接口: type={}, file={}", typeName, filePath);
                return content;
            }
        }
        logger.info("[OpenFeignResolver] 未找到 Feign 接口文件: type={}（扫描了 {} 个文件）", typeName, allFiles.size());
        return null;
    }

    /**
     * 从 @FeignClient 注解中提取 basePath。
     */
    private String extractBasePath(String content) {
        Matcher m = FEIGN_CLIENT_PATTERN.matcher(content);
        if (m.find()) {
            String basePath = m.group(2);
            return basePath != null ? basePath : "";
        }
        return "";
    }

    /**
     * 解析 Feign 接口，构建方法名 → 路由键 的映射。
     * 路由键格式："{HTTP方法} {完整路径}" 或 "{方法名}"（默认方法）。
     */
    private Map<String, String> parseFeignMethodRouteMap(String content, String basePath) {
        Map<String, String> map = new LinkedHashMap<>();
        Set<String> annotatedMethods = new HashSet<>();

        // 匹配带 @XxxMapping 注解的方法
        Matcher m = FEIGN_ANNOTATED_METHOD.matcher(content);
        while (m.find()) {
            String httpMethod = m.group(1);
            String methodPath = m.group(2);
            String methodName = m.group(3);
            String fullPath = basePath + methodPath;
            map.put(methodName, httpMethod + " " + fullPath);
            annotatedMethods.add(methodName);
        }

        // 匹配无注解的默认方法
        Matcher defaultMatcher = FEIGN_DEFAULT_METHOD.matcher(content);
        while (defaultMatcher.find()) {
            String methodName = defaultMatcher.group(1);
            if ("interface".equals(methodName)) continue;
            if (annotatedMethods.contains(methodName)) continue;

            // 检查前面 80 字符是否有 @XxxMapping 注解
            String before = content.substring(
                Math.max(0, defaultMatcher.start() - 80), defaultMatcher.start());
            if (before.contains("@RequestMapping") ||
                before.contains("@GetMapping") ||
                before.contains("@PostMapping") ||
                before.contains("@PutMapping") ||
                before.contains("@DeleteMapping") ||
                before.contains("@PatchMapping")) {
                continue;
            }

            map.put(methodName, methodName);
        }

        logger.info("[OpenFeignResolver] Feign 方法路由映射完成: basePath='{}', 注解方法={}, 默认方法={}, 总计={}",
            basePath, annotatedMethods.size(), map.size() - annotatedMethods.size(), map.size());
        return map;
    }

    /**
     * 通过 qualifiedName 精确查找 ROUTE 节点。
     */
    private String findRouteByQualifiedName(String qualifiedName, ResolutionContext context) {
        List<Node> allNodes;
        try {
            allNodes = context.getQueries().getAllNodes();
        } catch (Exception e) {
            logger.warn("[OpenFeignResolver] 查找 ROUTE 节点失败: qualifiedName='{}', error={}", qualifiedName, e.getMessage());
            return null;
        }

        for (Node node : allNodes) {
            if (node.getKind() == NodeKind.ROUTE &&
                node.getQualifiedName() != null &&
                node.getQualifiedName().equals(qualifiedName)) {
                return node.getId();
            }
        }
        logger.info("[OpenFeignResolver] 未找到 ROUTE 节点: qualifiedName='{}'（扫描了 {} 个节点）", qualifiedName, allNodes.size());
        return null;
    }

    /**
     * 提取 @FeignClient 接口中声明的方法为 ROUTE 节点。
     */
    private void extractFeignRoutes(String filePath, String content, FrameworkExtractionResult result) {
        Matcher clientMatcher = FEIGN_CLIENT_PATTERN.matcher(content);
        while (clientMatcher.find()) {
            String serviceName = clientMatcher.group(1);
            String basePath = clientMatcher.group(2);
            String url = clientMatcher.group(3);

            if (serviceName == null) serviceName = "unknown";
            if (basePath == null) basePath = "";
            if (url == null) url = "";

            // 找到 @FeignClient 所在接口的方法范围（注解后到 interface 结束的大括号）
            int clientStart = clientMatcher.start();

            // 查找接口名称
            String interfaceName = extractInterfaceName(content, clientStart);
            if (interfaceName == null) continue;

            // 提取该接口方法区域的路由
            Matcher methodMatcher = REQUEST_MAPPING_PATTERN.matcher(content);
            while (methodMatcher.find()) {
                // 简单判断：该 mapping 注解是否在 @FeignClient 的接口范围内
                // 通过位置判断：在 @FeignClient 之后，并且在对应的 interface 体结束之前
                if (methodMatcher.start() < clientStart) continue;

                String httpMethod = methodMatcher.group(1);
                String methodPath = methodMatcher.group(2);
                String fullPath = basePath + methodPath;

                int pos = methodMatcher.start();
                int line = getLineNumber(content, pos);

                Node routeNode = new Node();
                routeNode.setKind(NodeKind.ROUTE);
                routeNode.setName("FEIGN " + httpMethod + " " + fullPath);
                routeNode.setQualifiedName(interfaceName + ":" + httpMethod + " " + fullPath);
                routeNode.setId("ROUTE:" + interfaceName + ":" + httpMethod + " " + fullPath);
                routeNode.setFilePath(filePath);
                routeNode.setLanguage(Language.JAVA);
                routeNode.setStartLine(line);
                routeNode.setEndLine(line);
                routeNode.setVisibility(Visibility.PUBLIC);
                routeNode.setDecorators(Arrays.asList("@FeignClient(name=\"" + serviceName + "\")"));

                result.addNode(routeNode);
                logger.debug("[OpenFeignResolver] 提取 Feign 路由: {} {} (service={}, line {})",
                    httpMethod, fullPath, serviceName, line);
            }

            // 也提取没有 @RequestMapping 的方法（只有方法签名的 Feign 方法）
            // 这些方法默认映射为 GET + 方法名
            extractFeignDefaultMethods(filePath, content, clientStart, interfaceName,
                serviceName, basePath, result);
        }
    }

    /**
     * 提取 @FeignClient 接口中没有显式 @RequestMapping 的默认方法。
     * OpenFeign 支持通过方法名和参数推导默认路由。
     */
    private void extractFeignDefaultMethods(String filePath, String content, int clientStart,
                                            String interfaceName, String serviceName,
                                            String basePath, FrameworkExtractionResult result) {
        // 匹配接口内的方法签名（没有 @RequestMapping 修饰）
        // 模式：返回类型 方法名(参数...)
        Pattern defaultMethodPattern = Pattern.compile(
            "^\\s*(?:[\\w.<>]+)\\s+(\\w+)\\s*\\([^)]*\\)\\s*(?:throws\\s+[\\w,\\s]+)?\\s*;",
            Pattern.MULTILINE
        );

        Matcher matcher = defaultMethodPattern.matcher(content);
        while (matcher.find()) {
            if (matcher.start() < clientStart) continue;

            // 检查该方法上是否有 @RequestMapping 注解（向前回溯几行）
            String beforeMethod = content.substring(
                Math.max(0, matcher.start() - 80), matcher.start());
            if (beforeMethod.contains("@RequestMapping") ||
                beforeMethod.contains("@GetMapping") ||
                beforeMethod.contains("@PostMapping") ||
                beforeMethod.contains("@PutMapping") ||
                beforeMethod.contains("@DeleteMapping") ||
                beforeMethod.contains("@PatchMapping")) {
                continue; // 已有显式注解，跳过
            }

            String methodName = matcher.group(1);
            if ("interface".equals(methodName)) continue; // 跳过 interface 关键字

            int line = getLineNumber(content, matcher.start());

            Node routeNode = new Node();
            routeNode.setKind(NodeKind.ROUTE);
            routeNode.setName("FEIGN " + methodName + "(default)");
            routeNode.setQualifiedName(interfaceName + ":" + methodName);
            routeNode.setId("ROUTE:" + interfaceName + ":" + methodName);
            routeNode.setFilePath(filePath);
            routeNode.setLanguage(Language.JAVA);
            routeNode.setStartLine(line);
            routeNode.setEndLine(line);
            routeNode.setVisibility(Visibility.PUBLIC);
            routeNode.setDecorators(Arrays.asList(
                "@FeignClient(name=\"" + serviceName + "\")",
                "@RequestMapping(default)"
            ));

            result.addNode(routeNode);
            logger.debug("[OpenFeignResolver] 提取 Feign 默认方法: {} (line {})", methodName, line);
        }
    }

    /**
     * 提取 @Autowired Feign 客户端字段注入点，生成 UnresolvedRef。
     */
    private void extractFeignInjections(String filePath, String content, FrameworkExtractionResult result,
                                        ResolutionContext context) {
        // 快速预检：文件中是否有 @Autowired
        int autowiredIdx = content.indexOf("@Autowired");
        logger.info("[OpenFeignResolver] extractFeignInjections: file={}, @Autowired at idx={}", filePath, autowiredIdx);

        Matcher matcher = AUTOWIRED_FIELD_PATTERN.matcher(content);
        boolean found = false;
        while (matcher.find()) {
            String fieldType = matcher.group(1);   // 如 UserClient
            String fieldName = matcher.group(2);   // 如 userClient
            found = true;

            int pos = matcher.start();
            int line = getLineNumber(content, pos);

            // 验证该字段类型是否确实是 @FeignClient 接口
            // 策略：有 import 声明 或 同包下的 @FeignClient 接口 或 类型名以 Client 结尾
            boolean hasImport = hasFeignClientImport(content, fieldType);
            boolean hasInFile = hasFeignClientInFile(content, fieldType);
            boolean isClientType = fieldType.endsWith("Client") || fieldType.endsWith("Feign");
            if (!hasImport && !hasInFile && !isClientType) {
                logger.info("[OpenFeignResolver] 跳过 {} (line {}), 无Feign导入定义 (import={}, local={})",
                    fieldType, line, hasImport, hasInFile);
                continue;
            }

            // 从 DB 获取当前文件中所有已有的 METHOD 节点
            List<Node> fileNodes = context.getNodesInFile(filePath);
            logger.info("[OpenFeignResolver] 文件 {} 从DB获取到 {} 个节点, 字段={}", filePath, fileNodes.size(), fieldName);

            int refCount = 0;
            for (Node node : fileNodes) {
                if (node.getKind() != NodeKind.METHOD) continue;

                // 检查该方法体内是否引用了该 Feign 字段
                String methodBody = extractMethodBody(content, node.getStartLine());
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
                    logger.info("[OpenFeignResolver] Feign 注入引用: {} -> {} (line {}, id={})",
                        node.getName(), fieldType, node.getStartLine(), node.getId());
                }
            }
            logger.info("[OpenFeignResolver] 文件 {} 生成 {} 个引用", filePath, refCount);
        }
        if (!found) {
            logger.debug("[OpenFeignResolver] 文件 {} 未找到 @Autowired 字段", filePath);
        }
    }

    /**
     * 检查文件内容中是否有指定类型的 @FeignClient 定义。
     */
    private boolean hasFeignClientInFile(String content, String typeName) {
        Pattern p = Pattern.compile(
            "@FeignClient[^}]*?interface\\s+" + Pattern.quote(typeName) + "\\b",
            Pattern.DOTALL
        );
        return p.matcher(content).find();
    }

    /**
     * 检查是否有 import 语句导入 Feign 客户端接口。
     */
    private boolean hasFeignClientImport(String content, String typeName) {
        Pattern p = Pattern.compile(
            "import\\s+[\\w.]+\\." + Pattern.quote(typeName) + "\\s*;"
        );
        return p.matcher(content).find();
    }

    /**
     * 从文件内容中提取指定方法所在行的代码片段（方法体）。
     */
    private String extractMethodBody(String content, int methodStartLine) {
        String[] lines = content.split("\n");
        if (methodStartLine < 1 || methodStartLine > lines.length) return null;

        StringBuilder body = new StringBuilder();
        for (int i = methodStartLine - 1; i < Math.min(lines.length, methodStartLine + 30); i++) {
            body.append(lines[i]).append("\n");
        }
        return body.toString();
    }

    /**
     * 从 @FeignClient 注解位置向后查找接口名称。
     */
    private String extractInterfaceName(String content, int clientStart) {
        // @FeignClient 后面通常跟 interface 关键字
        Pattern p = Pattern.compile(
            "@FeignClient[^}]*?interface\\s+(\\w+)",
            Pattern.DOTALL
        );

        // 从 clientStart 位置开始搜索
        String substring = content.substring(clientStart);
        Matcher m = p.matcher(substring);
        if (m.find()) {
            return m.group(1);
        }

        // 备选：在注解后最近的一个 interface 声明
        Pattern p2 = Pattern.compile("interface\\s+(\\w+)");
        String afterAnno = content.substring(clientStart);
        Matcher m2 = p2.matcher(afterAnno);
        if (m2.find()) {
            return m2.group(1);
        }

        return null;
    }

    @Override
    public String resolve(String refName, String refKind, String sourceFile, ResolutionContext context) {
        if (!"calls".equals(refKind)) return null;

        logger.debug("[OpenFeignResolver] resolve: refName={}, refKind={}, sourceFile={}", refName, refKind, sourceFile);

        // refName 是 Feign 客户端接口的类型名（如 UserClient）
        // 在已索引节点中查找该类型对应的 ROUTE 节点
        List<Node> candidates = context.getNodesByLowerName(refName.toLowerCase());
        logger.debug("[OpenFeignResolver] 通过 lowerName 查询到 {} 个候选节点", candidates.size());
        for (Node candidate : candidates) {
            if (candidate.getKind() == NodeKind.INTERFACE &&
                candidate.getDecorators() != null) {
                for (String decorator : candidate.getDecorators()) {
                    if (decorator.contains("@FeignClient")) {
                        logger.debug("[OpenFeignResolver] 解析 Feign 引用(通过装饰器): {} -> {}", refName, candidate.getId());
                        return candidate.getId();
                    }
                }
            }
        }

        // 通过 qualifiedName 匹配 ROUTE 节点
        List<Node> allNodes;
        try {
            allNodes = context.getQueries().getAllNodes();
        } catch (Exception e) {
            return null;
        }

        logger.debug("[OpenFeignResolver] 扫描全部 {} 个节点寻找 ROUTE 匹配", allNodes.size());
        for (Node node : allNodes) {
            if (node.getKind() == NodeKind.ROUTE &&
                node.getQualifiedName() != null &&
                node.getQualifiedName().startsWith(refName + ":")) {
                logger.info("[OpenFeignResolver] 解析 Feign ROUTE 引用: {} -> {}", refName, node.getId());
                return node.getId();
            }
        }

        logger.warn("[OpenFeignResolver] 未解析到 Feign 引用: {}", refName);
        return null;
    }

    private int getLineNumber(String content, int position) {
        int line = 1;
        for (int i = 0; i < position && i < content.length(); i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }
}
