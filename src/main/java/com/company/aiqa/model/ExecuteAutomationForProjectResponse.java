package com.company.aiqa.model;

import java.util.List;

/**
 * Result of POST /api/v1/execute-automation-for-project - the project-wide
 * counterpart to {@link ExecuteAutomationResponse}, which is scoped to one
 * commit. See that record for why the detail lives in
 * {@link #testExecutionSummary}; the only difference here is scope.
 */
public record ExecuteAutomationForProjectResponse(
        String projectName,

        /** The script file(s) that were compiled and run, as found on disk. */
        List<String> scriptsExecuted,

        /** Folder those scripts were read from - the project's own root, not a commit subfolder. */
        String outputPath,

        /** Verdicts, timings, captured traffic, and the path to TestNG's own report. */
        TestExecutionSummary testExecutionSummary,

        /**
         * GET url for the request/response evidence report (aiqa-report.html) -
         * null when the run produced no report. testExecutionSummary carries
         * only the report's path on the SERVER's disk; this is what a caller on
         * another machine actually uses.
         */
        String executionReportDownloadUrl,

        String summary
) {
}
