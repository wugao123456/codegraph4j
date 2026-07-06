# Trae Agent Tools Guide

## Overview

This document provides a comprehensive guide to all tools available to the Trae IDE agent. These tools enable the agent to perform various software engineering tasks, from code exploration to file manipulation and web searching.

---

## Table of Contents

| Tool Name | Category | Description |
|-----------|----------|-------------|
| [Task](#task) | Subagent | Launch specialized subagents for complex tasks |
| [Skill](#skill) | Skill | Execute specialized skills |
| [SearchCodebase](#searchcodebase) | Search | Semantic code search by meaning |
| [Glob](#glob) | Search | Fast file pattern matching |
| [LS](#ls) | Search | List files and directories |
| [Grep](#grep) | Search | Powerful regex-based search |
| [Read](#read) | File | Read file contents |
| [WebSearch](#websearch) | Web | Search the web for real-time info |
| [WebFetch](#webfetch) | Web | Fetch content from a URL |
| [RunCommand](#runcommand) | Terminal | Execute terminal commands |
| [CheckCommandStatus](#checkcommandstatus) | Terminal | Check status of running commands |
| [StopCommand](#stopcommand) | Terminal | Stop running commands |
| [GetDiagnostics](#getdiagnostics) | Code | Get VS Code language diagnostics |
| [DeleteFile](#deletefile) | File | Delete files |
| [SearchReplace](#searchreplace) | File | Edit files with search/replace |
| [Write](#write) | File | Write files to disk |
| [TodoWrite](#todowrite) | Task | Manage task lists |
| [AskUserQuestion](#askuserquestion) | Interaction | Ask user questions |
| [NotifyUser](#notifyuser) | Interaction | Notify user for review |
| [OpenPreview](#openpreview) | Preview | Open web preview |
| [run_mcp](#run_mcp) | External | Call MCP tools |

---

## Tool Details

### Task

**Description:** Launch a new agent to handle complex, multi-step tasks autonomously.

**Available subagent_types:**
- **search**: Fast agent specialized for exploring codebases
- **general_purpose_task**: Perform general-purpose coding tasks
- **linux-operation-expert**: Manage remote Linux servers

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| description | string | Yes | A short (3-5 words) description of the task |
| query | string | Yes | The task for the agent to perform |
| subagent_type | string | Yes | The type of specialized agent to use |
| response_language | string | Yes | The language to use for the response |

**When to use:**
- Complex multi-step tasks
- Operations that produce a lot of output
- Cross-layer changes (frontend, backend, API)

**When NOT to use:**
- Simple tasks that can be done with direct tool calls
- When the exact output is already fully known
- Sequential tasks where later steps depend on earlier results

---

### Skill

**Description:** Execute a skill within the main conversation. Skills provide specialized capabilities and domain knowledge.

**Available skills:**
- **TRAE-code-review**: Code review tasks
- **TRAE-debugger**: Debug complex problems
- **TRAE-generate-mini-app**: Generate mini-apps based on Taro
- **atlasProject**: Generate project descriptions
- **skill-creator**: Create new skills
- **web-dev**: Create production-grade web interfaces

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| name | string | Yes | The skill name (no arguments) |

**Usage notes:**
- When a skill is relevant, invoke this tool immediately as your first action
- Only use skills listed in available_skills
- Do not invoke a skill that is already running

---

### SearchCodebase

**Description:** Semantic search that finds code by meaning, not exact text. Powered by a retrieval/embedding model suite with a real-time codebase index.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| information_request | string | Yes | A complete natural-language question describing the code you are looking for |
| target_directories | array | No | Specific directories to search within (absolute paths) |

**When to use:**
- Explore unfamiliar codebases
- Find code by intent or behavior
- Locate implementations, patterns, or flows across modules

**When NOT to use:**
- Exact text or symbol matches → use Grep
- Reading known files → use Read
- Finding files by name → use Glob

**Query guidelines:**
- Write complete natural-language questions
- One question per call
- Avoid single keywords (use Grep instead)

---

### Glob

**Description:** Fast file pattern matching tool that works with any codebase size.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| pattern | string | Yes | The glob pattern to match files against |
| path | string | No | The directory to search in (absolute path) |

**Usage:**
- Supports glob patterns like `**/*.js` or `src/**/*.ts`
- Returns matching file paths sorted by modification time

---

### LS

**Description:** Lists files and directories in a given path.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| path | string | Yes | The absolute path to the directory to list |
| ignore | array | No | List of glob patterns to ignore |

**Note:** Generally prefer Glob and Grep tools if you know which directories to search.

---

### Grep

**Description:** A powerful search tool built on ripgrep.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| pattern | string | Yes | The regular expression pattern to search for |
| path | string | No | File or directory to search in |
| glob | string | No | Glob pattern to filter files |
| output_mode | string | No | Output mode: content, files_with_matches (default), count |
| -B | integer | No | Number of lines before each match |
| -A | integer | No | Number of lines after each match |
| -C | integer | No | Number of lines before and after each match |
| -n | boolean | No | Show line numbers in output |
| -i | boolean | No | Case insensitive search |
| type | string | No | File type to search |
| head_limit | integer | No | Limit output to first N lines/entries |
| offset | integer | No | Skip first N lines before applying head_limit |
| multiline | boolean | No | Enable multiline mode |

---

### Read

**Description:** Reads a file from the local filesystem.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| file_path | string | Yes | The absolute path to the file to read |
| offset | integer | No | The line number to start reading from |
| limit | integer | No | The number of lines to read |

**Usage notes:**
- When you know which part of the file you need, only read that part
- Results are returned with line numbers starting at 1

---

### WebSearch

**Description:** Search the web for real-time information about any topic.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| query | string | Yes | The search query to be executed |
| num | integer | No | Maximum number of results (default: 5) |
| lr | string | No | Language restriction (e.g., 'lang_en') |

**CRITICAL REQUIREMENT:** After answering, include a "Sources:" section with all relevant URLs.

---

### WebFetch

**Description:** Fetch content from a specified URL and return its contents in a readable markdown format.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| url | string | Yes | The URL to fetch content from |

**Notes:**
- Returns empty for authenticated/private URLs
- HTTP URLs are automatically upgraded to HTTPS
- Results may be truncated if content is very large

---

### RunCommand

**Description:** Execute a command in a terminal session on behalf of the user.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| cwd | string | No | Working directory (absolute path) |
| command | string | Yes | The terminal command to execute |
| target_terminal | string | No | Target terminal for execution |
| command_type | string | No | Command type: web_server, long_running_process, short_running_process, other |
| blocking | boolean | Yes | Set to false only for web_server or long_running_process |
| wait_ms_before_async | integer | No | Milliseconds to wait before async mode |
| requires_approval | boolean | Yes | Whether user must approve before execution |

**Important notes:**
- DO NOT use for file operations — use specialized tools instead
- Terminals are stateful across sequential calls
- Avoid search commands like `find` and `grep` — use dedicated tools

---

### CheckCommandStatus

**Description:** Get the status of a previously executed non-blocking command.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| command_id | string | No | ID of the command to get status for |
| wait_ms_before_check | integer | No | Milliseconds to wait before checking |
| output_character_count | integer | No | Number of characters to view (default: 2000) |
| skip_character_count | integer | No | Characters to skip from output_priority position |
| output_priority | string | No | Priority: top, bottom (default), split |
| filter | string | No | Regex to filter output lines |

---

### StopCommand

**Description:** Terminate a currently running command.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| command_id | string | Yes | The command id of the running command to terminate |

---

### GetDiagnostics

**Description:** Get language diagnostics from VS Code.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| uri | string | No | Optional file URI to get diagnostics for |

---

### DeleteFile

**Description:** Delete files from the filesystem.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| file_paths | array | Yes | List of file paths to delete (absolute paths) |

---

### SearchReplace

**Description:** Edit files using search/replace operations.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| file_path | string | Yes | The file path (absolute path) |
| old_str | string | Yes | The SEARCH section - contiguous chunk of lines |
| new_str | string | Yes | The REPLACE section - lines to replace |

**Rules:**
1. Only replaces the first match occurrence
2. Include enough lines in SEARCH to uniquely match
3. Keep SEARCH and REPLACE sections concise

---

### Write

**Description:** Writes a file to the local filesystem.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| file_path | string | Yes | The absolute path to the file to write |
| content | string | Yes | The content to write to the file |

**Usage notes:**
- Overwrites existing files
- MUST read existing files first before writing
- Prefer editing over creating new files

---

### TodoWrite

**Description:** Create and manage a structured task list for tracking progress.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| todos | array | Yes | Array of todo items |
| merge | boolean | Yes | Whether to merge with existing todos |
| summary | string | No | Summary of work accomplished |

**Todo item properties:**

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| content | string | Yes | The description of the todo item |
| status | string | Yes | Status: pending, in_progress, completed |
| id | string | Yes | Unique identifier |
| priority | string | Yes | Priority: high, medium, low |

**When to use:** Complex tasks with 3+ distinct steps.

---

### AskUserQuestion

**Description:** Ask the user questions during execution for clarification or decision-making.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| questions | array | Yes | Questions to ask the user (1-4 questions) |

**Question properties:**

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| question | string | Yes | The complete question |
| header | string | Yes | Short label (max 12 chars) |
| options | array | Yes | Available choices (2-4 options) |
| multiSelect | boolean | No | Allow multiple selections |

---

### NotifyUser

**Description:** Notify the user to review output and request approval before proceeding.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| explanation | string | No | Brief explanation of this notice |
| file_paths | array | Yes | Absolute paths of documents to review |

**When to use:**
- Plan mode complete, waiting for user confirmation
- Spec mode complete, waiting for approval
- web-dev skill documents ready for review

---

### OpenPreview

**Description:** Show the available preview URL to the user.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| preview_url | string | Yes | The complete, valid preview URL |
| command_id | string | Yes | The command id that generated the preview |

---

### run_mcp

**Description:** Call an MCP (Model Context Protocol) tool by server identifier and tool name.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| server_name | string | Yes | Identifier of the MCP server |
| tool_name | string | Yes | Name of the MCP tool to invoke |
| args | object | Yes | Arguments to pass to the MCP tool |

**IMPORTANT:**
- Always obtain tool descriptor by calling LS and Read BEFORE calling this tool
- All tool-specific parameters must be passed inside the `args` field
- Only `server_name`, `tool_name`, and `args` are valid top-level fields

---

## Chinese Translation (中文翻译)

## Trae Agent 工具指南

## 概述

本文档提供了 Trae IDE 代理可用的所有工具的全面指南。这些工具使代理能够执行各种软件工程任务，从代码探索到文件操作和网络搜索。

---

## 目录

| 工具名称 | 类别 | 描述 |
|-----------|----------|-------------|
| [Task](#task-1) | 子代理 | 启动专门的子代理处理复杂任务 |
| [Skill](#skill-1) | 技能 | 执行专业技能 |
| [SearchCodebase](#searchcodebase-1) | 搜索 | 基于语义的代码搜索 |
| [Glob](#glob-1) | 搜索 | 快速文件模式匹配 |
| [LS](#ls-1) | 搜索 | 列出文件和目录 |
| [Grep](#grep-1) | 搜索 | 强大的正则表达式搜索 |
| [Read](#read-1) | 文件 | 读取文件内容 |
| [WebSearch](#websearch-1) | 网络 | 搜索网络获取实时信息 |
| [WebFetch](#webfetch-1) | 网络 | 从 URL 获取内容 |
| [RunCommand](#runcommand-1) | 终端 | 执行终端命令 |
| [CheckCommandStatus](#checkcommandstatus-1) | 终端 | 检查运行中命令的状态 |
| [StopCommand](#stopcommand-1) | 终端 | 停止运行中的命令 |
| [GetDiagnostics](#getdiagnostics-1) | 代码 | 获取 VS Code 语言诊断信息 |
| [DeleteFile](#deletefile-1) | 文件 | 删除文件 |
| [SearchReplace](#searchreplace-1) | 文件 | 使用搜索/替换编辑文件 |
| [Write](#write-1) | 文件 | 将文件写入磁盘 |
| [TodoWrite](#todowrite-1) | 任务 | 管理任务列表 |
| [AskUserQuestion](#askuserquestion-1) | 交互 | 向用户提问 |
| [NotifyUser](#notifyuser-1) | 交互 | 通知用户进行审核 |
| [OpenPreview](#openpreview-1) | 预览 | 打开网页预览 |
| [run_mcp](#run_mcp-1) | 外部 | 调用 MCP 工具 |

---

## 工具详情

### Task

**描述:** 启动新代理以自主处理复杂的多步骤任务。

**可用的 subagent_types:**
- **search**: 专门用于探索代码库的快速代理
- **general_purpose_task**: 执行通用编码任务
- **linux-operation-expert**: 管理远程 Linux 服务器

**参数:**

| 参数 | 类型 | 必填 | 描述 |
|-----------|------|----------|-------------|
| description | string | 是 | 任务的简短描述（3-5个词） |
| query | string | 是 | 代理要执行的任务 |
| subagent_type | string | 是 | 要使用的专门代理类型 |
| response_language | string | 是 | 响应使用的语言 |

**使用场景:**
- 复杂的多步骤任务
- 产生大量输出的操作
- 跨层变更（前端、后端、API）

**不适用场景:**
- 可通过直接工具调用完成的简单任务
- 确切输出已完全已知时
- 后续步骤依赖于先前结果的顺序任务

---

### Skill

**描述:** 在主对话中执行技能。技能提供专业能力和领域知识。

**可用技能:**
- **TRAE-code-review**: 代码审查任务
- **TRAE-debugger**: 调试复杂问题
- **TRAE-generate-mini-app**: 基于 Taro 生成小程序
- **atlasProject**: 生成项目描述
- **skill-creator**: 创建新技能
- **web-dev**: 创建生产级网页界面

**参数:**

| 参数 | 类型 | 必填 | 描述 |
|-----------|------|----------|-------------|
| name | string | 是 | 技能名称（无参数） |

**使用注意:**
- 当技能相关时，立即作为第一个操作调用此工具
- 仅使用 available_skills 中列出的技能
- 不要调用已经在运行的技能

---

### SearchCodebase

**描述:** 语义搜索，通过含义而非确切文本查找代码。由检索/嵌入模型套件提供支持，具有实时代码库索引。

**参数:**

| 参数 | 类型 | 必填 | 描述 |
|-----------|------|----------|-------------|
| information_request | string | 是 | 描述你要查找的代码的完整自然语言问题 |
| target_directories | array | 否 | 要搜索的特定目录（绝对路径） |

**使用场景:**
- 探索不熟悉的代码库
- 通过意图或行为查找代码
- 在模块间定位实现、模式或流程

**不适用场景:**
- 精确文本或符号匹配 → 使用 Grep
- 读取已知文件 → 使用 Read
- 按名称查找文件 → 使用 Glob

**查询指南:**
- 编写完整的自然语言问题
- 每次调用一个问题
- 避免单个关键字（改用 Grep）

---

### Glob

**描述:** 快速文件模式匹配工具，适用于任何大小的代码库。

**参数:**

| 参数 | 类型 | 必填 | 描述 |
|-----------|------|----------|-------------|
| pattern | string | 是 | 要匹配的 glob 模式 |
| path | string | 否 | 搜索的目录（绝对路径） |

**用法:**
- 支持 glob 模式如 `**/*.js` 或 `src/**/*.ts`
- 返回按修改时间排序的匹配文件路径

---

### LS

**描述:** 列出指定路径中的文件和目录。

**参数:**

| 参数 | 类型 | 必填 | 描述 |
|-----------|------|----------|-------------|
| path | string | 是 | 要列出的目录的绝对路径 |
| ignore | array | 否 | 要忽略的 glob 模式列表 |

**注意:** 如果你知道要搜索哪些目录，通常首选 Glob 和 Grep 工具。

---

### Grep

**描述:** 基于 ripgrep 的强大搜索工具。

**参数:**

| 参数 | 类型 | 必填 | 描述 |
|-----------|------|----------|-------------|
| pattern | string | 是 | 要搜索的正则表达式模式 |
| path | string | 否 | 要搜索的文件或目录 |
| glob | string | 否 | 用于过滤文件的 glob 模式 |
| output_mode | string | 否 | 输出模式：content、files_with_matches（默认）、count |
| -B | integer | 否 | 每个匹配前的行数 |
| -A | integer | 否 | 每个匹配后的行数 |
| -C | integer | 否 | 每个匹配前后的行数 |
| -n | boolean | 否 | 在输出中显示行号 |
| -i | boolean | 否 | 不区分大小写搜索 |
| type | string | 否 | 要搜索的文件类型 |
| head_limit | integer | 否 | 将输出限制为前 N 行/条目 |
| offset | integer | 否 | 在应用 head_limit 之前跳过前 N 行 |
| multiline | boolean | 否 | 启用多行模式 |

---

### Read

**描述:** 从本地文件系统读取文件。

**参数:**

| 参数 | 类型 | 必填 | 描述 |
|-----------|------|----------|-------------|
| file_path | string | 是 | 要读取的文件的绝对路径 |
| offset | integer | 否 | 开始读取的行号 |
| limit | integer | 否 | 要读取的行数 |

**使用注意:**
- 当你知道需要文件的哪部分时，只读取那部分
- 结果从第 1 行开始带行号返回

---

### WebSearch

**描述:** 搜索网络获取有关任何主题的实时信息。

**参数:**

| 参数 | 类型 | 必填 | 描述 |
|-----------|------|----------|-------------|
| query | string | 是 | 要执行的搜索查询 |
| num | integer | 否 | 最大结果数（默认：5） |
| lr | string | 否 | 语言限制（例如 'lang_en'） |

**关键要求:** 回答后必须包含 "Sources:" 部分，列出所有相关 URL。

---

### WebFetch

**描述:** 从指定 URL 获取内容并以可读的 markdown 格式返回。

**参数:**

| 参数 | 类型 | 必填 | 描述 |
|-----------|------|----------|-------------|
| url | string | 是 | 要获取内容的 URL |

**注意:**
- 对于经过身份验证的/私有 URL 返回空内容
- HTTP URL 会自动升级为 HTTPS
- 如果内容很大，结果可能会被截断

---

### RunCommand

**描述:** 代表用户在终端会话中执行命令。

**参数:**

| 参数 | 类型 | 必填 | 描述 |
|-----------|------|----------|-------------|
| cwd | string | 否 | 工作目录（绝对路径） |
| command | string | 是 | 要执行的终端命令 |
| target_terminal | string | 否 | 执行命令的目标终端 |
| command_type | string | 否 | 命令类型：web_server、long_running_process、short_running_process、other |
| blocking | boolean | 是 | 仅对 web_server 或 long_running_process 设置为 false |
| wait_ms_before_async | integer | 否 | 异步模式前等待的毫秒数 |
| requires_approval | boolean | 是 | 用户是否必须在执行前批准 |

**重要注意:**
- 不要用于文件操作 — 使用专门的工具
- 终端在顺序调用之间是有状态的
- 避免搜索命令如 `find` 和 `grep` — 使用专用工具

---

### CheckCommandStatus

**描述:** 获取先前执行的非阻塞命令的状态。

**参数:**

| 参数 | 类型 | 必填 | 描述 |
|-----------|------|----------|-------------|
| command_id | string | 否 | 要获取状态的命令 ID |
| wait_ms_before_check | integer | 否 | 检查前等待的毫秒数 |
| output_character_count | integer | 否 | 要查看的字符数（默认：2000） |
| skip_character_count | integer | 否 | 从 output_priority 位置跳过的字符数 |
| output_priority | string | 否 | 优先级：top、bottom（默认）、split |
| filter | string | 否 | 用于过滤输出行的正则表达式 |

---

### StopCommand

**描述:** 终止当前运行的命令。

**参数:**

| 参数 | 类型 | 必填 | 描述 |
|-----------|------|----------|-------------|
| command_id | string | 是 | 要终止的运行中命令的命令 ID |

---

### GetDiagnostics

**描述:** 从 VS Code 获取语言诊断信息。

**参数:**

| 参数 | 类型 | 必填 | 描述 |
|-----------|------|----------|-------------|
| uri | string | 否 | 可选的文件 URI 以获取诊断信息 |

---

### DeleteFile

**描述:** 从文件系统中删除文件。

**参数:**

| 参数 | 类型 | 必填 | 描述 |
|-----------|------|----------|-------------|
| file_paths | array | 是 | 要删除的文件路径列表（绝对路径） |

---

### SearchReplace

**描述:** 使用搜索/替换操作编辑文件。

**参数:**

| 参数 | 类型 | 必填 | 描述 |
|-----------|------|----------|-------------|
| file_path | string | 是 | 文件路径（绝对路径） |
| old_str | string | 是 | 搜索部分 - 连续的行块 |
| new_str | string | 是 | 替换部分 - 要替换的行 |

**规则:**
1. 仅替换第一个匹配项
2. 在搜索部分包含足够的行以唯一匹配
3. 保持搜索和替换部分简洁

---

### Write

**描述:** 将文件写入本地文件系统。

**参数:**

| 参数 | 类型 | 必填 | 描述 |
|-----------|------|----------|-------------|
| file_path | string | 是 | 要写入的文件的绝对路径 |
| content | string | 是 | 要写入文件的内容 |

**使用注意:**
- 覆盖现有文件
- 写入前必须先读取现有文件
- 优先编辑而不是创建新文件

---

### TodoWrite

**描述:** 创建和管理结构化任务列表以跟踪进度。

**参数:**

| 参数 | 类型 | 必填 | 描述 |
|-----------|------|----------|-------------|
| todos | array | 是 | 任务项数组 |
| merge | boolean | 是 | 是否与现有任务合并 |
| summary | string | 否 | 完成工作的摘要 |

**任务项属性:**

| 属性 | 类型 | 必填 | 描述 |
|----------|------|----------|-------------|
| content | string | 是 | 任务项的描述 |
| status | string | 是 | 状态：pending、in_progress、completed |
| id | string | 是 | 唯一标识符 |
| priority | string | 是 | 优先级：high、medium、low |

**使用场景:** 具有 3 个以上不同步骤的复杂任务。

---

### AskUserQuestion

**描述:** 在执行过程中向用户提问以获取澄清或决策。

**参数:**

| 参数 | 类型 | 必填 | 描述 |
|-----------|------|----------|-------------|
| questions | array | 是 | 要向用户提出的问题（1-4 个） |

**问题属性:**

| 属性 | 类型 | 必填 | 描述 |
|----------|------|----------|-------------|
| question | string | 是 | 完整的问题 |
| header | string | 是 | 简短标签（最多 12 个字符） |
| options | array | 是 | 可用选项（2-4 个） |
| multiSelect | boolean | 否 | 允许多选 |

---

### NotifyUser

**描述:** 通知用户审核输出并请求批准后再继续。

**参数:**

| 参数 | 类型 | 必填 | 描述 |
|-----------|------|----------|-------------|
| explanation | string | 否 | 此通知的简要说明 |
| file_paths | array | 是 | 要审核的文档的绝对路径 |

**使用场景:**
- 计划模式完成，等待用户确认
- 规范模式完成，等待批准
- web-dev 技能文档准备好审核

---

### OpenPreview

**描述:** 向用户显示可用的预览 URL。

**参数:**

| 参数 | 类型 | 必填 | 描述 |
|-----------|------|----------|-------------|
| preview_url | string | 是 | 完整的有效预览 URL |
| command_id | string | 是 | 生成预览的命令 ID |

---

### run_mcp

**描述:** 通过服务器标识符和工具名称调用 MCP（Model Context Protocol）工具。

**参数:**

| 参数 | 类型 | 必填 | 描述 |
|-----------|------|----------|-------------|
| server_name | string | 是 | MCP 服务器的标识符 |
| tool_name | string | 是 | 要调用的 MCP 工具名称 |
| args | object | 是 | 要传递给 MCP 工具的参数 |

**重要:**
- 调用此工具前必须通过 LS 和 Read 获取工具描述符
- 所有工具特定参数必须在 `args` 字段内传递
- 只有 `server_name`、`tool_name` 和 `args` 是有效的顶级字段