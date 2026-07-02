package com.codegraph.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import com.codegraph.mcp.MCPTransport.ContentItem;
import com.codegraph.mcp.MCPTransport.ToolCallResult;

public class MarkdownUtils {
    

    
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /**
     * 将 ToolCallResult 转换为可读的 Markdown 格式文档。
     *
     * @param result     工具执行结果
     * @param toolName   工具名称
     * @param arguments  工具参数
     * @return Markdown 格式字符串
     */
    public static String resultToMarkdown(ToolCallResult result, String toolName, String arguments) {
        StringBuilder sb = new StringBuilder();

        // 标题
        sb.append("# CodeGraph Explore Result\n\n");

        // 元信息
        sb.append("## Metadata\n\n");
        sb.append("| Field | Value |\n");
        sb.append("|-------|-------|\n");
        sb.append("| **Tool** | ").append(toolName).append(" |\n");
        sb.append("| **Query** | ").append(arguments).append(" |\n");
        sb.append("| **Timestamp** | ").append(LocalDateTime.now().format(FORMATTER)).append(" |\n");
        sb.append("| **Status** | ").append(result.isError ? "❌ Error" : "✅ Success").append(" |\n");
        sb.append("\n");

        // 内容
        sb.append("## Content\n\n");
        if (result.content == null || result.content.isEmpty()) {
            sb.append("*No content returned.*\n");
        } else {
            for (ContentItem item : result.content) {
                if ("error".equals(item.type)) {
                    sb.append("### ❌ Error Message\n\n");
                    sb.append("```\n");
                    sb.append(item.text).append("\n");
                    sb.append("```\n\n");
                } else {
                    sb.append(item.text).append("\n");
                }
            }
        }

        // 统计信息
        int contentItems = result.content != null ? result.content.size() : 0;
        int totalChars = 0;
        if (result.content != null) {
            for (ContentItem item : result.content) {
                if (item.text != null) {
                    totalChars += item.text.length();
                }
            }
        }
        sb.append("---\n\n");
        sb.append("## Stats\n\n");
        sb.append("- Content items: ").append(contentItems).append("\n");
        sb.append("- Total characters: ").append(totalChars).append("\n");

        return sb.toString();
    }

    /**
     * 将 Markdown 字符串写入指定文件。
     *
     * @param resultText     工具执行结果
     * @param toolName   工具名称
     * @param arguments  工具参数
     * @param projectPath  项目路径
     */
    public static void writeMarkdownToFile(ToolCallResult result, String toolName, 
        String arguments, String projectPath) {
        // 需要先解析 resultText 为 ToolCallResult 对象，或者修改方法签名
        // 这里假设 resultText 是 JSON 字符串，需要解析
        
        String markdown = resultToMarkdown(result, toolName, arguments);
        try {
            Path path = Paths.get(projectPath,".codegraph/logs/explore_"+ arguments+".md");
            Files.write(path, markdown.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("Failed to write markdown file: " + e.getMessage());
            e.printStackTrace();
        }
    }

}
