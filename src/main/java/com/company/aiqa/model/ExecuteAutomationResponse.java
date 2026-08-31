package com.company.aiqa.model;

import java.util.List;

/**
 * Result of POST /api/v1/execute-automation.
 *
 * <p>The detail lives in {@link #testExecutionSummary} - per-method verdicts,
 * each carrying the HTTP requests and responses that produced it.
 */
public record ExecuteAutomationResponse(
        String projectName,
        String commitHash,

        /** The script file(s) that were compiled and run, as found on disk. */
        List<String> scriptsExecuted,

        /** Folder those scripts were read from. */
        String outputPath,

        /** Verdicts, timings, captured traffic, and the path to TestNG's own report. */
        TestExecutionSummary testExecutionSummary,

        /**
         * GET url for the request/response evidence report (aiqa-report.html) -
         * null when the run produced no report (empty script set never reaches
         * here; see AutomationExecutionService). testExecutionSummary carries
         * only the report's path on the SERVER's disk; this is what a caller on
         * another machine actually uses. Prefer copying this over assembling
         * /api/v1/execution-report/download by hand.
         */
        String executionReportDownloadUrl,

        String summary
) {
}
