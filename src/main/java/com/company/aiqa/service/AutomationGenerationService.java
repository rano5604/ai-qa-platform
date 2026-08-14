package com.company.aiqa.service;

import com.company.aiqa.ai.LlmClient;
import com.company.aiqa.ai.PromptBuilder;
import com.company.aiqa.config.PipelineProperties;
import com.company.aiqa.git.GitDiffService;
import com.company.aiqa.config.GitProperties;
import com.company.aiqa.model.*;
import com.company.aiqa.parser.SourceParsingService;
import com.company.aiqa.testcase.AutomationScriptMerger;
import com.company.aiqa.testcase.ManualTestCaseGenerator;
import com.company.aiqa.testcase.TestCaseGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Second pass over an existing run: turns the manual test cases already
 * generated for one commit into runnable REST Assured automation.
 *
 * <p>Deliberately separate from QaPipelineService. That class discovers work
 * from git history and produces test cases; this one is handed a specific
 * commit whose cases already exist and only translates them into code. Keeping
 * them apart means the automation pass can be re-run, or run much later,
 * without touching merge history or regenerating any manual cases.
 *
 * <p><b>API only.</b> Only cases that can actually be exercised over HTTP are
 * automated - UI walkthroughs and configuration checks are skipped and
 * counted, since a script for them couldn't meaningfully run.
 *
 * <p><b>Generation only.</b> This class never runs what it produces and sends
 * no HTTP traffic at the target. Running the scripts is
 * {@link AutomationExecutionService}'s job, behind its own endpoint - the two
 * have such different risk profiles that making execution an option on this
 * request put every generation call one boolean away from mutating live data.
 */
@Service
public class AutomationGenerationService {

    private static final Logger log = LoggerFactory.getLogger(AutomationGenerationService.class);

    private static final String MANUAL_CSV = "manual_test_cases.csv";
    private static final String DEFAULT_BASE_URI = "http://localhost:8080";

    /** Categories that never describe an HTTP interaction, whatever their wording. */
    private static final String CONFIG_TYPE = "configuration";

    /** Upper bound on endpoint-bearing classes fed to the prompt, so a large API surface can't blow the context window. */
    private static final int MAX_ENDPOINT_CLASSES = 40;

    /**
     * How far to follow a payload type's own field types. 2 reaches
     * DTO -> nested DTO -> enum, which is where the values that make a payload
     * valid actually live; deeper tends to drag in the whole domain model.
     */
    private static final int PAYLOAD_SCHEMA_DEPTH = 2;

    /** Ceiling on payload schemas in the prompt, for the same reason as MAX_ENDPOINT_CLASSES. */
    private static final int MAX_PAYLOAD_SCHEMAS = 60;

    /** An HTTP verb mentioned in a case's steps - the strongest signal it's API-testable. */
    private static final Pattern HTTP_VERB = Pattern.compile(
            "\\b(GET|POST|PUT|DELETE|PATCH)\\b");

    private final GitProperties gitProperties;
    private final GitDiffService gitDiffService;
    private final SourceParsingService sourceParsingService;
    private final ManualTestCaseGenerator manualTestCaseGenerator;
    private final TestCaseGenerator testCaseGenerator;
    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final PipelineProperties pipelineProperties;
    private final AutomationScriptMerger scriptMerger;
    private final GeneratedRunLocator runLocator;

    public AutomationGenerationService(GitProperties gitProperties,
                                        GitDiffService gitDiffService,
                                        SourceParsingService sourceParsingService,
                                        ManualTestCaseGenerator manualTestCaseGenerator,
                                        TestCaseGenerator testCaseGenerator,
                                        PromptBuilder promptBuilder,
                                        LlmClient llmClient,
                                        PipelineProperties pipelineProperties,
                                        AutomationScriptMerger scriptMerger,
                                        GeneratedRunLocator runLocator) {
        this.gitProperties = gitProperties;
        this.gitDiffService = gitDiffService;
        this.sourceParsingService = sourceParsingService;
        this.manualTestCaseGenerator = manualTestCaseGenerator;
        this.testCaseGenerator = testCaseGenerator;
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.pipelineProperties = pipelineProperties;
        this.scriptMerger = scriptMerger;
        this.runLocator = runLocator;
    }

    public GenerateAutomationResponse generate(GenerateAutomationRequest request) {
        // 1. Work entirely from what's already on disk - no clone, no fetch, no
        // credential. This pass consumes output a previous run produced, so
        // reaching out to the remote would add a network dependency (and a
        // token requirement) for information already local.
        String projectName = runLocator.resolveProjectName(request.getProjectName(), request.getRepoUrl());
        String localPath = resolveLocalRepo(request, projectName);

        String baseOutputDir = request.getOutputDir() != null
                ? request.getOutputDir()
                : pipelineProperties.getOutputDir();
        String runOutputDir = runLocator.runOutputDir(baseOutputDir, projectName, request.getCommitHash());

        // 2. Load the manual cases produced for this commit. Failing loudly
        // here is deliberate: silently generating nothing would look identical
        // to "this commit had no API cases", which is a very different problem.
        if (!Files.isDirectory(Path.of(runOutputDir))) {
            throw new IllegalArgumentException(
                    "No generated-tests folder for commit %s at %s. Run test-case generation for this commit first."
                            .formatted(request.getCommitHash(), runOutputDir));
        }
        List<ManualTestCase> allCases = manualTestCaseGenerator.loadRunCsv(runOutputDir, MANUAL_CSV);
        if (allCases.isEmpty()) {
            throw new IllegalArgumentException(
                    "No manual test cases found in %s/%s - nothing to automate."
                            .formatted(runOutputDir, MANUAL_CSV));
        }

        // 3. Endpoint context from the local checkout, if one exists. Endpoints
        // come from the WHOLE repo, not just this commit's files: a
        // service-layer change is still exercised through a controller the
        // commit never touched, so scanning only the diff finds nothing and
        // makes a perfectly automatable change look un-automatable.
        if (localPath == null) {
            throw new IllegalArgumentException(
                    ("No local checkout found for project '%s'. Automation needs one to read the API surface from. "
                            + "Either run test-case generation for this repo first (which clones it under %s), "
                            + "or pass an explicit \"repoPath\".")
                            .formatted(projectName, gitProperties.getWorkspaceDir()));
        }

        List<ChangedFile> changedFiles = gitDiffService.computeChangedSourceFiles(
                localPath,
                gitDiffService.findCommitWithParent(localPath, request.getCommitHash()).preMergeSha(),
                request.getCommitHash());
        List<ClassInfo> changedClasses = changedFiles.isEmpty()
                ? List.of()
                : sourceParsingService.parseChangedFiles(changedFiles);

        // Both scans read the source AS IT WAS at this commit, never the
        // checkout's working tree. syncRepo only fetches refs, so that tree is
        // frozen at clone time: scanning it generated tests against controllers
        // that did not exist at the commit under test, which compiled, ran, and
        // 404'd against a deployment that rightly had never heard of them.
        List<ClassInfo> endpointClasses;
        List<TypeSchema> payloadSchemas;
        try (GitDiffService.SourceAtCommit source =
                     gitDiffService.openSourceAtCommit(localPath, request.getCommitHash())) {

            endpointClasses = sourceParsingService.scanAllEndpoints(source);
            if (endpointClasses.size() > MAX_ENDPOINT_CLASSES) {
                log.info("Repo exposes {} endpoint-bearing class(es) - passing the first {} to keep the prompt bounded.",
                        endpointClasses.size(), MAX_ENDPOINT_CLASSES);
                endpointClasses = endpointClasses.subList(0, MAX_ENDPOINT_CLASSES);
            }

            // The real shape of every @RequestBody type. Without this the model
            // knows a create endpoint takes an "AppointmentReq" but not what is
            // in one, so it invents field names, the create is rejected, and
            // the precondition it was building never exists.
            java.util.Set<String> payloadTypes = endpointClasses.stream()
                    .flatMap(c -> c.methods().stream())
                    .map(MethodInfo::apiEndpoint)
                    .filter(e -> e != null)
                    .map(ApiEndpointInfo::requestBodyType)
                    .filter(t -> t != null && !t.isBlank())
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

            payloadSchemas = payloadTypes.isEmpty()
                    ? List.of()
                    : sourceParsingService.scanTypeSchemas(source, payloadTypes,
                            PAYLOAD_SCHEMA_DEPTH, MAX_PAYLOAD_SCHEMAS);
        }

        List<String> endpoints = endpointClasses.stream()
                .flatMap(c -> c.methods().stream())
                .map(MethodInfo::apiEndpoint)
                .filter(e -> e != null)
                .map(e -> e.httpMethod() + " " + e.path())
                .distinct()
                .toList();

        log.info("Commit {} changed {} source file(s) -> {}; repo exposes {} endpoint(s).",
                request.getCommitHash(), changedFiles.size(),
                changedClasses.stream().map(ClassInfo::simpleName).toList(), endpoints.size());

        // 4. Keep only the cases worth automating.
        List<ManualTestCase> apiCases = allCases.stream()
                .filter(tc -> isApiTestable(tc, endpoints))
                .toList();
        int skipped = allCases.size() - apiCases.size();

        long explicit = apiCases.stream().filter(tc -> mentionsHttpExplicitly(tc, endpoints)).count();
        if (!apiCases.isEmpty()) {
            log.info("{} of {} selected case(s) name an HTTP verb or endpoint path outright; the remaining {} are "
                            + "user-worded and rely on the model mapping them to a listed endpoint (it omits any it can't).",
                    explicit, apiCases.size(), apiCases.size() - explicit);
        }

        if (apiCases.isEmpty()) {
            String summary = ("Commit %s: %d manual case(s) found, none of them API-testable "
                    + "(%d skipped as UI/configuration). %s")
                    .formatted(request.getCommitHash(), allCases.size(), skipped,
                            endpoints.isEmpty()
                                    ? "No REST endpoints were detected in this commit either."
                                    : "Detected endpoints: " + endpoints);
            log.info(summary);
            return new GenerateAutomationResponse(projectName, request.getCommitHash(), allCases.size(),
                    0, skipped, endpoints, List.of(), runOutputDir, summary);
        }

        // 5. Generate.
        String baseUri = request.getBaseUri() != null && !request.getBaseUri().isBlank()
                ? request.getBaseUri()
                : DEFAULT_BASE_URI;
        String systemPrompt = promptBuilder.apiAutomationSystemPrompt(baseUri);

        // Batch the cases: each one becomes a whole REST Assured method, so a
        // large batch overruns the model's OUTPUT limit and comes back
        // truncated mid-string - unparseable, with every case in that call
        // lost. Smaller batches keep each response inside the cap, and a batch
        // that still overflows is split rather than discarded.
        List<TestCaseResult> generated = new ArrayList<>();   // replaced by the merged single file below
        List<String> failures = new ArrayList<>();
        java.util.Set<String> takenFileNames = new java.util.HashSet<>();
        int perCall = Math.max(1, pipelineProperties.getMaxCasesPerAutomationCall());
        List<List<ManualTestCase>> caseBatches = partition(apiCases, perCall);

        for (int i = 0; i < caseBatches.size(); i++) {
            generateBatch(caseBatches.get(i), endpointClasses, payloadSchemas, systemPrompt, request,
                    runOutputDir, generated, failures, takenFileNames, "batch %d/%d".formatted(i + 1, caseBatches.size()));
        }

        // Batching is an implementation detail of staying inside the model's
        // output limit - the caller should get ONE file per commit, named for
        // that commit, not three arbitrarily-numbered ones.
        if (!generated.isEmpty()) {
            try {
                TestCaseResult mergedScript = scriptMerger.merge(generated, request.getCommitHash());
                deletePerBatchFiles(generated);
                String path = writeMerged(runOutputDir, mergedScript);
                generated = List.of(new TestCaseResult(mergedScript.targetClassName(),
                        mergedScript.testFileName(), mergedScript.testCode(), path));
            } catch (Exception e) {
                // Keep the per-batch files rather than losing everything - they
                // are still valid, just split across several classes.
                log.error("Could not merge the generated scripts ({}), leaving the per-batch files in place.",
                        e.getMessage(), e);
                failures.add("merge: " + shortMessage(e.getMessage()));
            }
        }

        String failure = failures.isEmpty() ? null : String.join("; ", failures);

        String summary = "Commit %s: %d manual case(s), %d API-testable (%d skipped as UI/configuration), %d script file(s) generated against %s."
                .formatted(request.getCommitHash(), allCases.size(), apiCases.size(), skipped,
                        generated.size(), baseUri);
        if (failure != null) {
            summary += " WARNING: %d case(s) could not be generated: %s".formatted(failures.size(), failure);
        }

        if (!generated.isEmpty()) {
            summary += " Nothing has been executed - run it with POST /api/v1/execute-automation for this commit.";
        }
        log.info(summary);

        return new GenerateAutomationResponse(projectName, request.getCommitHash(), allCases.size(),
                apiCases.size(), skipped, endpoints, generated, runOutputDir, summary);
    }

    /**
     * Whether a manual case is a candidate for HTTP automation.
     *
     * <p>Two hard exclusions only: {@code Configuration} cases (a dependency
     * bump or SDK-version change has no HTTP surface by definition), and
     * everything when the project exposes no endpoints at all (nothing to
     * call).
     *
     * <p>Beyond that, functional cases are passed through for the model to
     * judge - its prompt requires it to OMIT anything it can't map to a listed
     * endpoint. An earlier version demanded the case text itself name an HTTP
     * verb or endpoint path, which rejected every legitimately automatable
     * case whose steps were written in user language ("Set a new slot for a
     * shop") rather than API language. Since manual cases are phrased for
     * humans by design, that heuristic filtered out exactly the cases this
     * endpoint exists to automate.
     */
    private boolean isApiTestable(ManualTestCase tc, List<String> endpoints) {
        if (endpoints.isEmpty()) {
            return false;
        }
        if (tc.type() != null && CONFIG_TYPE.equalsIgnoreCase(tc.type().trim())) {
            return false;
        }
        return true;
    }

    /** True when the case's own text already names an HTTP verb or a known path - logged as a confidence signal. */
    private boolean mentionsHttpExplicitly(ManualTestCase tc, List<String> endpoints) {
        String haystack = ((tc.steps() == null ? "" : tc.steps()) + " "
                + (tc.testData() == null ? "" : tc.testData()) + " "
                + (tc.expectedResult() == null ? "" : tc.expectedResult()));
        if (HTTP_VERB.matcher(haystack.toUpperCase(Locale.ROOT)).find()) {
            return true;
        }
        return endpoints.stream()
                .map(e -> e.substring(e.indexOf(' ') + 1))
                .filter(p -> !p.isBlank() && !"/".equals(p))
                .anyMatch(haystack::contains);
    }

    /**
     * Generates one batch, splitting it in half and retrying on failure so a
     * truncated or malformed response costs only that half rather than every
     * case in the call. Bottoms out at a single case, whose failure is real
     * and gets reported.
     */
    private void generateBatch(List<ManualTestCase> batch, List<ClassInfo> endpointClasses,
                                List<TypeSchema> payloadSchemas, String systemPrompt,
                                GenerateAutomationRequest request, String runOutputDir,
                                List<TestCaseResult> generatedOut, List<String> failuresOut,
                                java.util.Set<String> takenFileNames, String label) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            String response = llmClient.complete(
                    systemPrompt,
                    promptBuilder.buildApiAutomationUserPrompt(batch, endpointClasses, payloadSchemas),
                    request.getLlmKeys());
            generatedOut.addAll(testCaseGenerator.generateAndWrite(response, runOutputDir, takenFileNames));
            log.info("Automation {} ({} case(s)): generated OK.", label, batch.size());
        } catch (Exception e) {
            if (batch.size() > 1) {
                log.warn("Automation {} ({} case(s)) failed ({}) - splitting and retrying so the rest still land.",
                        label, batch.size(), e.getMessage());
                int mid = batch.size() / 2;
                generateBatch(batch.subList(0, mid), endpointClasses, payloadSchemas, systemPrompt, request,
                        runOutputDir, generatedOut, failuresOut, takenFileNames, label + ".a");
                generateBatch(batch.subList(mid, batch.size()), endpointClasses, payloadSchemas, systemPrompt, request,
                        runOutputDir, generatedOut, failuresOut, takenFileNames, label + ".b");
                return;
            }
            String id = batch.get(0).testCaseId();
            log.error("Automation {}: case {} could not be generated: {}", label, id, e.getMessage());
            failuresOut.add(id + ": " + shortMessage(e.getMessage()));
        }
    }

    /** Removes the intermediate per-batch files once their contents live in the merged class. */
    private void deletePerBatchFiles(List<TestCaseResult> scripts) {
        for (TestCaseResult script : scripts) {
            if (script.writtenPath() == null) {
                continue;
            }
            try {
                Files.deleteIfExists(Path.of(script.writtenPath()));
            } catch (java.io.IOException e) {
                log.warn("Could not remove intermediate script {}: {}", script.writtenPath(), e.getMessage());
            }
        }
    }

    private String writeMerged(String runOutputDir, TestCaseResult merged) {
        try {
            Path file = Path.of(runOutputDir, merged.testFileName());
            Files.writeString(file, merged.testCode(), java.nio.charset.StandardCharsets.UTF_8);
            return file.toAbsolutePath().toString();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to write merged automation file: " + e.getMessage(), e);
        }
    }

    /** Keeps the response summary readable - raw LLM parse errors embed the entire truncated payload. */
    private String shortMessage(String message) {
        if (message == null) {
            return "unknown error";
        }
        String firstLine = message.split("\\R", 2)[0];
        return firstLine.length() > 200 ? firstLine.substring(0, 200) + "..." : firstLine;
    }

    /** Splits a list into consecutive chunks of at most {@code size}. */
    private static <T> List<List<T>> partition(List<T> items, int size) {
        if (items.isEmpty()) {
            return List.of();
        }
        List<List<T>> batches = new ArrayList<>();
        for (int start = 0; start < items.size(); start += size) {
            batches.add(items.subList(start, Math.min(start + size, items.size())));
        }
        return batches;
    }

    /**
     * Finds an existing local checkout to read endpoints from - an explicit
     * repoPath if given, otherwise the clone this platform already made under
     * aiqa.git.workspace-dir, matched by project name.
     *
     * <p>Returns null when there's no local clone. Endpoint grounding is then
     * unavailable, which the caller reports explicitly rather than silently
     * generating scripts against invented paths.
     */
    private String resolveLocalRepo(GenerateAutomationRequest request, String projectName) {
        if (request.getRepoPath() != null && !request.getRepoPath().isBlank()) {
            Path explicit = Path.of(request.getRepoPath());
            if (!Files.isDirectory(explicit.resolve(".git"))) {
                throw new IllegalArgumentException("repoPath is not a git checkout: " + explicit);
            }
            return explicit.toString();
        }

        Path workspace = Path.of(gitProperties.getWorkspaceDir());
        if (!Files.isDirectory(workspace)) {
            return null;
        }
        try (java.util.stream.Stream<Path> dirs = Files.list(workspace)) {
            for (Path candidate : dirs.filter(Files::isDirectory).toList()) {
                if (!Files.isDirectory(candidate.resolve(".git"))) {
                    continue;
                }
                if (projectName.equalsIgnoreCase(gitDiffService.resolveProjectName(candidate.toString()))) {
                    log.info("Using existing local clone for '{}': {}", projectName, candidate);
                    return candidate.toString();
                }
            }
        } catch (java.io.IOException e) {
            log.warn("Could not scan {} for a local clone of '{}': {}", workspace, projectName, e.getMessage());
        }
        return null;
    }
}
