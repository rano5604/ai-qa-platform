package com.company.aiqa.model;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Payload for POST /api/v1/generate-automation-for-project.
 *
 * <p>The commit-scoped sibling, {@link GenerateAutomationRequest}, turns ONE
 * commit's manual cases into automation - the right shape for "I just merged,
 * automate what's new". This is the other question: "automate everything the
 * project has ever accumulated", read from the project-wide rollup CSV
 * ({@code generated-tests/&lt;project&gt;/all_manual_test_cases.csv}) rather
 * than any one commit's folder. Every manual test case ever generated for the
 * project, across every commit, becomes one target for automation - not
 * pinned to a single commit's diff, since cases from different commits are
 * mixed together here by construction.
 *
 * <p>The API surface is read from the local checkout's CURRENT state (HEAD),
 * not any specific historical commit - there is no single commit that "the
 * whole project" corresponds to. Everything else about how this generates
 * automation is identical to the per-commit endpoint: only API-testable cases
 * are automated, an existing project-level script is extended rather than
 * regenerated (cases it already covers are skipped), and this endpoint only
 * generates - it sends no HTTP traffic and cannot mutate anything. Run
 * POST /api/v1/execute-automation-for-project (or the per-commit executor
 * pointed at this project) to actually run what it produces.
 */
public class GenerateAutomationForProjectRequest {

    /**
     * The project folder name under generated-tests/ - also used to find the
     * existing local clone (by matching its derived project name) when the API
     * surface needs scanning. The only thing this endpoint requires.
     */
    @NotBlank
    private String projectName;

    /**
     * Optional explicit path to a local checkout, if it isn't under
     * aiqa.git.workspace-dir where the platform's own clones live.
     */
    private String repoPath;

    /** Optional override of the configured output directory (must match what the original run used). */
    private String outputDir;

    /**
     * Base URI baked into the generated scripts' default, e.g.
     * "http://localhost:8080". The scripts always read
     * System.getProperty("baseUri", &lt;this&gt;) so the target can still be
     * overridden at run time without regenerating them.
     */
    private String baseUri;

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

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }

    public String getRepoPath() { return repoPath; }
    public void setRepoPath(String repoPath) { this.repoPath = repoPath; }

    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }

    public String getBaseUri() { return baseUri; }
    public void setBaseUri(String baseUri) { this.baseUri = baseUri; }

    public LlmKeys getLlmKeys() { return llmKeys; }
    public void setLlmKeys(LlmKeys llmKeys) { this.llmKeys = llmKeys; }

    public List<String> getOpenApiUrls() { return openApiUrls; }
    public void setOpenApiUrls(List<String> openApiUrls) { this.openApiUrls = openApiUrls; }
}
