package com.company.aiqa.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload for POST /api/v1/generate-tests-from-branch
 *
 * Unlike GenerateTestsRequest (which requires a pre-existing local clone),
 * this endpoint clones/fetches the repo itself using the supplied credential,
 * locates the most recent merge commit on the given branch, and runs the
 * pipeline against exactly that merge's before/after state.
 */
public class GenerateTestsFromBranchRequest {

    @NotBlank
    private String repoUrl;      // e.g. "https://github.com/org/repo.git"

    @NotBlank
    private String branch;       // e.g. "Release" - the branch the merge landed on

    /**
     * Personal/project access token with read access to repoUrl. Works for
     * GitHub, GitLab, or any other git host - optional if the server already
     * has a matching entry under aiqa.git.hosts (or aiqa.git.default-token)
     * configured; if provided, this takes precedence for this call only and
     * is never logged or persisted.
     */
    private String accessToken;

    /**
     * Optional hint for how accessToken should be sent: "GITHUB" (token as
     * username), "GITLAB" (token as password, GitLab's "oauth2" convention -
     * also works against most other git servers), or "GENERIC" (same as
     * GITLAB). Leave unset to auto-detect from repoUrl's hostname.
     */
    private String provider;

    /** Optional override of the configured output directory. */
    private String outputDir;

    /**
     * If true, reprocess the latest merge even if it's already recorded in
     * this repo+branch's merge history. Only applies to
     * /generate-tests-from-branch; the backfill endpoint always skips
     * already-processed merges regardless of this flag, since its whole
     * point is catching up on what's new. Default: false.
     */
    private Boolean force;

    /**
     * "MANUAL" (business/functional test cases for QA - default), "AUTOMATED"
     * (JUnit/Jest/pytest/etc. source files), or "BOTH".
     */
    private String testCaseMode;

    /** Same optional per-request LLM credentials as GenerateTestsRequest.llmKeys - see LlmKeys. */
    private LlmKeys llmKeys;

    public LlmKeys getLlmKeys() { return llmKeys; }
    public void setLlmKeys(LlmKeys llmKeys) { this.llmKeys = llmKeys; }

    public String getTestCaseMode() { return testCaseMode; }
    public void setTestCaseMode(String testCaseMode) { this.testCaseMode = testCaseMode; }

    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }

    public Boolean getForce() { return force; }
    public void setForce(Boolean force) { this.force = force; }
}
