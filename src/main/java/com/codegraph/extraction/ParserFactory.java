package com.codegraph.extraction;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 解析器工厂
 * 根据文件类型选择合适的解析器
 */
public class ParserFactory {
    
    private final List<CodeParser> parsers;
    
    public ParserFactory() {
        this.parsers = new ArrayList<>();
        this.parsers.add(new TreeSitterCodeParser());
        this.parsers.add(new JavaScriptParser());
    }
    
    /**
     * 获取适合文件的解析器
     * @param filePath 文件路径
     * @return 解析器，如果没有匹配的返回 null
     */
    public CodeParser getParser(Path filePath) {
        for (CodeParser parser : parsers) {
            if (parser.supports(filePath)) {
                return parser;
            }
        }
        return null;
    }
    
    /**
     * 添加自定义解析器
     * @param parser 解析器
     */
    public void addParser(CodeParser parser) {
        parsers.add(parser);
    }
    
    /**
     * 获取所有解析器
     */
    public List<CodeParser> getAllParsers() {
        return parsers;
    }
}