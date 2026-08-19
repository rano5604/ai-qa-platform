package com.company.aiqa.service;

import com.company.aiqa.ai.router.ProviderLogs;
import com.company.aiqa.ai.LlmClient;
import com.company.aiqa.ai.PromptBuilder;
import com.company.aiqa.config.PipelineProperties;
import com.company.aiqa.error.NotFoundException;
import com.company.aiqa.git.GitDiffService;
import com.company.aiqa.llm.LlmKeyStore;
import com.company.aiqa.config.GitProperties;
import com.company.aiqa.model.*;
import com.company.aiqa.openapi.ApiContract;
import com.company.aiqa.openapi.ApiContractRenderer;
import com.company.aiqa.openapi.OpenApiContractService;
import com.company.aiqa.openapi.OpenApiSource;
import com.company.aiqa.parser.SourceParsingService;
import com.company.aiqa.testcase.AutomationScriptMerger;
import com.company.aiqa.testcase.CompileSalvager;
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

    /**
     * Character budget for the endpoint implementation fed to the prompt.
     *
     * <p>Generous, because this is the context that decides whether the suite
     * runs at all: without the handler bodies the model cannot see that a fee
     * needs an existing merchant, invents an id, and every test 404s. Still
     * bounded, since a large service layer would otherwise crowd out the manual
     * cases themselves.
     */
    private static final int MAX_IMPLEMENTATION_CHARS = 60_000;

    /** Below this, the remaining source is too fragmentary to help - drop it instead of shrinking again. */
    private static final int MIN_IMPLEMENTATION_CHARS = 4_000;

    /** Cut point for a shrunken prompt - never mid-line, so the model never sees half a statement. */
    private static final char NEWLINE = '\n';

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
    private final CompileSalvager compileSalvager;
    private final GeneratedRunLocator runLocator;
    private final LlmKeyStore llmKeyStore;
    private final OpenApiContractService openApiContractService;
    private final ApiContractRenderer apiContractRenderer;

    public AutomationGenerationService(GitProperties gitProperties,
                                        GitDiffService gitDiffService,
                                        SourceParsingService sourceParsingService,
                                        ManualTestCaseGenerator manualTestCaseGenerator,
                                        TestCaseGenerator testCaseGenerator,
                                        PromptBuilder promptBuilder,
                                        LlmClient llmClient,
                                        PipelineProperties pipelineProperties,
                                        AutomationScriptMerger scriptMerger,
                                        CompileSalvager compileSalvager,
                                        GeneratedRunLocator runLocator,
                                        LlmKeyStore llmKeyStore,
                                        OpenApiContractService openApiContractService,
                                        ApiContractRenderer apiContractRenderer) {
        this.gitProperties = gitProperties;
        this.gitDiffService = gitDiffService;
        this.sourceParsingService = sourceParsingService;
        this.manualTestCaseGenerator = manualTestCaseGenerator;
        this.testCaseGenerator = testCaseGenerator;
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.pipelineProperties = pipelineProperties;
        this.scriptMerger = scriptMerger;
        this.compileSalvager = compileSalvager;
        this.runLocator = runLocator;
        this.llmKeyStore = llmKeyStore;
        this.openApiContractService = openApiContractService;
        this.apiContractRenderer = apiContractRenderer;
    }

    public GenerateAutomationResponse generate(GenerateAutomationRequest request) {
        if (request.getLlmKeys() == null || request.getLlmKeys().isEmpty()) {
            if (!llmKeyStore.isConfigured() && !llmClient.hasServerSideCredentials()) {
                throw new IllegalStateException(
                        "No LLM credentials are configured, so there is no model to generate with. "
                                + "Configure them once with POST /api/v1/llm-keys.");
            }
        }

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
            throw new NotFoundException(
                    "No generated-tests folder for commit %s at %s. Run test-case generation for this commit first."
                            .formatted(request.getCommitHash(), runOutputDir));
        }
        List<ManualTestCase> allCases = manualTestCaseGenerator.loadRunCsv(runOutputDir, MANUAL_CSV);
        if (allCases.isEmpty()) {
            throw new NotFoundException(
                    "No manual test cases found in %s/%s - nothing to automate."
                            .formatted(runOutputDir, MANUAL_CSV));
        }

        // 3. Endpoint context from the local checkout, if one exists. Endpoints
        // come from the WHOLE repo, not just this commit's files: a
        // service-layer change is still exercised through a controller the
        // commit never touched, so scanning only the diff finds nothing and
        // makes a perfectly automatable change look un-automatable.
        if (localPath == null) {
            throw new NotFoundException(
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
        String implementationSource;
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

            // Must happen inside this block - SourceAtCommit is closed on exit,
            // and the handler bodies are the only place a precondition like
            // "the merchant must already exist" is actually stated.
            implementationSource = collectImplementationSource(source, endpointClasses);
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

        // The service's own OpenAPI document, if it serves one. This is the
        // only thing in the whole prompt that describes a RESPONSE - without it
        // every assertion about a returned body is a guess - and it is the only
        // authority on what the DEPLOYED target accepts, which is what these
        // tests are about to call.
        //
        // Fetched here rather than with the rest of the source scanning, for two
        // reasons: it needs the resolved baseUri, and the "no API-testable
        // cases" return above happens first, so a run with nothing to generate
        // never touches the network.
        //
        // Optional by construction. An unreachable document falls back to the
        // source-derived DTOs, which is what the generator used before this
        // existed - trading a better prompt for no prompt at all would be a bad
        // bargain.
        ApiContract apiContract = openApiContractService.collect(
                localPath == null ? null : Path.of(localPath), baseUri, request.getOpenApiUrls());

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

        // Shared, and mutable, on purpose: when a provider rejects a prompt as
        // too large, the batch that discovered it shrinks this for every batch
        // that follows. Otherwise all 34 cases pay the same rejection.
        java.util.concurrent.atomic.AtomicReference<String> implementation =
                new java.util.concurrent.atomic.AtomicReference<>(implementationSource);

        for (int i = 0; i < caseBatches.size(); i++) {
            generateBatch(caseBatches.get(i), endpointClasses, payloadSchemas, apiContract, implementation,
                    systemPrompt, request, runOutputDir, generated, failures, takenFileNames,
                    "batch %d/%d".formatted(i + 1, caseBatches.size()));
        }

        // Batching is an implementation detail of staying inside the model's
        // output limit - the caller should get ONE file per commit, named for
        // that commit, not three arbitrarily-numbered ones.
        if (!generated.isEmpty()) {
            try {
                TestCaseResult mergedScript = scriptMerger.merge(generated, request.getCommitHash());

                // Java compiles a file all-or-nothing, so one unusable line -
                // an invented matcher, a bad literal, a missing import - loses
                // every test in it and the run reports zero tests as though
                // nothing happened. Drop only the methods that don't compile,
                // and say which, so the rest of the suite still runs and the
                // gap is visible rather than silent.
                // Before compiling: a method reading an id field nothing assigns
                // cannot send a request, so it is a guaranteed red row rather
                // than a test. Pruned first so salvage() can clean up anything
                // its removal breaks.
                CompileSalvager.Salvaged pruned = compileSalvager.dropMethodsUsingUnassignedFields(mergedScript);
                mergedScript = pruned.script();
                if (!pruned.removedMethods().isEmpty()) {
                    failures.add(("removed %d method(s) that read an id field no setup assigns - nothing that "
                            + "runs first fills it in, so they would have sent null or nothing at all: %s")
                            .formatted(pruned.removedMethods().size(), pruned.removedMethods()));
                }

                CompileSalvager.Salvaged salvaged = compileSalvager.salvage(mergedScript);
                mergedScript = salvaged.script();
                if (!salvaged.removedMethods().isEmpty()) {
                    failures.add("removed %d uncompilable test method(s): %s"
                            .formatted(salvaged.removedMethods().size(), salvaged.removedMethods()));
                }
                // Anything still unassigned after the prune above - a field only
                // read by a method salvage() kept, say - is still worth naming.
                List<String> orphanFields = compileSalvager.fieldsReadButNeverAssigned(mergedScript);
                if (!orphanFields.isEmpty()) {
                    failures.add(("shared id field(s) %s are never assigned - every test using them will "
                            + "error before sending a request").formatted(orphanFields));
                }
                if (!salvaged.unresolvedErrors().isEmpty()) {
                    failures.add("script does not compile: "
                            + String.join("; ", salvaged.unresolvedErrors()));
                }

                deletePerBatchFiles(generated);
                String path = writeMerged(runOutputDir, mergedScript);
                generated = List.of(new TestCaseResult(mergedScript.targetClassName(),
                        mergedScript.testFileName(), mergedScript.testCode(), path));
            } catch (Exception e) {
                // Keep the per-batch files rather than losing everything - they
                // are still valid, just split across several classes.
                log.error("Could not merge the generated scripts ({}), leaving the per-batch files in place.",
                        e.getMessage(), e);
                failures.add("merge: " + shortMessage(e));
            }
        }

        String failure = failures.isEmpty() ? null : String.join("; ", failures);

        String summary = "Commit %s: %d manual case(s), %d API-testable (%d skipped as UI/configuration), %d script file(s) generated against %s."
                .formatted(request.getCommitHash(), allCases.size(), apiCases.size(), skipped,
                        generated.size(), baseUri);
        // Say which contract shaped the payloads, or that none did. A run
        // generated blind against source DTOs and one generated from the
        // target's own document are different artefacts and should not read
        // the same.
        summary += apiContract.isEmpty()
                ? " No OpenAPI document was reachable - payloads came from source DTOs only, and no response shape was known."
                : " API contract: %d operation(s) from %s.".formatted(apiContract.endpoints().size(),
                        apiContract.sources().stream().map(OpenApiSource::describe).toList());
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
     * The batch's cases as one blob, so the renderer can tell which operations
     * this batch is actually going to call and spend its budget on those.
     */
    private String batchText(List<ManualTestCase> batch) {
        StringBuilder sb = new StringBuilder();
        for (ManualTestCase tc : batch) {
            sb.append(tc.scenario()).append(' ').append(tc.preconditions()).append(' ')
                    .append(tc.steps()).append(' ').append(tc.testData()).append(' ')
                    .append(tc.expectedResult()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Generates one batch, splitting it in half and retrying on failure so a
     * truncated or malformed response costs only that half rather than every
     * case in the call. Bottoms out at a single case, whose failure is real
     * and gets reported.
     */
    private void generateBatch(List<ManualTestCase> batch, List<ClassInfo> endpointClasses,
                                List<TypeSchema> payloadSchemas,
                                ApiContract apiContract,
                                java.util.concurrent.atomic.AtomicReference<String> implementation,
                                String systemPrompt,
                                GenerateAutomationRequest request, String runOutputDir,
                                List<TestCaseResult> generatedOut, List<String> failuresOut,
                                java.util.Set<String> takenFileNames, String label) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            String response = llmClient.complete(
                    systemPrompt,
                    promptBuilder.buildApiAutomationUserPrompt(batch, endpointClasses, payloadSchemas,
                            implementation.get(), apiContractRenderer.render(apiContract, batchText(batch))),
                    llmKeyStore.resolveFor(request.getLlmKeys()));
            generatedOut.addAll(testCaseGenerator.generateAndWrite(response, runOutputDir, takenFileNames));
            log.info("Automation {} ({} case(s)): generated OK.", label, batch.size());
        } catch (Exception e) {
            // Splitting the batch does NOT shrink this prompt. The endpoint
            // list, the payload schemas and the implementation source are sent
            // in full with every call, so a batch of one is nearly as large as
            // a batch of ten - which is why an oversized prompt failed all 34
            // cases identically instead of getting smaller on retry. The
            // implementation source is the only part big enough to matter, so
            // it is what gives way first.
            if (com.company.aiqa.ai.router.ProviderCooldownRegistry.isPromptTooLarge(e)
                    && shrinkImplementation(implementation, label)) {
                generateBatch(batch, endpointClasses, payloadSchemas, apiContract, implementation, systemPrompt,
                        request, runOutputDir, generatedOut, failuresOut, takenFileNames, label);
                return;
            }
            if (batch.size() > 1) {
                log.warn("Automation {} ({} case(s)) failed ({}) - splitting and retrying so the rest still land.",
                        label, batch.size(), e.getMessage());
                int mid = batch.size() / 2;
                generateBatch(batch.subList(0, mid), endpointClasses, payloadSchemas, apiContract, implementation,
                        systemPrompt, request, runOutputDir, generatedOut, failuresOut, takenFileNames, label + ".a");
                generateBatch(batch.subList(mid, batch.size()), endpointClasses, payloadSchemas, apiContract,
                        implementation, systemPrompt, request, runOutputDir, generatedOut, failuresOut,
                        takenFileNames, label + ".b");
                return;
            }
            String id = batch.get(0).testCaseId();
            log.error("Automation {}: case {} could not be generated: {}", label, id, e.getMessage());
            failuresOut.add(id + ": " + shortMessage(e));
        }
    }

    /**
     * Halves the implementation source after a provider rejected the prompt for
     * its size, and reports whether there was anything left to give.
     *
     * <p>Cut on a line boundary so the model is never handed half a statement,
     * and dropped entirely once the remainder is too small to be worth the
     * tokens. The change sticks for the rest of the run: the size limit belongs
     * to the provider, not to the batch that happened to hit it.
     *
     * @return false when the source is already empty - the caller should stop
     *         retrying and report the failure
     */
    private boolean shrinkImplementation(java.util.concurrent.atomic.AtomicReference<String> implementation,
                                          String label) {
        String current = implementation.get();
        if (current == null || current.isEmpty()) {
            return false;
        }
        int half = current.length() / 2;
        String smaller = "";
        if (half >= MIN_IMPLEMENTATION_CHARS) {
            int boundary = current.lastIndexOf(NEWLINE, half);
            smaller = current.substring(0, boundary > 0 ? boundary : half);
        }
        implementation.set(smaller);
        log.warn("Automation {}: the provider rejected this prompt as too large. Cases and endpoints are fixed "
                + "cost, so the implementation source is cut from {} to {} char(s) and the call retried - every "
                + "later batch uses the smaller context too.", label, current.length(), smaller.length());
        return true;
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

    /**
     * Keeps the response summary readable - raw LLM parse errors embed the
     * entire truncated payload - while still naming what went wrong.
     *
     * <p>Deliberately NOT "the first line of the message": for
     * AllProvidersFailedException that line is the constant header "All
     * providers failed:" and every provider's real reason sits below it, so a
     * failed run reported one copy of that header per case and diagnosed
     * nothing. ProviderLogs.compactFailure flattens the reasons instead.
     */
    private String shortMessage(Throwable e) {
        String flat = ProviderLogs.compactFailure(e);
        return flat.length() > 300 ? flat.substring(0, 300) + "..." : flat;
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
    /**
     * Source of the endpoint classes and the collaborators they reference, up to
     * {@link #MAX_IMPLEMENTATION_CHARS}.
     *
     * <p>Endpoint signatures and request schemas describe the SHAPE of a call
     * but say nothing about what the handler requires to already exist. When fee
     * creation begins by loading its merchant and throwing if absent, that fact
     * lives only in the method body - and a generator that cannot see it writes
     * tests that post a fee against an invented merchant id and 404 every time.
     *
     * <p>Controllers come first so they are never crowded out, then the services
     * and repositories they name, since that is where existence checks and
     * validation actually sit.
     */
    private String collectImplementationSource(GitDiffService.SourceAtCommit source,
                                               List<ClassInfo> endpointClasses) {
        java.util.LinkedHashSet<String> wanted = new java.util.LinkedHashSet<>();
        for (ClassInfo endpointClass : endpointClasses) {
            if (endpointClass.sourceFilePath() != null) {
                wanted.add(endpointClass.sourceFilePath());
            }
        }
        int controllerCount = wanted.size();

        // Collaborators by simple type name - the services/repositories a
        // controller delegates to, which is where the existence checks live.
        java.util.Set<String> collaborators = new java.util.HashSet<>();
        for (ClassInfo endpointClass : endpointClasses) {
            collaborators.addAll(endpointClass.referencedTypes());
        }
        if (!collaborators.isEmpty()) {
            for (String path : source.paths()) {
                if (!path.endsWith(".java") || wanted.contains(path)) {
                    continue;
                }
                String simpleName = path.substring(path.lastIndexOf('/') + 1).replace(".java", "");
                if (collaborators.contains(simpleName)) {
                    wanted.add(path);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        int included = 0;
        for (String path : wanted) {
            String body = source.read(path);
            if (body == null || body.isBlank()) {
                continue;
            }
            if (sb.length() + body.length() > MAX_IMPLEMENTATION_CHARS) {
                // Stop cleanly rather than half-including a class: a truncated
                // method body is worse than an absent one, since it can read as
                // though a check simply isn't there.
                break;
            }
            sb.append("### ").append(path).append("\n```java\n")
                    .append(body).append("\n```\n\n");
            included++;
        }

        log.info("Endpoint implementation for the prompt: {} of {} file(s), {} chars "
                        + "({} controller(s), rest collaborators).",
                included, wanted.size(), sb.length(), controllerCount);
        return sb.toString();
    }

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
