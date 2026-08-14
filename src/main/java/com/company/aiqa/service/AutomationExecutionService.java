package com.company.aiqa.service;

import com.company.aiqa.config.PipelineProperties;
import com.company.aiqa.execution.RestAssuredTestExecutionService;
import com.company.aiqa.model.ExecuteAutomationRequest;
import com.company.aiqa.model.ExecuteAutomationResponse;
import com.company.aiqa.model.TestCaseResult;
import com.company.aiqa.model.TestExecutionSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs automation that a previous generation pass already produced for a commit.
 *
 * <p>Execution is a separate endpoint from generation, not a flag on it,
 * because the two do fundamentally different things. Generation costs LLM
 * tokens, needs a key, and touches nothing outside this platform's own output
 * folder. Execution costs nothing, needs no credential, and fires real
 * POST/PUT/DELETE traffic at a live system. Folding the dangerous one into the
 * safe one as an option meant every generation request was one mistyped
 * boolean away from mutating whatever baseUri pointed at.
 *
 * <p>Nothing is generated, cloned or fetched here: if a commit has no scripts
 * on disk, that is reported as such rather than quietly producing some.
 */
@Service
public class AutomationExecutionService {

    private static final Logger log = LoggerFactory.getLogger(AutomationExecutionService.class);

    private static final String DEFAULT_BASE_URI = "http://localhost:8080";

    /** Where TestNG's reports and the captured traffic are written, inside the commit's folder. */
    private static final String REPORT_DIR_NAME = "test-report";

    /**
     * Generation merges a commit's automation into one file with this prefix.
     * Preferring it over "every .java in the folder" matters: earlier runs left
     * per-batch scripts behind, and some folders still hold files that predate
     * the current generator and no longer compile. Running the merged file is
     * both what the user means and what actually builds.
     */
    private static final String MERGED_SCRIPT_PREFIX = "AutomationTest_";

    private final PipelineProperties pipelineProperties;
    private final RestAssuredTestExecutionService executionService;
    private final GeneratedRunLocator runLocator;

    public AutomationExecutionService(PipelineProperties pipelineProperties,
                                      RestAssuredTestExecutionService executionService,
                                      GeneratedRunLocator runLocator) {
        this.pipelineProperties = pipelineProperties;
        this.executionService = executionService;
        this.runLocator = runLocator;
    }

    public ExecuteAutomationResponse execute(ExecuteAutomationRequest request) {
        String projectName = runLocator.resolveProjectName(request.getProjectName(), request.getRepoUrl());
        String baseOutputDir = request.getOutputDir() != null && !request.getOutputDir().isBlank()
                ? request.getOutputDir()
                : pipelineProperties.getOutputDir();
        String runOutputDir = runLocator.runOutputDir(baseOutputDir, projectName, request.getCommitHash());

        if (!Files.isDirectory(Path.of(runOutputDir))) {
            throw new IllegalArgumentException(
                    "No generated-tests folder for commit %s at %s. Generate automation for this commit first: POST /api/v1/generate-automation."
                            .formatted(request.getCommitHash(), runOutputDir));
        }

        List<TestCaseResult> scripts = loadScripts(runOutputDir, request.getScriptFileName());
        if (scripts.isEmpty()) {
            throw new IllegalArgumentException(
                    ("No automation script found in %s. This endpoint only RUNS scripts that already exist - "
                            + "generate them first with POST /api/v1/generate-automation.")
                            .formatted(runOutputDir));
        }

        String baseUri = request.getBaseUri() != null && !request.getBaseUri().isBlank()
                ? request.getBaseUri()
                : DEFAULT_BASE_URI;

        List<String> fileNames = scripts.stream().map(TestCaseResult::testFileName).toList();
        log.info("Executing {} script(s) for commit {} against {}: {}",
                scripts.size(), request.getCommitHash(), baseUri, fileNames);

        TestExecutionSummary execution = executionService.execute(
                scripts, baseUri, Path.of(runOutputDir, REPORT_DIR_NAME));

        String summary = ("Commit %s: ran %d script file(s) against %s - %d test(s): %d passed, %d failed, "
                + "%d error(s), %d skipped (%d/%d script(s) compiled).")
                .formatted(request.getCommitHash(), scripts.size(), baseUri,
                        execution.totalTests(), execution.passed(), execution.failed(),
                        execution.errorCount(), execution.skipped(),
                        execution.scriptsCompiled(), execution.scriptsAttempted());
        if (!execution.errors().isEmpty()) {
            summary += " Compile/run issues: %d (see testExecutionSummary.errors).".formatted(execution.errors().size());
        }
        if (execution.reportPath() != null) {
            summary += " TestNG report: %s.".formatted(execution.reportPath());
        }
        log.info(summary);

        return new ExecuteAutomationResponse(projectName, request.getCommitHash(), fileNames,
                runOutputDir, execution, summary);
    }

    /**
     * Reads the scripts to run off disk.
     *
     * <p>An explicit scriptFileName wins. Otherwise this takes the merged
     * {@code AutomationTest_*} file(s) only - see MERGED_SCRIPT_PREFIX for why
     * "everything ending in .java" is the wrong default.
     */
    private List<TestCaseResult> loadScripts(String runOutputDir, String scriptFileName) {
        Path dir = Path.of(runOutputDir);

        if (scriptFileName != null && !scriptFileName.isBlank()) {
            Path explicit = dir.resolve(scriptFileName.trim());
            if (!Files.isRegularFile(explicit)) {
                throw new IllegalArgumentException("No such script in %s: %s".formatted(runOutputDir, scriptFileName));
            }
            return List.of(readScript(explicit));
        }

        List<TestCaseResult> scripts = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> merged = files
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(MERGED_SCRIPT_PREFIX) && name.endsWith(".java");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            for (Path file : merged) {
                scripts.add(readScript(file));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not list %s: %s".formatted(runOutputDir, e.getMessage()), e);
        }
        return scripts;
    }

    private TestCaseResult readScript(Path file) {
        try {
            String fileName = file.getFileName().toString();
            String className = fileName.endsWith(".java")
                    ? fileName.substring(0, fileName.length() - 5)
                    : fileName;
            return new TestCaseResult(className, fileName,
                    Files.readString(file, StandardCharsets.UTF_8), file.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new IllegalStateException("Could not read %s: %s".formatted(file, e.getMessage()), e);
        }
    }
}
