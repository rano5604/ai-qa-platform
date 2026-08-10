package com.company.aiqa.model;

/**
 * Outcome of running one merge through the pipeline.
 *
 * This replaces the old binary "recorded = done" assumption in
 * MergeHistoryService: a merge is only skipped on a later backfill when it
 * reached {@link #SUCCESS}. PARTIAL and FAILED merges stay eligible for
 * re-attempt, which is what lets the platform automatically find and finish
 * incomplete generation instead of silently leaving gaps.
 */
public enum MergeStatus {

    /** Every expected test-case category was generated - nothing left to do. */
    SUCCESS,

    /**
     * Some categories generated, some missing - typically because individual
     * per-category LLM calls failed (every candidate model exhausted, a
     * malformed response, a timeout) while others succeeded. A re-attempt
     * regenerates ONLY the missing categories; completed work is never redone.
     */
    PARTIAL,

    /**
     * The run produced nothing usable - it threw before any category
     * completed, or every category failed. A re-attempt starts the merge over.
     */
    FAILED;

    /** True when this merge still has work outstanding. */
    public boolean isIncomplete() {
        return this != SUCCESS;
    }
}
