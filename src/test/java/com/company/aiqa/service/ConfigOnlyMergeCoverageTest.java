package com.company.aiqa.service;

import com.company.aiqa.model.MergeStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * From two mms merges (0dfaf7fe, 4c8ad09e) recorded SUCCESS at 100% coverage
 * holding zero test cases, in a run whose only real LLM call had failed. Both
 * are config-only: no source files, so the four source-diff categories had no
 * batches, the per-batch loop never ran, and they were credited as covered
 * without a call. CONFIGURATION was credited from that same loop while the
 * dedicated config block put it in failedCategories - so one summary named it
 * as both, and isProcessed skipped the merge forever.
 */
class ConfigOnlyMergeCoverageTest {

    private static final List<String> ALL =
            List.of("POSITIVE", "NEGATIVE", "BOUNDARY", "SECURITY", "CONFIGURATION");
    private static final List<String> SOURCE_ONLY =
            List.of("POSITIVE", "NEGATIVE", "BOUNDARY", "SECURITY");

    /** The exact shape of the two stuck merges. */
    @Test
    void aConfigOnlyMergeWhoseConfigCategoryFailedIsNotComplete() {
        QaPipelineService.Coverage coverage = QaPipelineService.coverageOf(
                ALL, SOURCE_ONLY, List.of(), false);

        assertEquals(MergeStatus.FAILED, coverage.status());
        assertEquals(0, coverage.coveragePercent());
        assertEquals(List.of("CONFIGURATION"), coverage.missing());
    }

    /** ...and the same merge, once CONFIGURATION succeeds, really is complete. */
    @Test
    void aConfigOnlyMergeWhoseConfigCategorySucceededIsComplete() {
        QaPipelineService.Coverage coverage = QaPipelineService.coverageOf(
                ALL, SOURCE_ONLY, List.of("CONFIGURATION"), true);

        assertEquals(MergeStatus.SUCCESS, coverage.status());
        assertEquals(100, coverage.coveragePercent());
        assertTrue(coverage.missing().isEmpty());
    }

    /**
     * The other half of the bug: a category with no input must not be reported
     * as missing either, or the merge is retried forever against files that do
     * not exist.
     */
    @Test
    void categoriesWithNoInputAreNeitherCreditedNorMissing() {
        QaPipelineService.Coverage coverage = QaPipelineService.coverageOf(
                ALL, SOURCE_ONLY, List.of("CONFIGURATION"), true);

        assertEquals(List.of("CONFIGURATION"), coverage.applicable());
        for (String sourceCategory : SOURCE_ONLY) {
            assertTrue(!coverage.missing().contains(sourceCategory), sourceCategory + " should not be missing");
            assertTrue(!coverage.applicable().contains(sourceCategory), sourceCategory + " should not be applicable");
        }
    }

    /** An ordinary source merge is unaffected - nothing is ever not-applicable. */
    @Test
    void anOrdinarySourceMergeIsUnchanged() {
        QaPipelineService.Coverage all = QaPipelineService.coverageOf(ALL, List.of(), ALL, true);
        assertEquals(MergeStatus.SUCCESS, all.status());
        assertEquals(100, all.coveragePercent());

        QaPipelineService.Coverage half = QaPipelineService.coverageOf(
                ALL, List.of(), List.of("POSITIVE", "NEGATIVE", "BOUNDARY"), true);
        assertEquals(MergeStatus.PARTIAL, half.status());
        assertEquals(60, half.coveragePercent());
        assertEquals(List.of("SECURITY", "CONFIGURATION"), half.missing());
    }

    /**
     * FAILED is reserved for a run that produced nothing: MergeHistoryService
     * preserves it verbatim, so a productive run marked FAILED is frozen there.
     */
    @Test
    void aRunHoldingCasesIsPartialNotFailed() {
        QaPipelineService.Coverage coverage = QaPipelineService.coverageOf(
                ALL, List.of(), List.of(), true);

        assertEquals(MergeStatus.PARTIAL, coverage.status());
    }

    /** A change that gave no category any input cannot be scored out of zero. */
    @Test
    void nothingApplicableScoresAsComplete() {
        QaPipelineService.Coverage coverage = QaPipelineService.coverageOf(ALL, ALL, List.of(), false);

        assertEquals(100, coverage.coveragePercent());
        assertEquals(MergeStatus.SUCCESS, coverage.status());
    }
}
