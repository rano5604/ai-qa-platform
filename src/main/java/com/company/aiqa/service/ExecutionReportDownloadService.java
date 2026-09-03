package com.company.aiqa.service;

import com.company.aiqa.config.PipelineProperties;
import com.company.aiqa.error.NotFoundException;
import com.company.aiqa.execution.ExecutionReportWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serves a commit's {@code aiqa-report.html} back to the caller as a
 * downloadable file.
 *
 * <p>Execution writes the report - request/response evidence alongside every
 * verdict, see {@link ExecutionReportWriter} - to the SERVER's disk, and until
 * now the only way to see it was to already have a filesystem on that server:
 * {@code testExecutionSummary.reportPath} names a local path, of no use to a
 * caller on another machine. Mirrors {@link TestCaseDownloadService}'s
 * approach for the same reason - resolution is delegated to
 * {@link GeneratedRunLocator} so a download looks in exactly the folder
 * execution wrote to.
 */
@Service
public class ExecutionReportDownloadService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionReportDownloadService.class);

    private final PipelineProperties pipelineProperties;
    private final GeneratedRunLocator runLocator;

    public ExecutionReportDownloadService(PipelineProperties pipelineProperties, GeneratedRunLocator runLocator) {
        this.pipelineProperties = pipelineProperties;
        this.runLocator = runLocator;
    }

    /**
     * Reads one commit's execution report, or the project-wide run's report
     * when {@code commitHash} is null/blank - see
     * {@link AutomationExecutionService#executeForProject}, which writes its
     * report to the project's own root rather than a commit subfolder.
     *
     * @throws IllegalArgumentException if the resolved path escapes the output directory
     * @throws NotFoundException if no report exists yet at the resolved location
     */
    public Download load(String projectName, String repoUrl, String commitHash) {
        String project = runLocator.resolveProjectName(projectName, repoUrl);
        Path baseDir = Path.of(pipelineProperties.getOutputDir()).toAbsolutePath().normalize();

        Path file;
        String downloadName;
        if (commitHash == null || commitHash.isBlank()) {
            file = baseDir.resolve(project).resolve(AutomationExecutionService.REPORT_DIR_NAME)
                    .resolve(ExecutionReportWriter.REPORT_FILE);
            downloadName = project + "_" + ExecutionReportWriter.REPORT_FILE;
        } else {
            String runDir = runLocator.runOutputDir(baseDir.toString(), project, commitHash);
            file = Path.of(runDir, AutomationExecutionService.REPORT_DIR_NAME, ExecutionReportWriter.REPORT_FILE);
            downloadName = project + "_" + runLocator.sanitizeForPath(commitHash) + "_"
                    + ExecutionReportWriter.REPORT_FILE;
        }
        Path resolved = file.toAbsolutePath().normalize();

        // Same traversal guard as TestCaseDownloadService, for the same reason:
        // resolveProjectName hands a caller-supplied projectName back verbatim,
        // and its fallback regex keeps '.' and '-', so ".." survives sanitizing.
        // Checked AFTER normalize so the traversal is already collapsed.
        if (!resolved.startsWith(baseDir)) {
            log.warn("Rejected report download outside {}: projectName='{}' resolved to {}",
                    baseDir, projectName, resolved);
            throw new IllegalArgumentException("Invalid projectName.");
        }

        if (!Files.isRegularFile(resolved)) {
            String runIt = (commitHash == null || commitHash.isBlank())
                    ? "POST /api/v1/execute-automation-for-project"
                    : "POST /api/v1/execute-automation for this commit";
            throw new NotFoundException(
                    "No execution report found at " + baseDir.relativize(resolved) + ". Run " + runIt + " first.");
        }

        try {
            return new Download(downloadName, Files.readAllBytes(resolved));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + resolved, e);
        }
    }

    /** A file's download name and its bytes. */
    public record Download(String fileName, byte[] content) {
    }
}
