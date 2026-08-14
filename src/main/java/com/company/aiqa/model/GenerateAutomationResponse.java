package com.company.aiqa.model;

import java.util.List;

/**
 * Result of POST /api/v1/generate-automation.
 *
 * <p>The counts are deliberately explicit about what was NOT automated:
 * automating a UI-only or config-only case would produce a script that can't
 * meaningfully run, so those are skipped - and saying so plainly is more
 * useful than quietly generating fewer files than there were test cases.
 *
 * <p>There are no execution results here by design - generating and running are
 * separate endpoints. See ExecuteAutomationResponse.
 */
public record GenerateAutomationResponse(
        String projectName,
        String commitHash,

        /** Manual test cases found in that commit's folder. */
        int manualCasesFound,

        /** How many of those were API-testable and fed to the generator. */
        int apiCasesSelected,

        /** Cases skipped for having no HTTP surface (UI steps, config checks). */
        int skippedNonApi,

        /** REST endpoints detected in the commit, used to ground the generated scripts. */
        List<String> endpointsDetected,

        /** The generated REST Assured files, written alongside the manual cases. */
        List<TestCaseResult> generatedTests,

        /** Folder the scripts were written to. */
        String outputPath,

        String summary
) {
}
