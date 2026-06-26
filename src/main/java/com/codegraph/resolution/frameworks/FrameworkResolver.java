package com.codegraph.resolution.frameworks;

import com.codegraph.core.Node;
import com.codegraph.core.types.Language;
import com.codegraph.resolution.ResolutionContext;

import java.util.Collections;
import java.util.List;

/**
 * 框架解析器接口。
 * 每个框架实现此接口，提供框架特定的节点提取和引用解析能力。
 *
 * 架构对应 codegraph 项目中的 FrameworkResolver 接口。
 */
public interface FrameworkResolver {

    /**
     * 框架名称（如 "spring", "express", "django"）。
     */
    String getName();

    /**
     * 适用的编程语言。返回 null 表示适用所有语言。
     */
    List<Language> getLanguages();

    /**
     * 检测项目是否使用了此框架（项目级，启动时调用一次）。
     *
     * @param context 解析上下文
     * @return true 表示检测到框架
     */
    boolean detect(ResolutionContext context);

    /**
     * 从文件中提取框架特有的节点和引用（如路由、配置键、中间件等）。
     * 在 AST 提取完成后调用。
     *
     * @param filePath 文件路径
     * @param content  文件内容
     * @param context  解析上下文
     * @return 提取结果，包含框架特有节点和未解析引用
     */
    FrameworkExtractionResult extract(String filePath, String content, ResolutionContext context);

    /**
     * 使用框架特有模式解析引用。
     * 当标准解析器（精确匹配、导入解析、限定名匹配）未能解析时，调用此方法。
     *
     * @param refName     引用名称
     * @param refKind     引用类型
     * @param sourceFile  源文件路径
     * @param context     解析上下文
     * @return 目标节点 ID，未解析返回 null
     */
    String resolve(String refName, String refKind, String sourceFile, ResolutionContext context);

    /**
     * 可选：声明引用名称存在（即使图中没有同名节点），绕过名称预过滤。
     * 例如 Spring 的 :prefix 后缀引用、动态属性等。
     */
    default boolean claimsReference(String name) {
        return false;
    }

    /**
     * 可选：所有文件提取完成后的跨文件后处理。
     *
     * @param context 解析上下文
     * @return 额外的节点（如跨文件的合成节点）
     */
    default List<Node> postExtract(ResolutionContext context) {
        return Collections.emptyList();
    }
}
