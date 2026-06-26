package com.codegraph.context;

/**
 * 自适应输出预算 — 根据项目规模返回 explore 工具的输出参数上限。
 * 对标 codegraph mcp/tools.ts 的 getExploreOutputBudget() 函数。
 *
 * <p>项目越大，输出上限越高，但始终保持低于 agent 的内联工具结果上限
 * (~25K chars)，避免结果被外化到文件导致额外读取成本。
 *
 * <p>分段策略：
 * <ul>
 *   <li>&lt;150 文件: 小项目，输出紧凑，13K 上限</li>
 *   <li>&lt;500 文件: 中小项目，18K 上限</li>
 *   <li>&lt;5000 文件: 中大项目，24K 上限，开启关系图</li>
 *   <li>≥5000 文件: 大型项目，维持 24K 上限（外化成本不变）</li>
 * </ul>
 */
public class ExploreOutputBudget {

    /** 总输出字符上限 */
    public int maxOutputChars;
    /** 默认最大文件数 */
    public int defaultMaxFiles;
    /** 单文件最大字符数 */
    public int maxCharsPerFile;
    /** 聚类合并间隙阈值（行） */
    public int gapThreshold;
    /** 文件头最大符号数 */
    public int maxSymbolsInFileHeader;
    /** 每类关系边最大显示数 */
    public int maxEdgesPerRelationshipKind;
    /** 是否包含关系图段落 */
    public boolean includeRelationships;
    /** 是否包含额外文件 */
    public boolean includeAdditionalFiles;
    /** 是否包含完整性信号 */
    public boolean includeCompletenessSignal;
    /** 是否包含预算说明 */
    public boolean includeBudgetNote;
    /** 是否排除低价值文件（测试/图标/i18n） */
    public boolean excludeLowValueFiles;

    public ExploreOutputBudget() {}

    /**
     * 根据项目的文件数量返回自适应输出预算。
     * @param fileCount 项目索引的文件总数
     */
    public static ExploreOutputBudget forProject(int fileCount) {
        if (fileCount < 150) {
            return tinyProject();
        } else if (fileCount < 500) {
            return smallProject();
        } else if (fileCount < 15000) {
            return mediumProject();
        } else {
            return largeProject();
        }
    }

    private static ExploreOutputBudget tinyProject() {
        ExploreOutputBudget b = new ExploreOutputBudget();
        b.maxOutputChars = 13000;
        b.defaultMaxFiles = 4;
        b.maxCharsPerFile = 3800;
        b.gapThreshold = 7;
        b.maxSymbolsInFileHeader = 5;
        b.maxEdgesPerRelationshipKind = 4;
        b.includeRelationships = false;
        b.includeAdditionalFiles = false;
        b.includeCompletenessSignal = false;
        b.includeBudgetNote = false;
        b.excludeLowValueFiles = true;
        return b;
    }

    private static ExploreOutputBudget smallProject() {
        ExploreOutputBudget b = new ExploreOutputBudget();
        b.maxOutputChars = 18000;
        b.defaultMaxFiles = 5;
        b.maxCharsPerFile = 3800;
        b.gapThreshold = 8;
        b.maxSymbolsInFileHeader = 6;
        b.maxEdgesPerRelationshipKind = 6;
        b.includeRelationships = false;
        b.includeAdditionalFiles = false;
        b.includeCompletenessSignal = false;
        b.includeBudgetNote = false;
        b.excludeLowValueFiles = true;
        return b;
    }

    private static ExploreOutputBudget mediumProject() {
        ExploreOutputBudget b = new ExploreOutputBudget();
        b.maxOutputChars = 24000;
        b.defaultMaxFiles = 8;
        b.maxCharsPerFile = fileCount < 5000 ? 6500 : 7000;
        b.gapThreshold = fileCount < 5000 ? 12 : 15;
        b.maxSymbolsInFileHeader = fileCount < 5000 ? 10 : 15;
        b.maxEdgesPerRelationshipKind = fileCount < 5000 ? 10 : 15;
        b.includeRelationships = true;
        b.includeAdditionalFiles = true;
        b.includeCompletenessSignal = true;
        b.includeBudgetNote = true;
        b.excludeLowValueFiles = false;
        return b;
    }

    // Helper for mediumProject — needs fileCount from caller
    private static int fileCount = 0;
    public static ExploreOutputBudget forProjectWithCount(int fc, int actualFileCount) {
        fileCount = fc;
        if (actualFileCount < 150) {
            ExploreOutputBudget b = new ExploreOutputBudget();
            b.maxOutputChars = 13000; b.defaultMaxFiles = 4; b.maxCharsPerFile = 3800;
            b.gapThreshold = 7; b.maxSymbolsInFileHeader = 5; b.maxEdgesPerRelationshipKind = 4;
            b.includeRelationships = false; b.includeAdditionalFiles = false;
            b.includeCompletenessSignal = false; b.includeBudgetNote = false;
            b.excludeLowValueFiles = true;
            return b;
        } else if (actualFileCount < 500) {
            ExploreOutputBudget b = new ExploreOutputBudget();
            b.maxOutputChars = 18000; b.defaultMaxFiles = 5; b.maxCharsPerFile = 3800;
            b.gapThreshold = 8; b.maxSymbolsInFileHeader = 6; b.maxEdgesPerRelationshipKind = 6;
            b.includeRelationships = false; b.includeAdditionalFiles = false;
            b.includeCompletenessSignal = false; b.includeBudgetNote = false;
            b.excludeLowValueFiles = true;
            return b;
        } else if (actualFileCount < 5000) {
            ExploreOutputBudget b = new ExploreOutputBudget();
            b.maxOutputChars = 24000; b.defaultMaxFiles = 8; b.maxCharsPerFile = 6500;
            b.gapThreshold = 12; b.maxSymbolsInFileHeader = 10; b.maxEdgesPerRelationshipKind = 10;
            b.includeRelationships = true; b.includeAdditionalFiles = true;
            b.includeCompletenessSignal = true; b.includeBudgetNote = true;
            b.excludeLowValueFiles = false;
            return b;
        } else {
            ExploreOutputBudget b = new ExploreOutputBudget();
            b.maxOutputChars = 24000; b.defaultMaxFiles = 8; b.maxCharsPerFile = 7000;
            b.gapThreshold = 15; b.maxSymbolsInFileHeader = 15; b.maxEdgesPerRelationshipKind = 15;
            b.includeRelationships = true; b.includeAdditionalFiles = true;
            b.includeCompletenessSignal = true; b.includeBudgetNote = true;
            b.excludeLowValueFiles = false;
            return b;
        }
    }

    private static ExploreOutputBudget largeProject() {
        ExploreOutputBudget b = new ExploreOutputBudget();
        b.maxOutputChars = 24000;
        b.defaultMaxFiles = 8;
        b.maxCharsPerFile = 7000;
        b.gapThreshold = 15;
        b.maxSymbolsInFileHeader = 15;
        b.maxEdgesPerRelationshipKind = 15;
        b.includeRelationships = true;
        b.includeAdditionalFiles = true;
        b.includeCompletenessSignal = true;
        b.includeBudgetNote = true;
        b.excludeLowValueFiles = false;
        return b;
    }

    // Reusable tier method
    public static ExploreOutputBudget getForFileCount(int fileCount) {
        if (fileCount < 150) {
            ExploreOutputBudget b = new ExploreOutputBudget();
            b.maxOutputChars = 13000; b.defaultMaxFiles = 4; b.maxCharsPerFile = 3800;
            b.gapThreshold = 7; b.maxSymbolsInFileHeader = 5; b.maxEdgesPerRelationshipKind = 4;
            b.includeRelationships = false; b.includeAdditionalFiles = false;
            b.includeCompletenessSignal = false; b.includeBudgetNote = false;
            b.excludeLowValueFiles = true;
            return b;
        } else if (fileCount < 500) {
            ExploreOutputBudget b = new ExploreOutputBudget();
            b.maxOutputChars = 18000; b.defaultMaxFiles = 5; b.maxCharsPerFile = 3800;
            b.gapThreshold = 8; b.maxSymbolsInFileHeader = 6; b.maxEdgesPerRelationshipKind = 6;
            b.includeRelationships = false; b.includeAdditionalFiles = false;
            b.includeCompletenessSignal = false; b.includeBudgetNote = false;
            b.excludeLowValueFiles = true;
            return b;
        } else if (fileCount < 5000) {
            ExploreOutputBudget b = new ExploreOutputBudget();
            b.maxOutputChars = 24000; b.defaultMaxFiles = 8; b.maxCharsPerFile = 6500;
            b.gapThreshold = 12; b.maxSymbolsInFileHeader = 10; b.maxEdgesPerRelationshipKind = 10;
            b.includeRelationships = true; b.includeAdditionalFiles = true;
            b.includeCompletenessSignal = true; b.includeBudgetNote = true;
            b.excludeLowValueFiles = false;
            return b;
        } else {
            ExploreOutputBudget b = new ExploreOutputBudget();
            b.maxOutputChars = 24000; b.defaultMaxFiles = 8; b.maxCharsPerFile = 7000;
            b.gapThreshold = 15; b.maxSymbolsInFileHeader = 15; b.maxEdgesPerRelationshipKind = 15;
            b.includeRelationships = true; b.includeAdditionalFiles = true;
            b.includeCompletenessSignal = true; b.includeBudgetNote = true;
            b.excludeLowValueFiles = false;
            return b;
        }
    }
}
