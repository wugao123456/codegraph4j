package com.codegraph.config;

import com.codegraph.context.ContextBuilder;

import java.io.File;

/**
 * CodeGraph 统一配置类。
 * 集中管理所有可配置项，通过 CLI 参数或环境变量注入。
 */
public class CodeGraphConfig {

    private String projectPath;
    private final String dbPath;

    private int webPort = 0;

    private int searchLimit = 8;
    private int traversalDepth = 3;
    private int maxNodes = 200;

 
    public CodeGraphConfig(String projectPath, String dbPath) {
        this.projectPath = projectPath;
        this.dbPath = dbPath;
       
    }

    // ========== Getters ==========

    /** 项目源码根目录（日志、解析代码、.codegraph 输出等基于此） */
    public String getProjectPath() {
        return projectPath;
    }

    /** 数据库目录路径；null 时默认 = projectPath/.codegraph */
    public String getDbPath() {
        return dbPath;
    }

    /** 获取实际的数据库文件 */
    public File getDbFile() {
        String base = (dbPath != null) ? dbPath : projectPath;
        return new File(base, ".codegraph/codegraph4j.db");
    }



    /** 获取日志目录 */
    public File getLogDir() {
        return new File(projectPath, ".codegraph/logs");
    }

    // ========== Web 服务参数 ==========

    /** Web 查看器 HTTP 端口，0 表示禁用 */
    public int getWebPort() {
        return webPort;
    }

    public void setWebPort(int webPort) {
        this.webPort = webPort;
    }

    // ========== 搜索参数 ==========

    public int getSearchLimit() {
        return searchLimit;
    }

    public void setSearchLimit(int searchLimit) {
        this.searchLimit = searchLimit;
    }

    public int getTraversalDepth() {
        return traversalDepth;
    }

    public void setTraversalDepth(int traversalDepth) {
        this.traversalDepth = traversalDepth;
    }

    public int getMaxNodes() {
        return maxNodes;
    }

    public void setMaxNodes(int maxNodes) {
        this.maxNodes = maxNodes;
    }

    // ========== 工厂方法 ==========

    /** 构建探索搜索选项 */
    public ContextBuilder.FindOptions buildExploreFindOptions() {
        ContextBuilder.FindOptions opts = new ContextBuilder.FindOptions();
        opts.searchLimit = this.searchLimit;
        opts.traversalDepth = this.traversalDepth;
        opts.maxNodes = this.maxNodes;
        return opts;
    }
}
