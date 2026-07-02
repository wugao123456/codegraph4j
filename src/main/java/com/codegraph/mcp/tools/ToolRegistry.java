package com.codegraph.mcp.tools;

import com.codegraph.mcp.MCPTransport.ToolDefinition;

import java.util.*;

/**
 * 工具注册表 — 管理所有 Tool 实例，提供注册、查找、列表功能。
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /** 注册工具（按注册顺序保持，可覆盖同名） */
    public void register(Tool tool) {
        tools.put(tool.getName(), tool);
    }

    /** 按名称查找工具，不存在返回 null */
    public Tool get(String name) {
        return tools.get(name);
    }

    /** 获取所有工具的元信息列表（保持注册顺序） */
    public List<ToolDefinition> getDefinitions() {
        List<ToolDefinition> defs = new ArrayList<>();
        for (Tool t : tools.values()) {
            defs.add(t.getDefinition());
        }
        return defs;
    }

    /** 获取所有注册的工具 */
    public List<Tool> getAll() {
        return new ArrayList<>(tools.values());
    }

    /** 获取注册的工具数量 */
    public int size() {
        return tools.size();
    }
}
