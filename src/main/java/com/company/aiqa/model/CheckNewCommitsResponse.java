package com.company.aiqa.model;

import java.util.List;

/**
 * Result of POST /api/v1/generate-tests-from-branch/check-new-commits.
 *
 * <p>Deliberately thin - this is meant to answer "is a backfill worth
 * running" before spending anything on one, not to replace it. For detail on
 * WHY a specific merge is incomplete, use GET /api/v1/merge-history/incomplete
 * once you already know there's something to look at.
 */
public record CheckNewCommitsResponse(
        String projectName,
        String repoUrl,
        String branch,

        /** Every merge commit reachable from branch, oldest first - same walk the backfill itself uses. */
        int totalMerges,

        /** How many of those are already recorded (SUCCESS, PARTIAL, or FAILED - anything "not new"). */
        int alreadyProcessed,

        /** Merge SHAs with no history entry at all, oldest first - what a backfill would pick up as brand new. */
        List<String> newCommitShas,

        /** True when newCommitShas is non-empty. Convenience for callers that only want a yes/no. */
        boolean hasNewCommits,

        String summary
) {
}
