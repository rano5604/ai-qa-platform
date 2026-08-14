package com.company.aiqa.model;

import java.util.List;

/**
 * Outcome of compiling and running generated automation.
 *
 * <p>errors covers scripts that failed to COMPILE as well as failures to
 * launch - LLM-generated code isn't guaranteed to build, and a script that
 * didn't compile contributes nothing to totalTests but is reported here
 * rather than vanishing. Scripts that did compile still run even when a
 * sibling failed.
 */
public record TestExecutionSummary(
        String baseUri,
        int scriptsAttempted,
        int scriptsCompiled,
        List<String> errors,
        int totalTests,
        int passed,
        int failed,
        int errorCount,
        int skipped,

        /**
         * Folder holding TestNG's own report output - index.html for a human,
         * testng-results.xml for a machine. Kept on disk next to the scripts
         * rather than deleted with the temp workspace, so a run stays
         * inspectable after the API response has been read and forgotten.
         */
        String reportPath,

        /** Per-method results, each carrying its own HTTP exchanges. */
        List<TestExecutionResult> results
) {
}
