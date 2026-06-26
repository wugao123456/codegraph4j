package com.codegraph.parser;

import com.codegraph.core.Node;
import com.codegraph.core.types.Language;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * 代码解析器接口
 */
public interface CodeParser {
    
    /**
     * 获取支持的语言
     */
    Language getLanguage();
    
    /**
     * 解析文件并提取符号节点
     * @param filePath 文件路径
     * @param content 文件内容
     * @return 解析出的节点列表
     */
    List<Node> parse(Path filePath, String content) throws IOException;
    
    /**
     * 解析文件并提取符号节点和关系边。
     * 默认实现委托给 parse()，返回空的边列表。
     * tree-sitter 解析器应覆盖此方法。
     */
    default ParseResult parseWithEdges(Path filePath, String content) throws IOException {
        List<Node> nodes = parse(filePath, content);
        return new ParseResult(nodes, Collections.emptyList());
    }
    
    /**
     * 检查是否支持该文件
     * @param filePath 文件路径
     */
    boolean supports(Path filePath);
}