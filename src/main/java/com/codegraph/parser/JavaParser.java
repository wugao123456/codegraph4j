package com.codegraph.parser;

import com.codegraph.core.Node;
import com.codegraph.core.types.Language;
import com.codegraph.core.types.NodeKind;
import com.codegraph.core.types.Visibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java 代码解析器
 * 使用正则表达式提取基本符号信息
 */
public class JavaParser implements CodeParser {
    
    private static final Logger logger = LoggerFactory.getLogger(JavaParser.class);
    
    // 类定义正则
    private static final Pattern CLASS_PATTERN = Pattern.compile(
        "(?:public|private|protected)?\\s*(?:abstract|final)?\\s*class\\s+(\\w+)(?:\\s+extends\\s+(\\w+))?(?:\\s+implements\\s+([\\w,\\s]+))?"
    );
    
    // 接口定义正则
    private static final Pattern INTERFACE_PATTERN = Pattern.compile(
        "(?:public|private|protected)?\\s*interface\\s+(\\w+)(?:\\s+extends\\s+([\\w,\\s]+))?"
    );
    
    // 方法定义正则
    private static final Pattern METHOD_PATTERN = Pattern.compile(
        "(?:public|private|protected)?\\s*(?:static|abstract|final)?\\s*(?:\\w+(?:<[^>]+>)?)\\s+(\\w+)\\s*\\(([^)]*)\\)"
    );
    
    // 字段定义正则
    private static final Pattern FIELD_PATTERN = Pattern.compile(
        "(?:public|private|protected)?\\s*(?:static|final)?\\s*(?:\\w+(?:<[^>]+>)?)\\s+(\\w+)\\s*(?:=|;)"
    );
    
    @Override
    public Language getLanguage() {
        return Language.JAVA;
    }
    
    @Override
    public boolean supports(Path filePath) {
        return filePath.toString().endsWith(".java");
    }
    
    @Override
    public List<Node> parse(Path filePath, String content) throws IOException {
        logger.debug("[JavaParser] 开始解析文件: {}", filePath.getFileName());
        List<Node> nodes = new ArrayList<>();
        String[] lines = content.split("\n");
        
        // 解析类
        int classCount = parseClasses(filePath, content, lines, nodes);
        logger.debug("[JavaParser] 解析到 {} 个类", classCount);
        
        // 解析接口
        int interfaceCount = parseInterfaces(filePath, content, lines, nodes);
        logger.debug("[JavaParser] 解析到 {} 个接口", interfaceCount);
        
        // 解析方法
        int methodCount = parseMethods(filePath, content, lines, nodes);
        logger.debug("[JavaParser] 解析到 {} 个方法", methodCount);
        
        // 解析字段
        int fieldCount = parseFields(filePath, content, lines, nodes);
        logger.debug("[JavaParser] 解析到 {} 个字段", fieldCount);
        
        logger.debug("[JavaParser] 文件解析完成: {}, 共 {} 个符号", 
            filePath.getFileName(), nodes.size());
        
        return nodes;
    }
    
    private int parseClasses(Path filePath, String content, String[] lines, List<Node> nodes) {
        Matcher matcher = CLASS_PATTERN.matcher(content);
        int count = 0;
        while (matcher.find()) {
            count++;
            String className = matcher.group(1);
            int lineNum = findLineNumber(content, matcher.start());
            
            Node node = new Node();
            node.setId(generateId());
            node.setKind(NodeKind.CLASS);
            node.setName(className);
            node.setQualifiedName(getPackageName(content) + "." + className);
            node.setFilePath(filePath.toString());
            node.setLanguage(Language.JAVA);
            node.setStartLine(lineNum);
            node.setEndLine(findBlockEnd(lines, lineNum - 1));
            node.setStartColumn(0);
            node.setEndColumn(0);
            node.setVisibility(extractVisibility(getSafeSubstring(content, matcher.start(), 50)));
            node.setExported(true);
            
            logger.trace("[JavaParser] 发现类: {} @ 行 {}", className, lineNum);
            nodes.add(node);
        }
        return count;
    }
    
    private int parseInterfaces(Path filePath, String content, String[] lines, List<Node> nodes) {
        Matcher matcher = INTERFACE_PATTERN.matcher(content);
        int count = 0;
        while (matcher.find()) {
            count++;
            String interfaceName = matcher.group(1);
            int lineNum = findLineNumber(content, matcher.start());
            
            Node node = new Node();
            node.setId(generateId());
            node.setKind(NodeKind.INTERFACE);
            node.setName(interfaceName);
            node.setQualifiedName(getPackageName(content) + "." + interfaceName);
            node.setFilePath(filePath.toString());
            node.setLanguage(Language.JAVA);
            node.setStartLine(lineNum);
            node.setEndLine(findBlockEnd(lines, lineNum - 1));
            node.setStartColumn(0);
            node.setEndColumn(0);
            node.setVisibility(extractVisibility(getSafeSubstring(content, matcher.start(), 50)));
            node.setExported(true);
            
            logger.trace("[JavaParser] 发现接口: {} @ 行 {}", interfaceName, lineNum);
            nodes.add(node);
        }
        return count;
    }
    
    private int parseMethods(Path filePath, String content, String[] lines, List<Node> nodes) {
        Matcher matcher = METHOD_PATTERN.matcher(content);
        int count = 0;
        while (matcher.find()) {
            count++;
            String methodName = matcher.group(1);
            String params = matcher.group(2);
            int lineNum = findLineNumber(content, matcher.start());
            
            // 过滤构造方法（方法名与类名相同）
            boolean isConstructor = nodes.stream()
                .filter(n -> n.getKind() == NodeKind.CLASS)
                .anyMatch(n -> n.getName().equals(methodName));
            
            Node node = new Node();
            node.setId(generateId());
            node.setKind(isConstructor ? NodeKind.METHOD : NodeKind.METHOD);
            node.setName(methodName);
            node.setQualifiedName(getPackageName(content) + "." + methodName);
            node.setFilePath(filePath.toString());
            node.setLanguage(Language.JAVA);
            node.setStartLine(lineNum);
            node.setEndLine(findMethodEnd(lines, lineNum - 1));
            node.setStartColumn(0);
            node.setEndColumn(0);
            node.setSignature(params.isEmpty() ? "()" : "(" + params + ")");
            node.setVisibility(extractVisibility(getSafeSubstring(content, matcher.start(), 50)));
            
            logger.trace("[JavaParser] 发现方法: {} @ 行 {}", methodName, lineNum);
            nodes.add(node);
        }
        return count;
    }
    
    private int parseFields(Path filePath, String content, String[] lines, List<Node> nodes) {
        Matcher matcher = FIELD_PATTERN.matcher(content);
        int count = 0;
        while (matcher.find()) {
            count++;
            String fieldName = matcher.group(1);
            int lineNum = findLineNumber(content, matcher.start());
            
            Node node = new Node();
            node.setId(generateId());
            node.setKind(NodeKind.FIELD);
            node.setName(fieldName);
            node.setQualifiedName(getPackageName(content) + "." + fieldName);
            node.setFilePath(filePath.toString());
            node.setLanguage(Language.JAVA);
            node.setStartLine(lineNum);
            node.setEndLine(lineNum);
            node.setStartColumn(0);
            node.setEndColumn(0);
            node.setVisibility(extractVisibility(getSafeSubstring(content, matcher.start(), 50)));
            
            logger.trace("[JavaParser] 发现字段: {} @ 行 {}", fieldName, lineNum);
            nodes.add(node);
        }
        return count;
    }
    
    private String getPackageName(String content) {
        Pattern packagePattern = Pattern.compile("package\\s+(\\w+(?:\\.\\w+)*);");
        Matcher matcher = packagePattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }
    
    private int findLineNumber(String content, int position) {
        int line = 1;
        for (int i = 0; i < position && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
    
    private int findBlockEnd(String[] lines, int startLine) {
        int braceCount = 0;
        boolean foundOpen = false;
        for (int i = startLine; i < lines.length; i++) {
            String line = lines[i];
            for (char c : line.toCharArray()) {
                if (c == '{') {
                    braceCount++;
                    foundOpen = true;
                } else if (c == '}') {
                    braceCount--;
                    if (foundOpen && braceCount == 0) {
                        return i + 1;
                    }
                }
            }
        }
        return lines.length;
    }
    
    private int findMethodEnd(String[] lines, int startLine) {
        String line = lines[startLine];
        // 如果方法体在同一行（单行方法）
        if (line.contains("{") && line.contains("}")) {
            return startLine + 1;
        }
        return findBlockEnd(lines, startLine);
    }
    
    private Visibility extractVisibility(String declaration) {
        if (declaration.contains("public")) {
            return Visibility.PUBLIC;
        } else if (declaration.contains("private")) {
            return Visibility.PRIVATE;
        } else if (declaration.contains("protected")) {
            return Visibility.PROTECTED;
        }
        return Visibility.INTERNAL;
    }
    
    private String generateId() {
        return UUID.randomUUID().toString();
    }
    
    private String getSafeSubstring(String content, int start, int length) {
        int end = Math.min(start + length, content.length());
        if (start >= content.length()) {
            return "";
        }
        return content.substring(start, end);
    }
}