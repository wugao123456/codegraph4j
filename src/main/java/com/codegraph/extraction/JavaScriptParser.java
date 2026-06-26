package com.codegraph.extraction;

import com.codegraph.core.Node;
import com.codegraph.core.types.Language;
import com.codegraph.core.types.NodeKind;
import com.codegraph.core.types.Visibility;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaScript 代码解析器
 * 使用正则表达式提取基本符号信息
 */
public class JavaScriptParser implements CodeParser {
    
    // 类定义正则
    private static final Pattern CLASS_PATTERN = Pattern.compile(
        "class\\s+(\\w+)(?:\\s+extends\\s+(\\w+))?"
    );
    
    // 函数定义正则
    private static final Pattern FUNCTION_PATTERN = Pattern.compile(
        "(?:async\\s+)?(?:function\\s+(\\w+)|(?:const|let|var)\\s+(\\w+)\\s*=\\s*(?:async\\s+)?function|(?:\\w+)\\s*:\\s*(?:async\\s+)?function)"
    );
    
    // 箭头函数正则
    private static final Pattern ARROW_FUNCTION_PATTERN = Pattern.compile(
        "(?:const|let|var)\\s+(\\w+)\\s*=\\s*(?:async\\s+)?(?:\\([^)]*\\)|[^=])\\s*=>"
    );
    
    // 方法定义正则
    private static final Pattern METHOD_PATTERN = Pattern.compile(
        "(?:async\\s+)?(\\w+)\\s*\\([^)]*\\)\\s*\\{"
    );
    
    // 模块导出正则
    private static final Pattern EXPORT_PATTERN = Pattern.compile(
        "(?:export\\s+(?:default\\s+)?(?:class|function|const|let|var)\\s+(\\w+)|module\\.exports\\s*=)"
    );
    
    @Override
    public Language getLanguage() {
        return Language.JAVASCRIPT;
    }
    
    @Override
    public boolean supports(Path filePath) {
        String path = filePath.toString().toLowerCase();
        return path.endsWith(".js") || path.endsWith(".jsx") || path.endsWith(".mjs");
    }
    
    @Override
    public List<Node> parse(Path filePath, String content) throws IOException {
        List<Node> nodes = new ArrayList<>();
        String[] lines = content.split("\n");
        
        // 解析类
        parseClasses(filePath, content, lines, nodes);
        
        // 解析函数
        parseFunctions(filePath, content, lines, nodes);
        
        // 解析箭头函数
        parseArrowFunctions(filePath, content, lines, nodes);
        
        // 解析方法
        parseMethods(filePath, content, lines, nodes);
        
        return nodes;
    }
    
    private void parseClasses(Path filePath, String content, String[] lines, List<Node> nodes) {
        Matcher matcher = CLASS_PATTERN.matcher(content);
        while (matcher.find()) {
            String className = matcher.group(1);
            int lineNum = findLineNumber(content, matcher.start());
            
            Node node = new Node();
            node.setId(generateId());
            node.setKind(NodeKind.CLASS);
            node.setName(className);
            node.setQualifiedName(className);
            node.setFilePath(filePath.toString());
            node.setLanguage(Language.JAVASCRIPT);
            node.setStartLine(lineNum);
            node.setEndLine(findBlockEnd(lines, lineNum - 1));
            node.setStartColumn(0);
            node.setEndColumn(0);
            node.setVisibility(Visibility.PUBLIC);
            node.setExported(isExported(content, className));
            
            nodes.add(node);
        }
    }
    
    private void parseFunctions(Path filePath, String content, String[] lines, List<Node> nodes) {
        Matcher matcher = FUNCTION_PATTERN.matcher(content);
        while (matcher.find()) {
            String funcName = matcher.group(1);
            if (funcName == null) {
                funcName = matcher.group(2);
            }
            if (funcName == null) {
                continue;
            }
            
            int lineNum = findLineNumber(content, matcher.start());
            
            Node node = new Node();
            node.setId(generateId());
            node.setKind(NodeKind.FUNCTION);
            node.setName(funcName);
            node.setQualifiedName(funcName);
            node.setFilePath(filePath.toString());
            node.setLanguage(Language.JAVASCRIPT);
            node.setStartLine(lineNum);
            node.setEndLine(findBlockEnd(lines, lineNum - 1));
            node.setStartColumn(0);
            node.setEndColumn(0);
            node.setVisibility(Visibility.PUBLIC);
            node.setExported(isExported(content, funcName));
            
            nodes.add(node);
        }
    }
    
    private void parseArrowFunctions(Path filePath, String content, String[] lines, List<Node> nodes) {
        Matcher matcher = ARROW_FUNCTION_PATTERN.matcher(content);
        while (matcher.find()) {
            String funcName = matcher.group(1);
            int lineNum = findLineNumber(content, matcher.start());
            
            Node node = new Node();
            node.setId(generateId());
            node.setKind(NodeKind.FUNCTION);
            node.setName(funcName);
            node.setQualifiedName(funcName);
            node.setFilePath(filePath.toString());
            node.setLanguage(Language.JAVASCRIPT);
            node.setStartLine(lineNum);
            node.setEndLine(findArrowFunctionEnd(lines, lineNum - 1));
            node.setStartColumn(0);
            node.setEndColumn(0);
            node.setVisibility(Visibility.PUBLIC);
            node.setExported(isExported(content, funcName));
            
            nodes.add(node);
        }
    }
    
    private void parseMethods(Path filePath, String content, String[] lines, List<Node> nodes) {
        Matcher matcher = METHOD_PATTERN.matcher(content);
        while (matcher.find()) {
            String methodName = matcher.group(1);
            
            // 过滤已知的非方法关键字
            if (methodName.equals("if") || methodName.equals("for") || 
                methodName.equals("while") || methodName.equals("switch") ||
                methodName.equals("catch") || methodName.equals("class") ||
                methodName.equals("function") || methodName.equals("constructor")) {
                continue;
            }
            
            int lineNum = findLineNumber(content, matcher.start());
            
            Node node = new Node();
            node.setId(generateId());
            node.setKind(NodeKind.METHOD);
            node.setName(methodName);
            node.setQualifiedName(methodName);
            node.setFilePath(filePath.toString());
            node.setLanguage(Language.JAVASCRIPT);
            node.setStartLine(lineNum);
            node.setEndLine(findBlockEnd(lines, lineNum - 1));
            node.setStartColumn(0);
            node.setEndColumn(0);
            node.setVisibility(Visibility.PUBLIC);
            
            nodes.add(node);
        }
    }
    
    private boolean isExported(String content, String name) {
        // 检查是否有 export 或 module.exports
        Pattern exportPattern = Pattern.compile(
            "export\\s+(?:default\\s+)?(?:class|function|const|let|var)\\s+" + name + 
            "|module\\.exports.*" + name
        );
        return exportPattern.matcher(content).find();
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
    
    private int findArrowFunctionEnd(String[] lines, int startLine) {
        String line = lines[startLine];
        // 如果是单行箭头函数
        if (!line.trim().endsWith("{")) {
            return startLine + 1;
        }
        return findBlockEnd(lines, startLine);
    }
    
    private String generateId() {
        return UUID.randomUUID().toString();
    }
}