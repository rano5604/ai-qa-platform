package com.company.aiqa.model;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Payload for POST /api/v1/generate-automation.
 *
 * <p>Turns manual test cases that were ALREADY generated for a specific commit
 * into runnable REST Assured automation. The commit hash is the folder name
 * under generated-tests/&lt;project&gt;/ - the same value used as headRef when
 * those cases were produced - so this is a second pass over existing output
 * rather than a fresh analysis.
 *
 * <p>Only the API-testable cases are automated. Cases that describe UI steps,
 * or configuration checks with no HTTP surface, are skipped and reported in
 * the response rather than turned into scripts that couldn't run.
 *
 * <p>This endpoint <b>only generates</b>. It sends no HTTP traffic anywhere and
 * cannot mutate anything outside the output folder. To run what it produced,
 * call POST /api/v1/execute-automation with the same commit hash. (An earlier
 * "executeTests" flag here has been removed; requests still sending it are
 * accepted and it is ignored.)
 *
 * <p>Nothing here identifies a remote repository - this pass reads the manual
 * cases from the commit's own CSV on disk and, for the API surface, an
 * already-existing local clone found by {@link #projectName} alone. A
 * "repoUrl" field used to exist purely to derive projectName when it was
 * omitted; it is gone now that projectName is required, so there is nothing
 * left it did that this doesn't already do directly. Any caller still sending
 * one is unaffected - it is simply not read.
 */
public class GenerateAutomationRequest {

    /** Optional, informational only - this endpoint targets a commit, not a branch. */
    private String branch;

    /**
     * Optional explicit path to a local checkout, if it isn't under
     * aiqa.git.workspace-dir where the platform's own clones live.
     */
    private String repoPath;

    /**
     * The commit hash whose generated-tests folder holds the manual cases to
     * automate. Must match the folder name exactly, e.g.
     * "4df458deab5a8d8d4748696d753b4aa54fdcf304".
     */
    @NotBlank
    private String commitHash;

    /** Unused - kept so existing callers do not break. Nothing is fetched, so no credential is needed. */
    private String accessToken;

    /** Unused - kept so existing callers do not break. */
    private String provider;

    /** Optional override of the configured output directory (must match what the original run used). */
    private String outputDir;

    /**
     * Base URI baked into the generated scripts' default, e.g.
     * "http://localhost:8080". The scripts always read
     * System.getProperty("baseUri", &lt;this&gt;) so the target can still be
     * overridden at run time without regenerating them.
     */
    private String baseUri;

    /**
     * The project folder name under generated-tests/ - the same name
     * generation used, e.g. "mms". Also used to find the existing local clone
     * (by matching its derived project name) when the API surface needs
     * scanning; nothing is fetched or cloned here.
     */
    @NotBlank
    private String projectName;

    /** Per-request LLM credentials - same semantics as everywhere else, see LlmKeys. */
    private LlmKeys llmKeys;

    /**
     * OpenAPI document URLs, which skip discovery entirely.
     *
     * <p>Discovery reads the repo's own configuration and is right for a
     * service that follows the usual conventions. This is for the ones that do
     * not: a document served from a gateway, a spec behind a different host, or
     * a component whose config lives somewhere the scan does not look. Supplying
     * one URL replaces ALL discovery, not just the component it belongs to.
     */
    private List<String> openApiUrls;

    public String getRepoPath() { return repoPath; }
    public void setRepoPath(String repoPath) { this.repoPath = repoPath; }

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public String getCommitHash() { return commitHash; }
    public void setCommitHash(String commitHash) { this.commitHash = commitHash; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }

    public String getBaseUri() { return baseUri; }
    public void setBaseUri(String baseUri) { this.baseUri = baseUri; }

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }

    public List<String> getOpenApiUrls() { return openApiUrls; }
    public void setOpenApiUrls(List<String> openApiUrls) { this.openApiUrls = openApiUrls; }

    public LlmKeys getLlmKeys() { return llmKeys; }
    public void setLlmKeys(LlmKeys llmKeys) { this.llmKeys = llmKeys; }
}
