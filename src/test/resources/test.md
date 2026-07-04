Execute a skill within the main conversation
<skills_instructions>
When users ask you to perform tasks, check if any of the available skills below can help complete the task more effectively. Skills provide specialized capabilities and domain knowledge.
How to use skills:
- Invoke skills using this tool with the skill name only (no arguments)
- When you invoke a skill, you will see <command-message>The "{name}" skill is loading</command-message>
- The skill's prompt will expand and provide detailed instructions on how to complete the task
- Examples:
  - `command: "pdf"` - invoke the pdf skill
  - `command: "xlsx"` - invoke the xlsx skill
  - `command: "ms-office-suite:pdf"` - invoke using fully qualified name
Important:
- When a skill is relevant, you must invoke this tool IMMEDIATELY as your first action
- NEVER just announce or mention a skill in your text response without actually calling this tool
- This is a BLOCKING REQUIREMENT: invoke the relevant Skill tool BEFORE generating any other response about the task
- Only use skills listed in <available_skills> below
- Do not invoke a skill that is already running
- Do not use this tool for built-in CLI commands (like /help, /clear, etc.)
</skills_instructions>
<available_skills>
<skill>
<name>
TRAE-code-review
</name>
<description>
用于执行代码审查任务。适用于审查合并请求、代码差异，并提供关于代码质量、正确性和最佳实践的结构化反馈。
</description>
</skill>
<skill>
<name>
TRAE-debugger
</name>
<description>
用于调试需要收集运行时证据的复杂问题。它会启动一个调试服务器通过 HTTP 收集日志，然后遵循科学的调试流程（假设 → 插桩 → 复现 → 分析 → 修复 → 验证）。适用于仅通过静态代码分析无法诊断的 Bug。在用户主动要求运行时调试、或经过多轮对话仍无法通过静态分析解决问题时触发。
</description>
</skill>
<skill>
<name>
TRAE-generate-mini-app
</name>
<description>
当用户意图涉及小程序、Taro、微信小程序、跨端小程序等任何包含小程序的意图时，用于生成基于 Taro 框架的高质量、可运行的多端小程序代码。
</description>
</skill>
<skill>
<name>
TRAE-security-review
</name>
<description>
用于执行代码安全扫描任务。适用于审查合并请求和代码差异，并提供关于代码安全、漏洞风险和安全最佳实践的结构化反馈。
</description>
</skill>
<skill>
<name>
atlasProject
</name>
<description>
此技能用于生成当前项目的整体描述，包括项目目的、技术栈、核心模块、主要工作流和架构特点。当用户初次进入/Users/wugao-pc/Desktop/Project/knowGraph/knowledgeGraphSys/路径时，会自动调用此技能。
</description>
</skill>
<skill>
<name>
skill-creator
</name>
<description>
MANDATORY tool for creating SKILLs - MUST be invoked IMMEDIATELY when user wants to create/add any skill
</description>
</skill>
<skill>
<name>
web-dev
</name>
<description>
Create production-grade web interfaces with high design quality. **Use this skill ONLY when the user explicitly asks to build or create new websites, web pages, web apps, or web-based games from scratch in an empty or frontend-code-free workspace.** Not for bug fixes or modifications to existing projects.
</description>
</skill>

</available_skills>
