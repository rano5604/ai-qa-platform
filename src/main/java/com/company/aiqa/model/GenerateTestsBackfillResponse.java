package com.company.aiqa.model;

import java.util.List;

/**
 * Response for POST /api/v1/generate-tests-backfill: walks every merge
 * commit reachable from the requested branch, skips the ones already in
 * this repo+branch's merge history, runs the pipeline against the rest
 * (oldest first), and records each as processed. Safe to call repeatedly -
 * once a branch is fully covered, subsequent calls are a no-op (report
 * everything as "already processed") until a new merge lands.
 *
 * caughtUp is true only if every merge that needed processing succeeded
 * (failed == 0). If any merge failed, caughtUp is false and those merges
 * were NOT recorded as processed - rerun this same call to retry just
 * the failures; everything already-successful is skipped as usual.
 */
public record GenerateTestsBackfillResponse(
        int totalMergesOnBranch,
        int alreadyProcessed,
        int newlyProcessed,
        int failed,
        List<MergeRunResult> results,
        boolean caughtUp,
        String summary
) {
}
