package com.codegraph.extraction;

import com.codegraph.core.Node;
import com.codegraph.core.types.Language;
import com.codegraph.extraction.languages.JavaExtractor;
import com.codegraph.extraction.tree_sitter.TreeSitterExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 基于 tree-sitter + JNA 的 Java 代码解析器。
 * 替代旧的正则表达式 JavaParser。
 */
public class TreeSitterCodeParser implements CodeParser {

    private static final Logger logger = LoggerFactory.getLogger(TreeSitterCodeParser.class);

    private final TreeSitterExtractor extractor;
    private final JavaExtractor javaExtractor;

    public TreeSitterCodeParser() {
        this.extractor = new TreeSitterExtractor();
        this.javaExtractor = new JavaExtractor();
    }

    @Override
    public Language getLanguage() {
        return Language.JAVA;
    }

    @Override
    public boolean supports(Path filePath) {
        String name = filePath.getFileName().toString().toLowerCase();
        return name.endsWith(".java");
    }

    @Override
    public List<Node> parse(Path filePath, String content) throws IOException {
        // 委托给 parseWithEdges，只返回节点
        ParseResult result = parseWithEdges(filePath, content);
        return result.getNodes();
    }

    @Override
    public ParseResult parseWithEdges(Path filePath, String content) throws IOException {
        logger.debug("[TreeSitter] 开始解析: {}", filePath.getFileName());
        long startTime = System.currentTimeMillis();

        ParseResult result = extractor.extract(filePath, content, javaExtractor);

        long elapsed = System.currentTimeMillis() - startTime;
        logger.debug("[TreeSitter] 解析完成: {} ({}) — {} nodes, {} edges — {}ms",
            filePath.getFileName(), filePath,
            result.getNodeCount(), result.getEdgeCount(), elapsed);

        return result;
    }
}
