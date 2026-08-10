package com.company.aiqa.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload for POST /api/v1/generate-tests
 */
public class GenerateTestsRequest {

    @NotBlank
    private String repoPath;

    @NotBlank
    private String baseRef;   // e.g. "origin/main" or a commit SHA

    @NotBlank
    private String headRef;   // e.g. "origin/release/1.2" or "HEAD"

    /** Optional override of the configured output directory. */
    private String outputDir;

    /**
     * "MANUAL" (business/functional test cases for QA - default), "AUTOMATED"
     * (JUnit/Jest/pytest/etc. source files), or "BOTH". Leave unset to use
     * the server-configured default (aiqa.pipeline.default-test-case-mode).
     */
    private String testCaseMode;

    /**
     * Optional per-request LLM credentials. When supplied, ONLY the providers
     * whose key is present here are used for this run - any provider left out
     * is treated as inactive and never attempted. Omit the whole object to use
     * the server-configured keys (the previous behavior). See LlmKeys.
     */
    private LlmKeys llmKeys;

    /**
     * Restricts generation to these test-case categories only. Normally left
     * unset (meaning "all of aiqa.pipeline.test-case-categories"); it's set
     * automatically when RESUMING a partially-generated merge, so only the
     * categories that are actually missing get regenerated and work already
     * completed by an earlier attempt is never redone.
     */
    private java.util.List<String> onlyCategories;

    public java.util.List<String> getOnlyCategories() { return onlyCategories; }
    public void setOnlyCategories(java.util.List<String> onlyCategories) { this.onlyCategories = onlyCategories; }

    public LlmKeys getLlmKeys() { return llmKeys; }
    public void setLlmKeys(LlmKeys llmKeys) { this.llmKeys = llmKeys; }

    public String getTestCaseMode() { return testCaseMode; }
    public void setTestCaseMode(String testCaseMode) { this.testCaseMode = testCaseMode; }

    public String getRepoPath() { return repoPath; }
    public void setRepoPath(String repoPath) { this.repoPath = repoPath; }

    public String getBaseRef() { return baseRef; }
    public void setBaseRef(String baseRef) { this.baseRef = baseRef; }

    public String getHeadRef() { return headRef; }
    public void setHeadRef(String headRef) { this.headRef = headRef; }

    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }
}
