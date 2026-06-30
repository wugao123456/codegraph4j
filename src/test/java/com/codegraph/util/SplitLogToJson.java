package com.codegraph.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SplitLogToJson {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Pattern LOG_HEADER_PATTERN = Pattern.compile(
        "^(\\S+) - (\\S*) \\[([^\\]]+)\\] \"([^\"]+)\" (\\d+) (\\d+) \"([^\"]*)\" \"([^\"]*)\" \"([^\"]*)\""
    );

    public static boolean splitLogToJson(String logFilePath, String outputDir) {
        Path logPath = Paths.get(logFilePath);
        
        if (!Files.exists(logPath)) {
            System.err.println("❌ 错误: 文件不存在 - " + logFilePath);
            return false;
        }

        if (outputDir == null || outputDir.isEmpty()) {
            outputDir = logPath.getParent().toAbsolutePath().toString();
        }

        Path outputPath = Paths.get(outputDir);
        try {
            Files.createDirectories(outputPath);
        } catch (IOException e) {
            System.err.println("❌ 创建输出目录失败: " + e.getMessage());
            return false;
        }

        System.out.println("📖 读取日志文件: " + logFilePath);

        String logContent;
        try {
            logContent = new String(Files.readAllBytes(logPath), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            System.err.println("❌ 读取文件失败: " + e.getMessage());
            return false;
        }

        if (logContent.isEmpty()) {
            System.err.println("❌ 错误: 文件为空");
            return false;
        }

        System.out.println("🔍 解析日志内容...");

        String[] logLines = logContent.split("\n");
        List<Result> allResults = new ArrayList<>();

        for (int lineIdx = 0; lineIdx < logLines.length; lineIdx++) {
            String singleLogLine = logLines[lineIdx].trim();
            if (singleLogLine.isEmpty()) continue;

            int currentLine = lineIdx + 1;
            System.out.println("\n📝 处理第 " + currentLine + " 条日志...");

            int startIdx = singleLogLine.indexOf("request_body:");
            if (startIdx == -1) {
                System.out.println("⚠ 第 " + currentLine + " 条: 未找到 request_body，跳过");
                continue;
            }

            startIdx += "request_body:".length();
            String bodyPart = singleLogLine.substring(startIdx);

            int lastBrace = bodyPart.lastIndexOf('}');
            String requestBodyStr;
            if (lastBrace != -1) {
                requestBodyStr = bodyPart.substring(0, lastBrace + 1).replaceAll("^\"|\"$", "");
            } else {
                requestBodyStr = bodyPart.replaceAll("^\"|\"$", "");
            }

            JsonNode fullBody;
            try {
                fullBody = objectMapper.readTree(requestBodyStr);
            } catch (Exception e) {
                System.out.println("❌ 第 " + currentLine + " 条 JSON 解析失败: " + e.getMessage());
                System.out.println("   尝试修复 JSON 字符串...");
                
                int firstBrace = requestBodyStr.indexOf('{');
                lastBrace = requestBodyStr.lastIndexOf('}');
                if (firstBrace != -1 && lastBrace > firstBrace) {
                    String fixedJson = requestBodyStr.substring(firstBrace, lastBrace + 1);
                    try {
                        fullBody = objectMapper.readTree(fixedJson);
                        System.out.println("✓ 第 " + currentLine + " 条 JSON 修复成功");
                    } catch (Exception e2) {
                        System.out.println("❌ 第 " + currentLine + " 条 JSON 修复失败，跳过");
                        continue;
                    }
                } else {
                    System.out.println("❌ 第 " + currentLine + " 条 JSON 无法修复，跳过");
                    continue;
                }
            }

            allResults.add(new Result(currentLine, fullBody, singleLogLine));
        }

        if (allResults.isEmpty()) {
            System.err.println("❌ 错误: 没有成功解析任何日志条目");
            return false;
        }

        System.out.println("\n✅ 成功解析 " + allResults.size() + " 条日志");

        for (Result result : allResults) {
            int lineIdx = result.lineIdx;
            JsonNode fullBody = result.fullBody;
            String singleLogLine = result.logLine;

            Path lineOutputDir = outputPath.resolve("log_" + lineIdx);
            try {
                Files.createDirectories(lineOutputDir);
            } catch (IOException e) {
                System.err.println("❌ 创建目录失败: " + lineOutputDir);
                continue;
            }

            System.out.println("\n" + repeat("=", 60));
            System.out.println("处理第 " + lineIdx + " 条日志");
            System.out.println(repeat("=", 60));

            try {
                Path outputFile = lineOutputDir.resolve("01-full-request-body.json");
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), fullBody);
                System.out.println("✓ 完整请求体已保存: " + outputFile);

                ObjectNode config = objectMapper.createObjectNode();
                config.put("model", safeText(fullBody.get("model")));
                config.put("max_completion_tokens", safeInt(fullBody.get("max_completion_tokens")));
                config.put("stream", safeBoolean(fullBody.get("stream")));
                config.set("tools", fullBody.has("tools") ? fullBody.get("tools") : objectMapper.createArrayNode());
                
                outputFile = lineOutputDir.resolve("02-config.json");
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), config);
                System.out.println("✓ 配置部分已保存: " + outputFile);

                JsonNode messages = fullBody.has("messages") ? fullBody.get("messages") : objectMapper.createArrayNode();
                outputFile = lineOutputDir.resolve("03-all-messages.json");
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), messages);
                System.out.println("✓ 所有消息已保存（共" + messages.size() + "条）: " + outputFile);

                ArrayNode systemMsgs = objectMapper.createArrayNode();
                ArrayNode userMsgs = objectMapper.createArrayNode();
                ArrayNode assistantMsgs = objectMapper.createArrayNode();
                ArrayNode toolMsgs = objectMapper.createArrayNode();

                if (messages.isArray()) {
                    for (JsonNode msg : messages) {
                        String role = safeText(msg.get("role"));
                        if ("system".equals(role)) {
                            systemMsgs.add(msg);
                        } else if ("user".equals(role)) {
                            userMsgs.add(msg);
                        } else if ("assistant".equals(role)) {
                            assistantMsgs.add(msg);
                        } else if ("tool".equals(role)) {
                            toolMsgs.add(msg);
                        }
                    }
                }

                if (systemMsgs.size() > 0) {
                    outputFile = lineOutputDir.resolve("04-system-messages.json");
                    objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), systemMsgs);
                    System.out.println("✓ 系统消息已保存（共" + systemMsgs.size() + "条）: " + outputFile);
                }

                if (userMsgs.size() > 0) {
                    outputFile = lineOutputDir.resolve("05-user-messages.json");
                    objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), userMsgs);
                    System.out.println("✓ 用户消息已保存（共" + userMsgs.size() + "条）: " + outputFile);
                }

                if (assistantMsgs.size() > 0) {
                    outputFile = lineOutputDir.resolve("06-assistant-messages.json");
                    objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), assistantMsgs);
                    System.out.println("✓ 助手消息已保存（共" + assistantMsgs.size() + "条）: " + outputFile);
                }

                if (toolMsgs.size() > 0) {
                    outputFile = lineOutputDir.resolve("07-tool-messages.json");
                    objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), toolMsgs);
                    System.out.println("✓ 工具消息已保存（共" + toolMsgs.size() + "条）: " + outputFile);
                }

                Matcher matcher = LOG_HEADER_PATTERN.matcher(singleLogLine);
                if (matcher.find()) {
                    ObjectNode logHeader = objectMapper.createObjectNode();
                    logHeader.put("remote_addr", matcher.group(1));
                    logHeader.put("remote_user", matcher.group(2) != null && !matcher.group(2).isEmpty() ? matcher.group(2) : "-");
                    logHeader.put("time_local", matcher.group(3));
                    logHeader.put("request", matcher.group(4));
                    logHeader.put("status", Integer.parseInt(matcher.group(5)));
                    logHeader.put("body_bytes_sent", Integer.parseInt(matcher.group(6)));
                    logHeader.put("http_referer", matcher.group(7));
                    logHeader.put("http_user_agent", matcher.group(8));
                    logHeader.put("http_x_forwarded_for", matcher.group(9));

                    outputFile = lineOutputDir.resolve("08-log-header.json");
                    objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), logHeader);
                    System.out.println("✓ 日志头部信息已保存: " + outputFile);
                } else {
                    System.out.println("⚠ 无法解析日志头部信息");
                }

            } catch (IOException e) {
                System.err.println("❌ 写入文件失败: " + e.getMessage());
            }
        }

        System.out.println("\n" + repeat("=", 60));
        System.out.println("✅ 所有 JSON 文件已成功生成！");
        System.out.println("📁 输出目录: " + outputPath.toAbsolutePath());
        System.out.println("📊 共处理 " + allResults.size() + " 条日志，每条日志保存在独立子目录中");

        return true;
    }

    private static String safeText(JsonNode node) {
        return node != null && !node.isNull() ? node.asText() : null;
    }

    private static int safeInt(JsonNode node) {
        return node != null && !node.isNull() ? node.asInt() : 0;
    }

    private static boolean safeBoolean(JsonNode node) {
        return node != null && !node.isNull() && node.asBoolean();
    }

    private static String repeat(String str, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(str);
        }
        return sb.toString();
    }

    private static class Result {
        int lineIdx;
        JsonNode fullBody;
        String logLine;

        Result(int lineIdx, JsonNode fullBody, String logLine) {
            this.lineIdx = lineIdx;
            this.fullBody = fullBody;
            this.logLine = logLine;
        }
    }

    public static void main(String[] args) {
        String logFile = "/Users/wugao-pc/Desktop/opt/nginx/atlas-web-nginx/logs/AI_chat_completions.log";
        String outputDir = "/Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/.codegraph/logs";

        System.out.println(repeat("=", 60));
        System.out.println("AI Chat Completions 日志拆分工具");
        System.out.println(repeat("=", 60));
        System.out.println();

        System.out.println("输入文件: " + logFile);
        System.out.println("输出目录: " + outputDir);
        System.out.println();

        boolean success = splitLogToJson(logFile, outputDir);

        if (!success) {
            System.exit(1);
        }
    }
}