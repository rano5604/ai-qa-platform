package com.company.aiqa.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A direct POST /generate-tests for a commit backfill had already produced -
 * made with no commitSequence, or the wrong one - used to create a SIBLING
 * folder instead of finding the real one, so a resume's onlyCategories merged
 * into an empty folder instead of the one holding the earlier attempt's
 * output. QaPipelineService.resolveRunOutputDir is the fix: an existing
 * folder always wins, whatever shape it is; commitSequence only shapes a
 * FRESH folder's name.
 */
class RunOutputDirResolutionTest {

    @Test
    void reusesAnExistingFolderVerbatim() {
        String result = QaPipelineService.resolveRunOutputDir(
                true, "generated-tests/mms/3.abc123", "generated-tests/mms", "abc123", 99);

        assertEquals("generated-tests/mms/3.abc123", result);
    }

    /** Existing wins even when commitSequence disagrees with the folder it already has. */
    @Test
    void existingFolderWinsOverAMismatchedCommitSequence() {
        String result = QaPipelineService.resolveRunOutputDir(
                true, "generated-tests/mms/3.abc123", "generated-tests/mms", "abc123", 7);

        assertEquals("generated-tests/mms/3.abc123", result);
    }

    /** Existing wins even when it's the bare-hash shape, not a "<seq>.<hash>" one. */
    @Test
    void reusesABareHashFolderTooWithoutMintingASequencedSibling() {
        String result = QaPipelineService.resolveRunOutputDir(
                true, "generated-tests/mms/abc123", "generated-tests/mms", "abc123", 5);

        assertEquals("generated-tests/mms/abc123", result);
    }

    @Test
    void mintsASequencedFolderOnFirstGeneration() {
        String result = QaPipelineService.resolveRunOutputDir(
                false, "generated-tests/mms/abc123", "generated-tests/mms", "abc123", 5);

        assertEquals(Path.of("generated-tests/mms/5.abc123").toString(), result);
    }

    @Test
    void mintsABareHashFolderWhenNoSequenceWasGiven() {
        String result = QaPipelineService.resolveRunOutputDir(
                false, "generated-tests/mms/abc123", "generated-tests/mms", "abc123", null);

        assertEquals(Path.of("generated-tests/mms/abc123").toString(), result);
    }
}
