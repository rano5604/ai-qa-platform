package com.company.aiqa.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One row in a repo+branch's persisted merge history: a merge commit the
 * pipeline has run against, together with enough state to tell whether that
 * run actually FINISHED. Written by MergeHistoryService, one JSON file per
 * (repoUrl, branch).
 *
 * <p>The original version of this record held only mergeSha/preMergeSha/
 * processedAt/summary, which made "recorded" indistinguishable from
 * "complete" - a merge whose generation half-failed looked identical to one
 * that fully succeeded, so nothing ever went back to finish it. The extra
 * fields here are what make incomplete work discoverable and resumable.
 *
 * <p><b>Backward compatibility:</b> history files written by the earlier
 * version contain only the first four fields. Jackson supplies null/0 for the
 * rest, and the compact constructor below normalizes those - crucially
 * defaulting a missing status to {@link MergeStatus#SUCCESS}. That preserves
 * the old semantics exactly ("it's in the file, so it was processed") and
 * means upgrading does NOT cause every previously-recorded merge to be
 * re-run.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MergeHistoryEntry(
        String mergeSha,
        String preMergeSha,
        String processedAt,   // ISO-8601 - first time this merge was recorded
        String summary,

        /** SUCCESS / PARTIAL / FAILED - see the backward-compat note above. */
        MergeStatus status,

        /** How many times this merge has been attempted (1 after the first run). */
        int attemptCount,

        /** ISO-8601 timestamp of the most recent attempt. */
        String lastAttemptAt,

        /** Why the last attempt didn't fully succeed; null when status is SUCCESS. */
        String failureReason,

        /** Categories this run was supposed to produce (e.g. POSITIVE, NEGATIVE, ...). */
        List<String> expectedCategories,

        /** Categories actually produced across all attempts so far. */
        List<String> generatedCategories,

        /** expectedCategories minus generatedCategories - exactly what a resume regenerates. */
        List<String> missingCategories,

        /** Total business test cases written for this merge. */
        int testCaseCount,

        /** generated / expected, as a 0-100 percentage. */
        int coveragePercent
) {

    /**
     * Recovers which categories failed from a legacy summary line, which the
     * old pipeline wrote as:
     * {@code WARNING: 2 categories failed and produced no test cases: [NEGATIVE, SECURITY].}
     */
    private static final java.util.regex.Pattern LEGACY_FAILED_CATEGORIES = java.util.regex.Pattern.compile(
            "categor(?:y|ies) failed and produced no test cases: \\[([^\\]]+)]");

    public MergeHistoryEntry {
        expectedCategories = expectedCategories == null ? List.of() : List.copyOf(expectedCategories);
        generatedCategories = generatedCategories == null ? List.of() : List.copyOf(generatedCategories);
        missingCategories = missingCategories == null ? List.of() : List.copyOf(missingCategories);

        if (status == null) {
            // Legacy row (written before status tracking existed). Default to
            // SUCCESS so upgrading doesn't re-run history that was already
            // considered done - EXCEPT where the summary itself records that
            // categories failed. Those rows were the exact blind spot this
            // feature exists to fix: marked "processed" while genuinely
            // incomplete, and therefore skipped forever. Recovering the
            // category names from the summary lets a backfill finish them
            // without regenerating the categories that did succeed.
            List<String> recovered = recoverFailedCategories(summary);
            if (recovered.isEmpty()) {
                status = MergeStatus.SUCCESS;
            } else {
                status = MergeStatus.PARTIAL;
                if (missingCategories.isEmpty()) {
                    missingCategories = recovered;
                }
            }
        }

        attemptCount = Math.max(attemptCount, 1);
        lastAttemptAt = lastAttemptAt == null ? processedAt : lastAttemptAt;
        if (coveragePercent == 0 && status == MergeStatus.SUCCESS && expectedCategories.isEmpty()) {
            coveragePercent = 100;
        }
    }

    private static List<String> recoverFailedCategories(String summary) {
        if (summary == null) {
            return List.of();
        }
        java.util.regex.Matcher m = LEGACY_FAILED_CATEGORIES.matcher(summary);
        if (!m.find()) {
            return List.of();
        }
        return java.util.Arrays.stream(m.group(1).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** Convenience for callers deciding whether to re-attempt this merge. */
    public boolean isIncomplete() {
        return status.isIncomplete();
    }
}
