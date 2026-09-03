package com.company.aiqa.model;

import java.util.List;

/**
 * Result of POST /api/v1/generate-automation-for-project - the project-wide
 * counterpart to {@link GenerateAutomationResponse}, which is scoped to one
 * commit. See that record for why the counts below are broken out the way
 * they are; the only difference here is scope: every field covers the whole
 * project's rollup CSV, not one commit's folder.
 */
public record GenerateAutomationForProjectResponse(
        String projectName,

        /** Manual test cases found across the project's whole rollup CSV. */
        int manualCasesFound,

        /**
         * How many of those were API-testable. NOT all of these were
         * necessarily sent to the model this call - see alreadyAutomated below
         * for how many were already covered and skipped.
         */
        int apiCasesSelected,

        /** Cases skipped for having no HTTP surface (UI steps, config checks). */
        int skippedNonApi,

        /**
         * Of the API-testable cases, how many already had a {@code @Test}
         * method in an earlier run's project-level merged script and were left
         * untouched - no LLM call spent on them, no method regenerated. 0 on a
         * project's first generation, since nothing exists yet to already
         * cover anything.
         */
        int alreadyAutomated,

        /** REST endpoints detected in the project's current (HEAD) source, used to ground the generated scripts. */
        List<String> endpointsDetected,

        /** The generated REST Assured files, written to the project's output folder. */
        List<TestCaseResult> generatedTests,

        /** Folder the scripts were written to. */
        String outputPath,

        String summary
) {
}
