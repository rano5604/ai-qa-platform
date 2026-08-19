package com.company.aiqa.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload for POST /api/v1/generate-tests
 *
 * <p>Identify the repository by EITHER {@code repoPath} (a checkout already on
 * this machine) OR {@code repoUrl} (cloned on demand, reusing an existing clone
 * and fetching it when one is already present). Supplying repoUrl means a
 * caller can target any commit of any repo in a single call, instead of having
 * to run a branch endpoint first just to get the clone on disk.
 */
public class GenerateTestsRequest {

    /**
     * Path to a checkout on this machine. Optional when {@link #repoUrl} is
     * given; when both are set this wins, since a caller naming an exact
     * directory means that directory.
     */
    private String repoPath;

    /**
     * Remote to clone/fetch when repoPath is absent, e.g.
     * "https://github.com/org/repo.git". The clone is reused across runs - it
     * lands under aiqa.git.workspace-dir keyed by URL - so repeated calls
     * fetch rather than re-clone.
     */
    private String repoUrl;

    /**
     * Read token for repoUrl. Optional when the server already has a matching
     * entry under aiqa.git.hosts (or aiqa.git.default-token). Never logged or
     * persisted; applies to this call only.
     */
    private String accessToken;

    /**
     * Optional hint for how accessToken is sent: "GITHUB" (token as username),
     * "GITLAB"/"GENERIC" (token as password). Leave unset to auto-detect from
     * repoUrl's hostname.
     */
    private String provider;

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

    /**
     * This commit's 1-based position in the branch's chronological history,
     * prefixed onto the run folder as {@code <seq>.<commitHash>}.
     *
     * <p>Set automatically by the backfill, which is the only caller that knows
     * the whole ordering. Without it generated-tests/ is a flat pile of
     * 40-character hashes in no discernible order, and telling which merge came
     * first means going back to git.
     *
     * <p>Taken from the commit's index in the FULL merge list, not from a
     * counter of what was processed this run - so a resumed or partial backfill
     * gives a commit the same number it had last time, instead of renumbering
     * everything after a skip.
     */
    private Integer commitSequence;

    public Integer getCommitSequence() { return commitSequence; }
    public void setCommitSequence(Integer commitSequence) { this.commitSequence = commitSequence; }

    public LlmKeys getLlmKeys() { return llmKeys; }
    public void setLlmKeys(LlmKeys llmKeys) { this.llmKeys = llmKeys; }

    public String getTestCaseMode() { return testCaseMode; }
    public void setTestCaseMode(String testCaseMode) { this.testCaseMode = testCaseMode; }

    public String getRepoPath() { return repoPath; }
    public void setRepoPath(String repoPath) { this.repoPath = repoPath; }

    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getBaseRef() { return baseRef; }
    public void setBaseRef(String baseRef) { this.baseRef = baseRef; }

    public String getHeadRef() { return headRef; }
    public void setHeadRef(String headRef) { this.headRef = headRef; }

    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }
}
