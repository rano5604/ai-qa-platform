package com.company.aiqa.service;

import com.company.aiqa.config.PipelineProperties;
import com.company.aiqa.error.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Execution writes aiqa-report.html to the SERVER's disk and, until this
 * endpoint existed, only ever returned that local path - useless to a caller
 * on another machine. Mirrors the traversal guard and not-found behaviour
 * TestCaseDownloadService already established for the CSV download.
 */
class ExecutionReportDownloadServiceTest {

    private static final String COMMIT = "abc123abc123abc123abc123abc123abc123ab1";
    private static final byte[] REPORT_BYTES = "<html><body>report</body></html>".getBytes();

    @Test
    void readsTheReportFromTheCommitsRunFolder(@TempDir Path tmp) throws IOException {
        writeReportAt(tmp, "QueueManagement", "3." + COMMIT);

        ExecutionReportDownloadService service = serviceOver(tmp);
        ExecutionReportDownloadService.Download download =
                service.load("QueueManagement", null, COMMIT);

        assertArrayEquals(REPORT_BYTES, download.content());
        assertTrue(download.fileName().contains(COMMIT), download.fileName());
        assertTrue(download.fileName().endsWith("aiqa-report.html"), download.fileName());
    }

    /** GeneratedRunLocator accepts both the sequenced and bare-hash shapes - so must this. */
    @Test
    void findsTheReportUnderTheBareHashFolderToo(@TempDir Path tmp) throws IOException {
        writeReportAt(tmp, "mms", COMMIT);

        ExecutionReportDownloadService.Download download =
                serviceOver(tmp).load("mms", null, COMMIT);

        assertArrayEquals(REPORT_BYTES, download.content());
    }

    /**
     * A blank/null commitHash falls back to the project-wide run's own report -
     * see AutomationExecutionService.executeForProject, which writes there
     * rather than into a commit subfolder.
     */
    @Test
    void blankCommitHashFallsBackToTheProjectWideReport(@TempDir Path tmp) throws IOException {
        Path reportDir = tmp.resolve("mms").resolve("test-report");
        Files.createDirectories(reportDir);
        Files.write(reportDir.resolve("aiqa-report.html"), REPORT_BYTES);

        ExecutionReportDownloadService service = serviceOver(tmp);

        assertArrayEquals(REPORT_BYTES, service.load("mms", null, null).content());
        assertArrayEquals(REPORT_BYTES, service.load("mms", null, "  ").content());
    }

    @Test
    void reportsNotFoundForTheProjectWideReportWhenExecutionHasNeverRun(@TempDir Path tmp) {
        ExecutionReportDownloadService service = serviceOver(tmp);

        NotFoundException e = assertThrows(NotFoundException.class, () -> service.load("mms", null, null));

        assertTrue(e.getMessage().contains("execute-automation-for-project"), e.getMessage());
    }

    @Test
    void reportsNotFoundWhenExecutionHasNeverRun(@TempDir Path tmp) {
        ExecutionReportDownloadService service = serviceOver(tmp);

        NotFoundException e = assertThrows(NotFoundException.class,
                () -> service.load("mms", null, COMMIT));

        assertTrue(e.getMessage().contains("execute-automation"), e.getMessage());
    }

    /**
     * resolveProjectName's fallback regex keeps '.' and '-', so a bare ".."
     * survives it - the same hole TestCaseDownloadServiceTest would cover if
     * one existed. Checked here because a report download reads a second file
     * type off the same disk and must not reopen it.
     */
    @Test
    void rejectsATraversingProjectName(@TempDir Path tmp) {
        ExecutionReportDownloadService service = serviceOver(tmp);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.load("../../../../etc", null, COMMIT));

        assertEquals("Invalid projectName.", e.getMessage());
    }

    private void writeReportAt(Path outputDir, String project, String runFolder) throws IOException {
        Path reportDir = outputDir.resolve(project).resolve(runFolder).resolve("test-report");
        Files.createDirectories(reportDir);
        Files.write(reportDir.resolve("aiqa-report.html"), REPORT_BYTES);
    }

    private ExecutionReportDownloadService serviceOver(Path outputDir) {
        PipelineProperties properties = new PipelineProperties();
        properties.setOutputDir(outputDir.toString());
        return new ExecutionReportDownloadService(properties, new GeneratedRunLocator());
    }
}
