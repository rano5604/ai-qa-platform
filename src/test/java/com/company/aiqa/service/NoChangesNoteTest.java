package com.company.aiqa.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A commit that changes nothing testable used to produce no folder at all, so
 * the sequence numbers under generated-tests/&lt;project&gt;/ had holes -
 * QueueManagement was missing 4, 5, 19 and 20 - and a hole reads exactly like
 * a commit that failed or was never processed. The folder now exists and says
 * which it was.
 */
class NoChangesNoteTest {

    private static final String MESSAGE =
            "No changed source or configuration files found between abc123 and def456.";

    @Test
    void createsTheRunFolderAndWritesTheMessage(@TempDir Path tmp) throws IOException {
        Path runDir = tmp.resolve("QueueManagement").resolve("4.6438de09eec8");

        QaPipelineService.writeRunNote(runDir.toString(), MESSAGE);

        Path note = runDir.resolve("no-test-cases.txt");
        assertTrue(Files.exists(note), "the note should exist at " + note);
        assertEquals(MESSAGE, Files.readString(note).strip());
    }

    /** Parent directories do not exist yet on a project's first empty commit. */
    @Test
    void createsMissingParentDirectories(@TempDir Path tmp) {
        Path runDir = tmp.resolve("brand-new").resolve("project").resolve("1.abc");

        QaPipelineService.writeRunNote(runDir.toString(), MESSAGE);

        assertTrue(Files.exists(runDir.resolve("no-test-cases.txt")));
    }

    /**
     * The whole point of the note is that it is optional. This path has already
     * succeeded with nothing to record, so an unwritable folder must not turn a
     * complete merge into a failed one that every later backfill retries.
     */
    @Test
    void neverThrowsWhenThePathCannotBeWritten(@TempDir Path tmp) throws IOException {
        // A regular file where the run folder should be: createDirectories fails.
        Path blocked = tmp.resolve("blocked");
        Files.writeString(blocked, "not a directory");

        assertDoesNotThrow(() ->
                QaPipelineService.writeRunNote(blocked.resolve("1.abc").toString(), MESSAGE));
    }

    /** Rewriting an existing note replaces it rather than appending. */
    @Test
    void overwritesAnEarlierNote(@TempDir Path tmp) throws IOException {
        Path runDir = tmp.resolve("2.abc");
        QaPipelineService.writeRunNote(runDir.toString(), "stale text");

        QaPipelineService.writeRunNote(runDir.toString(), MESSAGE);

        assertEquals(MESSAGE, Files.readString(runDir.resolve("no-test-cases.txt")).strip());
    }
}
