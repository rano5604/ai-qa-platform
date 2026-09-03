package com.company.aiqa.service;

import com.company.aiqa.auth.AuthTokenResolver;
import com.company.aiqa.config.GitProperties;
import com.company.aiqa.config.PipelineProperties;
import com.company.aiqa.error.NotFoundException;
import com.company.aiqa.git.GitDiffService;
import com.company.aiqa.model.AuthLoginSpec;
import com.company.aiqa.execution.ExecutionReportWriter;
import com.company.aiqa.execution.RunDiagnosis;
import com.company.aiqa.execution.RestAssuredTestExecutionService;
import com.company.aiqa.model.ExecuteAutomationForProjectRequest;
import com.company.aiqa.model.ExecuteAutomationForProjectResponse;
import com.company.aiqa.model.ExecuteAutomationRequest;
import com.company.aiqa.model.ExecuteAutomationResponse;
import com.company.aiqa.model.TestCaseResult;
import com.company.aiqa.model.TestExecutionSummary;
import com.company.aiqa.replay.ReplayProperties;
import com.company.aiqa.replay.ReplayService;
import com.company.aiqa.testcase.AutomationScriptMerger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * Subfolder under a commit's run folder holding both TestNG's own report
     * and {@link ExecutionReportWriter#REPORT_FILE}. Public so
     * ExecutionReportDownloadService resolves the exact same path this class
     * writes to, rather than a second hardcoded copy drifting from it - the
     * same reasoning GeneratedRunLocator's javadoc gives for sharing folder
     * arithmetic between generation and execution.
     */
    public static final String REPORT_DIR_NAME = "test-report";

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
    private final AutomationScriptMerger scriptMerger;
    private final ExecutionReportWriter reportWriter;
    private final AuthTokenResolver authTokenResolver;
    private final GitProperties gitProperties;
    private final GitDiffService gitDiffService;

    public AutomationExecutionService(PipelineProperties pipelineProperties,
                                      RestAssuredTestExecutionService executionService,
                                      GeneratedRunLocator runLocator,
                                      AutomationScriptMerger scriptMerger,
                                      ReplayService replayService,
                                      ReplayProperties replayProperties,
                                      AuthTokenResolver authTokenResolver,
                                      GitProperties gitProperties,
                                      GitDiffService gitDiffService,
                                      @Value("${server.port:8080}") int serverPort) {
        this.pipelineProperties = pipelineProperties;
        this.executionService = executionService;
        this.runLocator = runLocator;
        this.scriptMerger = scriptMerger;
        this.authTokenResolver = authTokenResolver;
        this.gitProperties = gitProperties;
        this.gitDiffService = gitDiffService;
        // The report is read on the reader's machine, so it needs an
        // absolute address for this platform - and localhost is right for
        // the normal case of both being the same machine. Override with
        // aiqa.report.replay.platform-base-url when they are not.
        String platform = replayProperties.getPlatformBaseUrl();
        if (platform == null || platform.isBlank()) {
            platform = "http://localhost:" + serverPort;
        }
        this.reportWriter = replayProperties.isEnabled()
                ? new ExecutionReportWriter(platform.replaceAll("/+$", "") + "/api/v1/replay",
                        replayService.token())
                : new ExecutionReportWriter();
    }

    public ExecuteAutomationResponse execute(ExecuteAutomationRequest request) {
        String projectName = runLocator.resolveProjectName(request.getProjectName(), request.getRepoUrl());
        String baseOutputDir = request.getOutputDir() != null && !request.getOutputDir().isBlank()
                ? request.getOutputDir()
                : pipelineProperties.getOutputDir();
        String runOutputDir = runLocator.runOutputDir(baseOutputDir, projectName, request.getCommitHash());

        if (!Files.isDirectory(Path.of(runOutputDir))) {
            throw new NotFoundException(
                    "No generated-tests folder for commit %s at %s. Generate automation for this commit first: POST /api/v1/generate-automation."
                            .formatted(request.getCommitHash(), runOutputDir));
        }

        List<TestCaseResult> scripts = loadScripts(runOutputDir, request.getScriptFileName());
        if (scripts.isEmpty()) {
            throw new NotFoundException(
                    ("No automation script found in %s. This endpoint only RUNS scripts that already exist - "
                            + "generate them first with POST /api/v1/generate-automation.")
                            .formatted(runOutputDir));
        }

        String baseUri = request.getBaseUri() != null && !request.getBaseUri().isBlank()
                ? request.getBaseUri()
                : DEFAULT_BASE_URI;

        // Scripts written before the merger guaranteed this have no setup at
        // all, so REST Assured would use its own default and quietly ignore the
        // target this call names - see AutomationScriptMerger.withBaseUriHonoured.
        scripts = scripts.stream().map(scriptMerger::withBaseUriHonoured)
                .map(scriptMerger::withCompileSafetyRepairs).toList();

        List<String> fileNames = scripts.stream().map(TestCaseResult::testFileName).toList();
        log.info("Executing {} script(s) for commit {} against {}: {}",
                scripts.size(), request.getCommitHash(), baseUri, fileNames);

        Path reportDir = Path.of(runOutputDir, REPORT_DIR_NAME);
        TestExecutionSummary execution = executionService.execute(scripts, baseUri, reportDir,
                effectiveAuthHeaders(request.getAuthHeaders(), request.getLogin(), projectName, baseUri));

        // TestNG's own report shows verdicts only - the captured traffic sits
        // beside it in http-exchanges.json with nothing joining the two. This
        // writes the report a reviewer can actually triage from: each verdict
        // with the exact request sent and response received underneath it.
        Path evidenceReport = reportWriter.write(execution, reportDir, projectName, request.getCommitHash());

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
        if (evidenceReport != null) {
            summary += " Report with request/response evidence: %s.".formatted(evidenceReport.toAbsolutePath());
        }
        // Whether these verdicts can be read as defects at all - a run whose
        // tests all died setting up their fixtures proves nothing about the
        // rules they name, and that has to reach the caller, not just the HTML.
        for (String finding : RunDiagnosis.of(execution)) {
            summary += " WARNING: " + finding;
        }
        log.info(summary);

        return new ExecuteAutomationResponse(projectName, request.getCommitHash(), fileNames,
                runOutputDir, execution, downloadUrlFor(projectName, request.getCommitHash(), evidenceReport), summary);
    }

    /**
     * The project-wide counterpart to {@link #execute}: runs the merged script
     * POST /api/v1/generate-automation-for-project wrote to the project's own
     * root folder, rather than a commit's subfolder - so this needs only
     * {@link ExecuteAutomationForProjectRequest#getProjectName()}, never a
     * commit hash. Everything else - script discovery, base-URI repair,
     * compile+run, the evidence report - is identical to {@link #execute}.
     */
    public ExecuteAutomationForProjectResponse executeForProject(ExecuteAutomationForProjectRequest request) {
        String projectName = request.getProjectName().trim();
        String baseOutputDir = request.getOutputDir() != null && !request.getOutputDir().isBlank()
                ? request.getOutputDir()
                : pipelineProperties.getOutputDir();
        String projectOutputDir = Path.of(baseOutputDir, projectName).toString();

        if (!Files.isDirectory(Path.of(projectOutputDir))) {
            throw new NotFoundException(
                    ("No generated-tests folder for project '%s' at %s. Generate automation for it first: "
                            + "POST /api/v1/generate-automation-for-project.")
                            .formatted(projectName, projectOutputDir));
        }

        List<TestCaseResult> scripts = loadScripts(projectOutputDir, request.getScriptFileName());
        if (scripts.isEmpty()) {
            throw new NotFoundException(
                    ("No automation script found in %s. This endpoint only RUNS scripts that already exist - "
                            + "generate them first with POST /api/v1/generate-automation-for-project.")
                            .formatted(projectOutputDir));
        }

        String baseUri = request.getBaseUri() != null && !request.getBaseUri().isBlank()
                ? request.getBaseUri()
                : DEFAULT_BASE_URI;

        scripts = scripts.stream().map(scriptMerger::withBaseUriHonoured)
                .map(scriptMerger::withCompileSafetyRepairs).toList();

        List<String> fileNames = scripts.stream().map(TestCaseResult::testFileName).toList();
        log.info("Executing {} script(s) for project {} against {}: {}",
                scripts.size(), projectName, baseUri, fileNames);

        Path reportDir = Path.of(projectOutputDir, REPORT_DIR_NAME);
        TestExecutionSummary execution = executionService.execute(scripts, baseUri, reportDir,
                effectiveAuthHeaders(request.getAuthHeaders(), request.getLogin(), projectName, baseUri));

        // "all (project rollup)" rather than a real commit hash - this run
        // covers every case the project has ever accumulated, not one commit,
        // and the report's Commit field should say so rather than naming
        // something that would misleadingly look like a real commit.
        Path evidenceReport = reportWriter.write(execution, reportDir, projectName, "all (project rollup)");

        String summary = ("Project %s: ran %d script file(s) against %s - %d test(s): %d passed, %d failed, "
                + "%d error(s), %d skipped (%d/%d script(s) compiled).")
                .formatted(projectName, scripts.size(), baseUri,
                        execution.totalTests(), execution.passed(), execution.failed(),
                        execution.errorCount(), execution.skipped(),
                        execution.scriptsCompiled(), execution.scriptsAttempted());
        if (!execution.errors().isEmpty()) {
            summary += " Compile/run issues: %d (see testExecutionSummary.errors).".formatted(execution.errors().size());
        }
        if (execution.reportPath() != null) {
            summary += " TestNG report: %s.".formatted(execution.reportPath());
        }
        if (evidenceReport != null) {
            summary += " Report with request/response evidence: %s.".formatted(evidenceReport.toAbsolutePath());
        }
        for (String finding : RunDiagnosis.of(execution)) {
            summary += " WARNING: " + finding;
        }
        log.info(summary);

        return new ExecuteAutomationForProjectResponse(projectName, fileNames, projectOutputDir, execution,
                downloadUrlFor(projectName, null, evidenceReport), summary);
    }

    /**
     * The headers to inject on every request, combining a login-derived token
     * (if a {@code login} spec was supplied) with any static {@code authHeaders}.
     *
     * <p>Login runs first, then static headers are laid over it - so an explicit
     * static header always wins a name clash, which is the intuitive precedence:
     * a caller who spells a header out means exactly that value. A login that
     * yields nothing (unreachable, refused, no token) contributes nothing and the
     * run proceeds on whatever static headers exist, unauthenticated if none -
     * the auth-wall diagnosis then explains the result.
     */
    private Map<String, String> effectiveAuthHeaders(Map<String, String> staticHeaders, AuthLoginSpec login,
                                                     String projectName, String baseUri) {
        Map<String, String> effective = new LinkedHashMap<>();
        if (login != null) {
            effective.putAll(authTokenResolver.resolve(login, baseUri, resolveTargetRepoRoot(projectName)));
        }
        effective.putAll(cleanHeaders(staticHeaders));
        return effective;
    }

    /**
     * Normalizes a static auth-header map: null becomes empty, and any entry with
     * a blank name or a null value is dropped, so a half-filled form field
     * doesn't put an empty {@code Authorization:} header on every request.
     */
    private Map<String, String> cleanHeaders(Map<String, String> requested) {
        if (requested == null || requested.isEmpty()) {
            return Map.of();
        }
        Map<String, String> clean = new LinkedHashMap<>();
        requested.forEach((name, value) -> {
            if (name != null && !name.isBlank() && value != null) {
                clean.put(name.trim(), value);
            }
        });
        return clean;
    }

    /**
     * The target project's local checkout, for auth source discovery - the same
     * project-name scan of {@code aiqa.git.workspace-dir} generation uses. Null
     * when no clone is found; login then relies on explicit/convention values.
     */
    private Path resolveTargetRepoRoot(String projectName) {
        if (projectName == null || projectName.isBlank()) {
            return null;
        }
        Path workspace = Path.of(gitProperties.getWorkspaceDir());
        if (!Files.isDirectory(workspace)) {
            return null;
        }
        try (Stream<Path> dirs = Files.list(workspace)) {
            for (Path candidate : dirs.filter(Files::isDirectory).toList()) {
                if (!Files.isDirectory(candidate.resolve(".git"))) {
                    continue;
                }
                if (projectName.equalsIgnoreCase(gitDiffService.resolveProjectName(candidate.toString()))) {
                    return candidate;
                }
            }
        } catch (IOException e) {
            log.warn("Could not scan {} for a local clone of '{}': {}", workspace, projectName, e.getMessage());
        }
        return null;
    }

    /**
     * Mirrors QaPipelineService.downloadUrlFor - null when there is no report
     * to link to. commitHash null/blank omits that query param entirely,
     * matching ExecutionReportDownloadService's project-wide fallback.
     */
    private String downloadUrlFor(String projectName, String commitHash, Path evidenceReport) {
        if (evidenceReport == null || projectName == null || projectName.isBlank()) {
            return null;
        }
        String url = "/api/v1/execution-report/download?projectName="
                + java.net.URLEncoder.encode(projectName, java.nio.charset.StandardCharsets.UTF_8);
        if (commitHash != null && !commitHash.isBlank()) {
            url += "&commitHash=" + java.net.URLEncoder.encode(commitHash, java.nio.charset.StandardCharsets.UTF_8);
        }
        return url;
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
                throw new NotFoundException("No such script in %s: %s".formatted(runOutputDir, scriptFileName));
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
