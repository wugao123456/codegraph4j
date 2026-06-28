package com.codegraph.resolution.frameworks;

import com.codegraph.core.types.Language;
import com.codegraph.resolution.ResolutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 框架解析器注册中心。
 * 管理所有 FrameworkResolver 实例，提供框架检测和应用能力。
 *
 * 对应 codegraph 项目中的 frameworks/index.ts。
 */
public class FrameworkRegistry {

    private static final Logger logger = LoggerFactory.getLogger(FrameworkRegistry.class);

    private final Map<String, FrameworkResolver> resolvers = new LinkedHashMap<>();

    /**
     * 注册一个框架解析器。
     */
    public void register(FrameworkResolver resolver) {
        resolvers.put(resolver.getName(), resolver);
        logger.debug("[FrameworkRegistry] 注册框架: {}", resolver.getName());
    }

    /**
     * 获取指定名称的框架解析器。
     */
    public FrameworkResolver get(String name) {
        return resolvers.get(name);
    }

    /**
     * 获取所有注册的框架解析器。
     */
    public List<FrameworkResolver> getAll() {
        return new ArrayList<>(resolvers.values());
    }

    /**
     * 检测所有框架并返回匹配的解析器列表。
     *
     * @param context 解析上下文
     * @return 检测到的框架解析器列表
     */
    public List<FrameworkResolver> detectFrameworks(ResolutionContext context) {
        List<FrameworkResolver> detected = new ArrayList<>();
        for (FrameworkResolver resolver : resolvers.values()) {
            try {
                if (resolver.detect(context)) {
                    detected.add(resolver);
                    logger.info("[FrameworkRegistry] 检测到框架: {}", resolver.getName());
                }
            } catch (Exception e) {
                logger.warn("[FrameworkRegistry] 框架 {} 检测失败: {}", resolver.getName(), e.getMessage());
            }
        }
        return detected;
    }

    /**
     * 从检测到的框架中按语言过滤。
     *
     * @param detected  已检测到的框架列表
     * @param language  目标语言
     * @return 适用的框架解析器列表
     */
    public List<FrameworkResolver> getApplicableFrameworks(List<FrameworkResolver> detected, Language language) {
        List<FrameworkResolver> applicable = new ArrayList<>();
        for (FrameworkResolver resolver : detected) {
            List<Language> languages = resolver.getLanguages();
            if (languages == null || languages.isEmpty() || languages.contains(language)) {
                applicable.add(resolver);
            }
        }
        return applicable;
    }

    /**
     * 批量注册一组默认框架解析器。
     */
    public void registerDefaults() {
        // Java/Kotlin 框架
        register(new SpringResolver());

        // 远程调用框架
        register(new OpenFeignResolver());
        register(new DubboResolver());

        // 后续可扩展：PlayResolver、ExpressResolver、DjangoResolver 等
    }
}
