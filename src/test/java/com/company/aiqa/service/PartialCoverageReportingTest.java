package com.company.aiqa.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * From the 2026-08-19 backfill of the mms initial commit, whose response said
 * "2 categories failed and produced no test cases: [NEGATIVE, BOUNDARY]" while
 * returning five Negative cases in the same object.
 */
class PartialCoverageReportingTest {

    // ---------------------------------------------------------- warnings ----

    @Test
    void aCategoryThatProducedCasesIsNotReportedAsProducingNone() {
        String warnings = QaPipelineService.categoryWarnings(
                List.of("NEGATIVE", "BOUNDARY"), List.of("NEGATIVE", "BOUNDARY"), 5);

        assertFalse(warnings.contains("produced no test cases"), warnings);
        assertTrue(warnings.contains("incomplete"), warnings);
        assertTrue(warnings.contains("5 case(s) were generated before a batch failed"), warnings);
    }

    @Test
    void aCategoryThatTrulyProducedNothingStillSaysSo() {
        String warnings = QaPipelineService.categoryWarnings(List.of("SECURITY"), List.of(), 0);

        assertTrue(warnings.contains("1 category failed and produced no test cases: [SECURITY]"), warnings);
        assertFalse(warnings.contains("incomplete"), warnings);
    }

    /** A run can have both, and each has to be reported as what it is. */
    @Test
    void reportsTheTwoOutcomesSeparately() {
        String warnings = QaPipelineService.categoryWarnings(
                List.of("NEGATIVE", "SECURITY"), List.of("NEGATIVE"), 5);

        assertTrue(warnings.contains("produced no test cases: [SECURITY]"), warnings);
        assertTrue(warnings.contains("incomplete"), warnings);
        assertTrue(warnings.contains("[NEGATIVE]"), warnings);
    }

    @Test
    void saysNothingWhenEveryCategoryCompleted() {
        assertEquals("", QaPipelineService.categoryWarnings(List.of(), List.of(), 0));
    }

    // ------------------------------------------------------------ shrink ----

    /**
     * Splitting shrinks the diff, never the collaborator source that rides on
     * every call - so a run whose fixed context alone exceeds the ceiling fails
     * identically at 40 files and at 1. This is what gives way at that point.
     */
    @Test
    void halvesTheContextOnALineBoundary() {
        String source = ("line of implementation source\n").repeat(1000);   // ~30k chars
        AtomicReference<String> context = new AtomicReference<>(source);

        assertTrue(QaPipelineService.shrinkContext(context, "batch 1/3"));

        assertTrue(context.get().length() < source.length() / 2 + 40, context.get().length() + "");
        assertTrue(context.get().length() > 4_000, "should not have collapsed straight to empty");
        assertTrue(context.get().endsWith("source"), "cut mid-line: ..." + tail(context.get()));
    }

    /** Below the floor, half a method body helps nobody - drop it entirely. */
    @Test
    void dropsTheContextRatherThanLeavingAFragment() {
        AtomicReference<String> context = new AtomicReference<>("x".repeat(5_000));

        assertTrue(QaPipelineService.shrinkContext(context, "batch 1/3"));

        assertEquals("", context.get());
    }

    /** Nothing left to give up is the signal to report the batch as failed. */
    @Test
    void refusesOnceThereIsNothingLeft() {
        assertFalse(QaPipelineService.shrinkContext(new AtomicReference<>(""), "batch 1/3"));
        assertFalse(QaPipelineService.shrinkContext(new AtomicReference<>(null), "batch 1/3"));
        assertFalse(QaPipelineService.shrinkContext(null, "batch 1/3"));
    }

    /** Repeated shrinking terminates rather than halving forever. */
    @Test
    void terminates() {
        AtomicReference<String> context = new AtomicReference<>(("some source line\n").repeat(5000));
        int shrinks = 0;
        while (QaPipelineService.shrinkContext(context, "batch 1/3") && shrinks < 100) {
            shrinks++;
        }
        assertEquals("", context.get());
        assertTrue(shrinks < 20, "took " + shrinks + " shrinks to bottom out");
    }

    private static String tail(String s) {
        return s.length() < 30 ? s : s.substring(s.length() - 30);
    }
}
