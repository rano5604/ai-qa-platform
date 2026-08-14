package com.company.aiqa.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload for POST /api/v1/execute-automation.
 *
 * <p>Runs automation that ALREADY EXISTS for a commit. Nothing is generated
 * here: no LLM is called, no credential is needed, and the scripts on disk are
 * compiled and executed exactly as they are. Generating and running are
 * separate endpoints precisely because they have such different costs and
 * risks - generation spends tokens and is safe, execution is free and fires
 * real traffic at a live system.
 *
 * <p><b>This fires real HTTP requests</b>, including whatever POST/PUT/DELETE
 * the test cases describe, against whatever {@link #baseUri} points at. Point
 * it at a disposable environment, never production.
 */
public class ExecuteAutomationRequest {

    /**
     * Optional. Used only to derive the project folder name under
     * generated-tests/. Supply either this or {@link #projectName}.
     */
    private String repoUrl;

    /**
     * Overrides the project folder name if it doesn't match what's derived from
     * repoUrl (e.g. the original run used a different remote).
     */
    private String projectName;

    /**
     * The commit whose generated automation to run - the same folder name under
     * generated-tests/&lt;project&gt;/ that generation wrote to.
     */
    @NotBlank
    private String commitHash;

    /**
     * Where to send the requests, e.g. "http://localhost:8082". Overrides the
     * default that was baked into the scripts at generation time, since the
     * scripts read System.getProperty("baseUri", <default>) - so the same
     * commit's automation can be pointed at a different environment without
     * regenerating anything.
     */
    private String baseUri;

    /** Optional override of the configured output directory (must match what the original run used). */
    private String outputDir;

    /**
     * Optional. Runs one specific script file (e.g.
     * "AutomationTest_4df458d.java") instead of every script in the commit's
     * folder. The default - run them all - is normally what you want, since
     * generation merges a commit's automation into a single file anyway.
     */
    private String scriptFileName;

    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }

    public String getCommitHash() { return commitHash; }
    public void setCommitHash(String commitHash) { this.commitHash = commitHash; }

    public String getBaseUri() { return baseUri; }
    public void setBaseUri(String baseUri) { this.baseUri = baseUri; }

    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }

    public String getScriptFileName() { return scriptFileName; }
    public void setScriptFileName(String scriptFileName) { this.scriptFileName = scriptFileName; }
}
