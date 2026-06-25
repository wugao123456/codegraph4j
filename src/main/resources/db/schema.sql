-- CodeGraph SQLite 数据库模式
-- 版本 1

-- =============================================================================
-- 模式版本追踪表
-- =============================================================================
CREATE TABLE IF NOT EXISTS schema_versions (
    version INTEGER PRIMARY KEY,   -- 模式版本号
    applied_at INTEGER NOT NULL,   -- 应用时间戳（毫秒）
    description TEXT               -- 版本描述
);

-- 插入初始版本记录
INSERT INTO schema_versions (version, applied_at, description)
VALUES (1, strftime('%s', 'now') * 1000, '初始模式');

-- =============================================================================
-- 核心数据表
-- =============================================================================

-- 节点表：代码符号（函数、类、变量等）
CREATE TABLE IF NOT EXISTS nodes (
    id TEXT PRIMARY KEY,                 -- 节点唯一标识
    kind TEXT NOT NULL,                  -- 节点种类（如 function、class、variable 等）
    name TEXT NOT NULL,                  -- 符号名称
    qualified_name TEXT NOT NULL,        -- 完全限定名
    file_path TEXT NOT NULL,             -- 所在文件路径
    language TEXT NOT NULL,              -- 编程语言
    start_line INTEGER NOT NULL,         -- 起始行号
    end_line INTEGER NOT NULL,           -- 结束行号
    start_column INTEGER NOT NULL,       -- 起始列号
    end_column INTEGER NOT NULL,         -- 结束列号
    docstring TEXT,                      -- 文档注释
    signature TEXT,                      -- 函数/方法签名
    visibility TEXT,                     -- 可见性（public/private/protected）
    is_exported INTEGER DEFAULT 0,       -- 是否导出
    is_async INTEGER DEFAULT 0,          -- 是否异步
    is_static INTEGER DEFAULT 0,         -- 是否静态
    is_abstract INTEGER DEFAULT 0,       -- 是否抽象
    decorators TEXT,                     -- 装饰器/注解列表
    type_parameters TEXT,                -- 泛型类型参数
    return_type TEXT,                    -- 返回值类型
    updated_at INTEGER NOT NULL          -- 更新时间戳（毫秒）
);

-- 边表：节点之间的关系
CREATE TABLE IF NOT EXISTS edges (
    id INTEGER PRIMARY KEY AUTOINCREMENT, -- 边唯一标识
    source TEXT NOT NULL,                 -- 源节点 ID
    target TEXT NOT NULL,                 -- 目标节点 ID
    kind TEXT NOT NULL,                   -- 关系种类（如 calls、imports、inherits 等）
    metadata TEXT,                        -- 附加元数据（JSON 格式）
    line INTEGER,                         -- 关系所在行号
    col INTEGER,                          -- 关系所在列号
    provenance TEXT DEFAULT NULL,         -- 来源/出处
    FOREIGN KEY (source) REFERENCES nodes(id) ON DELETE CASCADE,
    FOREIGN KEY (target) REFERENCES nodes(id) ON DELETE CASCADE
);

-- 文件表：已追踪的源文件
CREATE TABLE IF NOT EXISTS files (
    path TEXT PRIMARY KEY,                -- 文件路径
    content_hash TEXT NOT NULL,           -- 文件内容哈希值
    language TEXT NOT NULL,               -- 编程语言
    size INTEGER NOT NULL,                -- 文件大小（字节）
    modified_at INTEGER NOT NULL,         -- 文件修改时间戳（毫秒）
    indexed_at INTEGER NOT NULL,          -- 索引时间戳（毫秒）
    node_count INTEGER DEFAULT 0,         -- 该文件包含的节点数量
    errors TEXT                           -- 解析错误信息
);

-- 未解析引用表：完整索引后仍需解析的引用
CREATE TABLE IF NOT EXISTS unresolved_refs (
    id INTEGER PRIMARY KEY AUTOINCREMENT, -- 引用唯一标识
    from_node_id TEXT NOT NULL,           -- 发起引用的节点 ID
    reference_name TEXT NOT NULL,         -- 引用的符号名称
    reference_kind TEXT NOT NULL,         -- 引用的种类
    line INTEGER NOT NULL,                -- 引用所在行号
    col INTEGER NOT NULL,                 -- 引用所在列号
    candidates TEXT,                      -- 候选解析目标列表
    file_path TEXT NOT NULL DEFAULT '',   -- 引用所在文件路径
    language TEXT NOT NULL DEFAULT 'unknown', -- 编程语言
    FOREIGN KEY (from_node_id) REFERENCES nodes(id) ON DELETE CASCADE
);

-- =============================================================================
-- 查询性能索引
-- =============================================================================

-- 节点表索引
CREATE INDEX IF NOT EXISTS idx_nodes_kind ON nodes(kind);
CREATE INDEX IF NOT EXISTS idx_nodes_name ON nodes(name);
CREATE INDEX IF NOT EXISTS idx_nodes_qualified_name ON nodes(qualified_name);
CREATE INDEX IF NOT EXISTS idx_nodes_file_path ON nodes(file_path);
CREATE INDEX IF NOT EXISTS idx_nodes_language ON nodes(language);
CREATE INDEX IF NOT EXISTS idx_nodes_file_line ON nodes(file_path, start_line);
CREATE INDEX IF NOT EXISTS idx_nodes_lower_name ON nodes(lower(name));

-- 全文搜索索引：基于节点名称、文档注释和签名的全文检索
CREATE VIRTUAL TABLE IF NOT EXISTS nodes_fts USING fts5(
    id,
    name,
    qualified_name,
    docstring,
    signature,
    content='nodes',
    content_rowid='rowid'
);

-- 触发器：保持 FTS 全文索引与节点表同步
CREATE TRIGGER IF NOT EXISTS nodes_ai AFTER INSERT ON nodes BEGIN
    INSERT INTO nodes_fts(rowid, id, name, qualified_name, docstring, signature)
    VALUES (NEW.rowid, NEW.id, NEW.name, NEW.qualified_name, NEW.docstring, NEW.signature);
END;

CREATE TRIGGER IF NOT EXISTS nodes_ad AFTER DELETE ON nodes BEGIN
    INSERT INTO nodes_fts(nodes_fts, rowid, id, name, qualified_name, docstring, signature)
    VALUES ('delete', OLD.rowid, OLD.id, OLD.name, OLD.qualified_name, OLD.docstring, OLD.signature);
END;

CREATE TRIGGER IF NOT EXISTS nodes_au AFTER UPDATE ON nodes BEGIN
    INSERT INTO nodes_fts(nodes_fts, rowid, id, name, qualified_name, docstring, signature)
    VALUES ('delete', OLD.rowid, OLD.id, OLD.name, OLD.qualified_name, OLD.docstring, OLD.signature);
    INSERT INTO nodes_fts(rowid, id, name, qualified_name, docstring, signature)
    VALUES (NEW.rowid, NEW.id, NEW.name, NEW.qualified_name, NEW.docstring, NEW.signature);
END;

-- 边表索引
CREATE INDEX IF NOT EXISTS idx_edges_kind ON edges(kind);
CREATE INDEX IF NOT EXISTS idx_edges_source_kind ON edges(source, kind);
CREATE INDEX IF NOT EXISTS idx_edges_target_kind ON edges(target, kind);

-- 文件表索引
CREATE INDEX IF NOT EXISTS idx_files_language ON files(language);
CREATE INDEX IF NOT EXISTS idx_files_modified_at ON files(modified_at);

-- 未解析引用表索引
CREATE INDEX IF NOT EXISTS idx_unresolved_from_node ON unresolved_refs(from_node_id);
CREATE INDEX IF NOT EXISTS idx_unresolved_name ON unresolved_refs(reference_name);
CREATE INDEX IF NOT EXISTS idx_unresolved_file_path ON unresolved_refs(file_path);
CREATE INDEX IF NOT EXISTS idx_unresolved_from_name ON unresolved_refs(from_node_id, reference_name);
CREATE INDEX IF NOT EXISTS idx_edges_provenance ON edges(provenance);

-- =============================================================================
-- 项目元数据表：版本和来源追踪
-- =============================================================================
CREATE TABLE IF NOT EXISTS project_metadata (
    key TEXT PRIMARY KEY,                 -- 元数据键名
    value TEXT NOT NULL,                  -- 元数据值
    updated_at INTEGER NOT NULL           -- 更新时间戳（毫秒）
);