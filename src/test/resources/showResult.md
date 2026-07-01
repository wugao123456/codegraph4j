# CodeGraph Explore Result

## Metadata

| Field | Value |
|-------|-------|
| **Tool** | codegraph_explore |
| **Query** | IndexCommand codegraph_explore 索引流程的完整调用链和核心步骤 |
| **Timestamp** | 2026-07-01 12:47:28 |
| **Status** | ✅ Success |

## Content

**Exploration: IndexCommand codegraph_explore 索引流程的完整调用链和核心步骤**

Found 3 symbols across 2 files.

**Source Code**

> The code below is the **verbatim, current on-disk source** of these files — re-read from disk on this call and line-numbered, byte-for-byte identical to what the Read tool returns.

**MCPToolHandler.java** — clustered — `/Users/wugao-pc/Desktop/Project/knowGraph/codegraph4j/src/main/java/com/codegraph/mcp/MCPToolHandler.java`

```java
397	
398	    private ToolDefinition exploreTool() {
399	        Map<String, Object> schema = new LinkedHashMap<>();
400	        schema.put("type", "object");
401	        Map<String, Object> props = new LinkedHashMap<>();
402	        props.put("query", prop("string", "Natural language question, symbol name, or file path to explore"));
403	        props.put("maxFiles", propWithDefault("integer", "Max files to include", 12));
404	        props.put("projectPath", prop("string", "Project root directory (optional)"));
405	        schema.put("properties", props);
406	        schema.put("required", Arrays.asList("query"));
407	        return new ToolDefinition("codegraph_explore",
408	            "Primary tool. Ask natural language questions or explore symbols/files. " +
409	            "Returns grouped source code with line numbers, call paths, and impact radius.",
410	            schema);

```


---

## Stats

- Content items: 1
- Total characters: 1356
