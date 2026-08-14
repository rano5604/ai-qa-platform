package com.company.aiqa.model;

import java.util.List;

public record GenerateTestsResponse(
        int changedFilesProcessed,
        int classesParsed,
        int impactedClassCount,
        /** Java-only (see DependencyService.buildMethodLevelEdges); 0 for a non-Java or purely config change. */
        int impactedMethodCount,
        List<CommitInfo> commitLog,
        List<ManualTestCase> businessTestCases,
        /** Where the CSV was written on the SERVER - not reachable by a remote caller. */
        String manualTestCasesCsvPath,
        /**
         * Relative URL that returns the CSV above as a download, so a caller on
         * another machine can actually retrieve what this run produced.
         * Null when the run generated nothing.
         */
        String manualTestCasesDownloadUrl,
        List<TestCaseResult> generatedTests,

        /** Categories this run set out to produce (all configured ones, or just the missing ones on a resume). */
        List<String> expectedCategories,
        /** Categories that actually produced test cases in this run. */
        List<String> generatedCategories,
        /** expectedCategories minus generatedCategories - what a resume would pick up. */
        List<String> missingCategories,
        /** generated / expected, 0-100. */
        int coveragePercent,
        /** SUCCESS when nothing is missing, PARTIAL when some categories failed, FAILED when none succeeded. */
        MergeStatus status,

        String summary
) {
}
