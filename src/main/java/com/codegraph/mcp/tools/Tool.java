package com.codegraph.mcp.tools;

import com.codegraph.mcp.MCPTransport.ToolCallResult;
import com.codegraph.mcp.MCPTransport.ToolDefinition;

import java.util.Map;

/**
 * MCP 工具接口 — 每个代码图谱工具都应实现此接口。
 */
public interface Tool {
    /** 工具名称，如 "codegraph_search" */
    String getName();

    /** 工具元信息定义（名称、描述、输入参数 JSON Schema） */
    ToolDefinition getDefinition();

    /** 执行工具，传入参数、返回结果 */
    ToolCallResult execute(Map<String, Object> args);
}
