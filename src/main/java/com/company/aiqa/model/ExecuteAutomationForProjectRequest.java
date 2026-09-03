package com.company.aiqa.model;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * Payload for POST /api/v1/execute-automation-for-project.
 *
 * <p>The project-wide counterpart to {@link ExecuteAutomationRequest}: runs
 * the merged script POST /api/v1/generate-automation-for-project wrote to the
 * project's own root folder ({@code generated-tests/&lt;project&gt;/AutomationTest_&lt;project&gt;.java}),
 * rather than a commit's subfolder - so this needs only {@link #projectName},
 * never a commit hash.
 *
 * <p>Nothing is generated here, same as the commit-scoped endpoint: no LLM is
 * called, no credential is needed, and the script on disk is compiled and
 * executed exactly as it is.
 *
 * <p><b>This fires real HTTP requests</b>, including whatever POST/PUT/DELETE
 * the test cases describe, against whatever {@link #baseUri} points at. Point
 * it at a disposable environment, never production.
 */
public class ExecuteAutomationForProjectRequest {

    /** The project folder name under generated-tests/ - the only thing this endpoint requires. */
    @NotBlank
    private String projectName;

    /**
     * Where to send the requests, e.g. "http://localhost:8082". Overrides the
     * default that was baked into the script at generation time, since the
     * script reads System.getProperty("baseUri", &lt;default&gt;) - so the same
     * project's automation can be pointed at a different environment without
     * regenerating anything.
     */
    private String baseUri;

    /** Optional override of the configured output directory (must match what the original run used). */
    private String outputDir;

    /**
     * Optional. Runs one specific script file instead of every
     * {@code AutomationTest_*} file in the project's root. The default - run
     * them all - is normally what you want, since generation merges the
     * project's automation into a single file anyway.
     */
    private String scriptFileName;

    /**
     * Optional. Headers added to every request that doesn't already set them,
     * so a suite can reach an auth-gated target - e.g.
     * {@code {"Authorization": "Bearer &lt;token&gt;"}} for a bearer token, or an
     * API-key/cookie header. Supplied at execution, exactly like {@link #baseUri},
     * because the generator is deliberately barred from inventing auth it can't
     * see in the diff; a target that answers 401 to an unauthenticated request
     * fails the whole suite otherwise. A test that sets its own value for one of
     * these headers keeps it (a deliberate bad-token negative case still works).
     */
    private Map<String, String> authHeaders;

    /**
     * Optional. Obtain the token by logging in, instead of pasting a static one
     * into {@link #authHeaders}. Supply the credentials; the login URL and the
     * token's field are discovered from the target source when omitted. The
     * resolved header is injected exactly like {@link #authHeaders}, and the two
     * may be combined.
     */
    private AuthLoginSpec login;

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }

    public String getBaseUri() { return baseUri; }
    public void setBaseUri(String baseUri) { this.baseUri = baseUri; }

    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }

    public String getScriptFileName() { return scriptFileName; }
    public void setScriptFileName(String scriptFileName) { this.scriptFileName = scriptFileName; }

    public Map<String, String> getAuthHeaders() { return authHeaders; }
    public void setAuthHeaders(Map<String, String> authHeaders) { this.authHeaders = authHeaders; }

    public AuthLoginSpec getLogin() { return login; }
    public void setLogin(AuthLoginSpec login) { this.login = login; }
}
