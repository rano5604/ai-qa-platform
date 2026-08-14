package com.company.aiqa.service;

import com.company.aiqa.config.PipelineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serves generated test cases back to the caller as a downloadable file.
 *
 * <p>The pipeline writes its CSV to disk and the response only ever carried
 * {@code manualTestCasesCsvPath} - a path on the SERVER's filesystem, which is
 * useless to anyone calling the API from another machine. This turns that path
 * into bytes the caller can actually retrieve.
 *
 * <p>Resolution is delegated to {@link GeneratedRunLocator} rather than
 * recomputed here, so a download looks in exactly the folder generation wrote
 * to - including the {@code <seq>.<hash>} shape the backfill produces.
 */
@Service
public class TestCaseDownloadService {

    private static final Logger log = LoggerFactory.getLogger(TestCaseDownloadService.class);

    /** Per-commit CSV, written by QaPipelineService for a single run. */
    public static final String PER_COMMIT_CSV = "manual_test_cases.csv";

    /** Project-level rollup of every commit's cases, maintained across runs. */
    public static final String ALL_CASES_CSV = "all_manual_test_cases.csv";

    private final PipelineProperties pipelineProperties;
    private final GeneratedRunLocator runLocator;

    public TestCaseDownloadService(PipelineProperties pipelineProperties, GeneratedRunLocator runLocator) {
        this.pipelineProperties = pipelineProperties;
        this.runLocator = runLocator;
    }

    /**
     * Reads one commit's generated test cases, or the whole project's rollup
     * when {@code commitHash} is null/blank.
     *
     * @throws IllegalArgumentException if the file does not exist, so the
     *         controller can answer 404 instead of leaking a stack trace
     */
    public Download load(String projectName, String repoUrl, String commitHash) {
        String project = runLocator.resolveProjectName(projectName, repoUrl);
        Path baseDir = Path.of(pipelineProperties.getOutputDir()).toAbsolutePath().normalize();

        Path file;
        String downloadName;
        if (commitHash == null || commitHash.isBlank()) {
            file = baseDir.resolve(project).resolve(ALL_CASES_CSV);
            downloadName = project + "_" + ALL_CASES_CSV;
        } else {
            String runDir = runLocator.runOutputDir(baseDir.toString(), project, commitHash);
            file = Path.of(runDir).resolve(PER_COMMIT_CSV);
            downloadName = project + "_" + runLocator.sanitizeForPath(commitHash) + "_" + PER_COMMIT_CSV;
        }

        Path resolved = file.toAbsolutePath().normalize();

        // resolveProjectName hands back a caller-supplied projectName verbatim,
        // and its fallback regex keeps '.' and '-', so ".." survives sanitizing.
        // Without this check "projectName=../../.." would read any file on the
        // host the process can see. Compare AFTER normalize so the traversal is
        // already collapsed.
        if (!resolved.startsWith(baseDir)) {
            log.warn("Rejected download outside {}: projectName='{}' resolved to {}", baseDir, projectName, resolved);
            throw new IllegalArgumentException("Invalid projectName.");
        }

        if (!Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException(
                    "No generated test cases found at " + baseDir.relativize(resolved)
                            + ". Run test generation for this project/commit first.");
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
