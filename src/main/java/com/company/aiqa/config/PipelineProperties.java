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

    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }

    public int getImpactDepth() { return impactDepth; }
    public void setImpactDepth(int impactDepth) { this.impactDepth = impactDepth; }

    public int getMaxChangedFiles() { return maxChangedFiles; }
    public void setMaxChangedFiles(int maxChangedFiles) { this.maxChangedFiles = maxChangedFiles; }
}
