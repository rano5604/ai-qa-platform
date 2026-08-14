package com.company.aiqa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the "aiqa.pipeline" section of application.yml.
 */
@ConfigurationProperties(prefix = "aiqa.pipeline")
public class PipelineProperties {

    /** Where generated test files are written (extension/framework depends on the source language). */
    private String outputDir = "generated-tests";

    /** How many hops of the dependency graph to walk when computing impact. */
    private int impactDepth = 2;

    /** Max number of changed files processed per run (guardrail). */
    private int maxChangedFiles = 40;

    /**
     * Default output when a request doesn't specify testCaseMode:
     * "MANUAL" (business/functional test cases for QA, default),
     * "AUTOMATED" (JUnit/Jest/pytest/etc. source files), or "BOTH".
     */
    private String defaultTestCaseMode = "MANUAL";

    public String getDefaultTestCaseMode() { return defaultTestCaseMode; }
    public void setDefaultTestCaseMode(String defaultTestCaseMode) { this.defaultTestCaseMode = defaultTestCaseMode; }

    /**
     * Categories to generate separately (one dedicated LLM call each) when
     * testCaseMode is MANUAL/BOTH, then merge - this is what guarantees real
     * negative/boundary/security coverage instead of a single prompt that
     * tends to front-load happy-path cases and taper off. Reorder or trim
     * this list to change emphasis (e.g. drop SECURITY for a low-risk repo,
     * or list SECURITY twice for extra scrutiny on a sensitive one).
     */
    private java.util.List<String> testCaseCategories =
            java.util.List.of("POSITIVE", "NEGATIVE", "BOUNDARY", "SECURITY");

    public java.util.List<String> getTestCaseCategories() { return testCaseCategories; }
    public void setTestCaseCategories(java.util.List<String> testCaseCategories) { this.testCaseCategories = testCaseCategories; }

    /**
     * How many existing test cases (per category) to show the LLM as
     * "here's what's already in the catalog" context when deciding NEW vs
     * UPDATE. Capped rather than showing the whole catalog, since after
     * hundreds of merges the master CSV can grow to thousands of rows and
     * blow the context window / cost per call. Takes the most recently
     * added entries for that category, on the heuristic that recent work
     * is more likely to be what a new diff touches again than something
     * added long ago - not perfect, but avoids the cost/size of a full
     * semantic match for this.
     */
    private int maxExistingTestCasesPerCategory = 40;

    /**
     * Soft budget for a single LLM prompt, in characters (~4 chars per token,
     * so the 400k default is roughly 100k tokens - comfortably inside Gemini
     * 2.5 Flash's window). When a batch's prompt exceeds this, the batch is
     * SPLIT IN HALF and each half sent separately - nothing is dropped, it
     * just takes more calls. The same splitting kicks in if a provider
     * rejects the prompt as too long at call time, since a given model's real
     * context window may be smaller than this budget.
     *
     * <p>Keep this in step with {@link #maxChangedFiles}: a full batch should
     * fit UNDER this budget. If it doesn't, every batch splits anyway and each
     * split re-sends the whole fixed context (existing cases, parsed units,
     * impact sets) again - which is the single biggest source of wasted
     * tokens, since that context costs the same whether it accompanies 40
     * files or 10.
     */
    private int maxPromptChars = 400_000;

    public int getMaxPromptChars() { return maxPromptChars; }
    public void setMaxPromptChars(int maxPromptChars) { this.maxPromptChars = maxPromptChars; }

    /**
     * How many categories to request in a single LLM call.
     *
     * <p>1 (default) gives each category the model's full attention and the
     * best depth per category. Raising it to 2 pairs categories up, halving
     * both call count and token spend - every category is still covered, but
     * each tends to come back with fewer cases because the model splits its
     * response budget across the combined ask.
     *
     * <p>Unlike maxPromptChars/maxChangedFiles - which are pure efficiency
     * wins with no downside - this one genuinely trades depth for cost. Raise
     * it only when token budget is the binding constraint.
     */
    private int categoriesPerCall = 1;

    public int getCategoriesPerCall() { return categoriesPerCall; }
    public void setCategoriesPerCall(int categoriesPerCall) { this.categoriesPerCall = categoriesPerCall; }

    /**
     * How many manual test cases /generate-automation converts per LLM call.
     *
     * <p>Unlike the prompt-size budget, the binding constraint here is the
     * OUTPUT limit: each case becomes a full REST Assured test method, so a
     * dozen cases in one response easily exceeds aiqa.router.max-tokens and
     * comes back truncated mid-string - unparseable JSON, and every case in
     * that call lost. Batching keeps each response comfortably inside the
     * cap; a batch that still overflows is split in half and retried.
     */
    private int maxCasesPerAutomationCall = 5;

    public int getMaxCasesPerAutomationCall() { return maxCasesPerAutomationCall; }
    public void setMaxCasesPerAutomationCall(int maxCasesPerAutomationCall) { this.maxCasesPerAutomationCall = maxCasesPerAutomationCall; }

    /**
     * How long POST /execute-automation waits for the TestNG subprocess before
     * killing it. This bounds a HUNG run, not a slow one - the child is doing
     * real network I/O against a target that may itself be wedged, and without
     * a ceiling a single unresponsive endpoint would pin a request thread
     * indefinitely. Raise it for suites that legitimately run longer; the whole
     * suite shares this one budget, not each test.
     */
    private long executionTimeoutSeconds = 300;

    public long getExecutionTimeoutSeconds() { return executionTimeoutSeconds; }
    public void setExecutionTimeoutSeconds(long executionTimeoutSeconds) { this.executionTimeoutSeconds = executionTimeoutSeconds; }

    /**
     * Per-body character cap on captured HTTP request/response payloads.
     * Anything longer is truncated with the full length noted, so one endpoint
     * returning a multi-megabyte list can't make the API response unusable.
     */
    private int maxCapturedBodyChars = 8_000;

    public int getMaxCapturedBodyChars() { return maxCapturedBodyChars; }
    public void setMaxCapturedBodyChars(int maxCapturedBodyChars) { this.maxCapturedBodyChars = maxCapturedBodyChars; }

    /**
     * Ceiling on how many HTTP exchanges a single run records. Tests keep
     * running once it's hit - only the capture stops - so a script that loops
     * over a thousand ids degrades its own evidence rather than filling the disk.
     */
    private int maxCapturedExchanges = 500;

    public int getMaxCapturedExchanges() { return maxCapturedExchanges; }
    public void setMaxCapturedExchanges(int maxCapturedExchanges) { this.maxCapturedExchanges = maxCapturedExchanges; }

    public int getMaxExistingTestCasesPerCategory() { return maxExistingTestCasesPerCategory; }
    public void setMaxExistingTestCasesPerCategory(int maxExistingTestCasesPerCategory) { this.maxExistingTestCasesPerCategory = maxExistingTestCasesPerCategory; }

    /**
     * Whether a merge that changes configuration files (application.yml,
     * .properties, Dockerfile, pom.xml, etc. - see ConfigType) triggers a
     * dedicated "Configuration" test-case category, in addition to (or, for a
     * config-only merge, instead of) the source-diff categories above. On by
     * default: a merge that only changes config used to be silently skipped
     * ("no changed source files found") and produce zero test cases even
     * though config changes are exactly the kind of thing QA wants a
     * regression checklist for.
     */
    private boolean configChangeDetectionEnabled = true;

    public boolean isConfigChangeDetectionEnabled() { return configChangeDetectionEnabled; }
    public void setConfigChangeDetectionEnabled(boolean configChangeDetectionEnabled) { this.configChangeDetectionEnabled = configChangeDetectionEnabled; }

    /**
     * Whether BUILD TOOLING changes (build.gradle, pom.xml, wrapper and version
     * catalogs, Dockerfiles, CI pipeline definitions - see
     * ConfigType.isBuildTooling) also produce config test cases. Off by default.
     *
     * <p>Config-change detection above deliberately casts a wide net, which
     * swept in build files too: a commit that only fixed the Android toolchain
     * generated a "Configuration" checklist asking a tester to verify the build
     * resolves and packaging is unchanged. The build proves that itself, and the
     * change contains no business logic - no conditions, no rules, no outcomes -
     * so there is nothing meaningful to test and the cases are pure noise in the
     * suite. Turn this on only if you specifically want build/release checklists.
     */
    private boolean buildConfigTestCasesEnabled = false;

    public boolean isBuildConfigTestCasesEnabled() { return buildConfigTestCasesEnabled; }
    public void setBuildConfigTestCasesEnabled(boolean buildConfigTestCasesEnabled) { this.buildConfigTestCasesEnabled = buildConfigTestCasesEnabled; }

    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }

    public int getImpactDepth() { return impactDepth; }
    public void setImpactDepth(int impactDepth) { this.impactDepth = impactDepth; }

    public int getMaxChangedFiles() { return maxChangedFiles; }
    public void setMaxChangedFiles(int maxChangedFiles) { this.maxChangedFiles = maxChangedFiles; }
}
