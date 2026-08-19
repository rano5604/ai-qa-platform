package com.company.aiqa.service;

import com.company.aiqa.ai.LlmClient;
import com.company.aiqa.ai.PromptBuilder;
import com.company.aiqa.config.CredentialScheme;
import com.company.aiqa.config.PipelineProperties;
import com.company.aiqa.dependency.DependencyService;
import com.company.aiqa.git.GitDiffService;
import com.company.aiqa.git.MergeHistoryService;
import com.company.aiqa.git.RepoSyncService;
import com.company.aiqa.impact.ImpactAnalysisService;
import com.company.aiqa.llm.LlmKeyStore;
import com.company.aiqa.model.*;
import com.company.aiqa.parser.SourceParsingService;
import com.company.aiqa.project.ProjectShapeAnalyzer;
import com.company.aiqa.testcase.ManualTestCaseGenerator;
import com.company.aiqa.testcase.TestCaseGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the full pipeline shown in the architecture diagram:
 *
 *   Merge to Release Branch -> Git Diff -> Parse Changed Java Files ->
 *   Find Dependencies -> Send Context to LLM -> Generate Test Cases
 *
 * "Generate Test Cases" branches on TestCaseMode:
 *   MANUAL (default)  -> business/functional test cases for QA, as a CSV
 *   AUTOMATED          -> JUnit/Jest/pytest/etc. source files
 *   BOTH               -> both of the above (two separate LLM calls)
 */
@Service
public class QaPipelineService {

    private static final Logger log = LoggerFactory.getLogger(QaPipelineService.class);

    private final GitDiffService gitDiffService;
    private final RepoSyncService repoSyncService;
    private final MergeHistoryService mergeHistoryService;
    private final SourceParsingService sourceParsingService;
    private final DependencyService dependencyService;
    private final ImpactAnalysisService impactAnalysisService;
    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final TestCaseGenerator testCaseGenerator;
    private final ManualTestCaseGenerator manualTestCaseGenerator;
    private final PipelineProperties pipelineProperties;
    private final ProjectShapeAnalyzer projectShapeAnalyzer;
    private final LlmKeyStore llmKeyStore;

    public QaPipelineService(GitDiffService gitDiffService,
                              RepoSyncService repoSyncService,
                              MergeHistoryService mergeHistoryService,
                              SourceParsingService sourceParsingService,
                              DependencyService dependencyService,
                              ImpactAnalysisService impactAnalysisService,
                              PromptBuilder promptBuilder,
                              LlmClient llmClient,
                              TestCaseGenerator testCaseGenerator,
                              ManualTestCaseGenerator manualTestCaseGenerator,
                              PipelineProperties pipelineProperties,
                              ProjectShapeAnalyzer projectShapeAnalyzer,
                              LlmKeyStore llmKeyStore) {
        this.gitDiffService = gitDiffService;
        this.repoSyncService = repoSyncService;
        this.mergeHistoryService = mergeHistoryService;
        this.sourceParsingService = sourceParsingService;
        this.dependencyService = dependencyService;
        this.impactAnalysisService = impactAnalysisService;
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.testCaseGenerator = testCaseGenerator;
        this.manualTestCaseGenerator = manualTestCaseGenerator;
        this.pipelineProperties = pipelineProperties;
        this.projectShapeAnalyzer = projectShapeAnalyzer;
        this.llmKeyStore = llmKeyStore;
    }

    private static final ImpactResult EMPTY_IMPACT =
            new ImpactResult(java.util.Set.of(), java.util.Set.of(), 0, java.util.Set.of(), java.util.Set.of());

    public GenerateTestsResponse run(GenerateTestsRequest request) {
        requireLlmCredentials(request.getLlmKeys());

        // 0. Make sure we have a checkout to diff. A caller may name a local
        // path or a remote URL; with a URL the clone is reused when it already
        // exists and fetched rather than re-cloned, so asking for several
        // commits of the same repo costs one clone. Mutating the request here
        // means every later step - diffing, parsing, reading collaborator
        // source - sees a real path without knowing where it came from.
        request.setRepoPath(resolveRepoCheckout(request));

        // 1. Git Diff
        List<ChangedFile> changedFiles = gitDiffService.computeChangedSourceFiles(
                request.getRepoPath(), request.getBaseRef(), request.getHeadRef());

        // Config files (application.yml, .properties, Dockerfile, pom.xml,
        // etc. - see ConfigType) are tracked separately from source files:
        // they have no methods/classes to parse and no dependency graph to
        // walk, but a merge that only changes config used to be silently
        // skipped ("no changed source files found") and produce zero test
        // cases even though config changes are exactly the kind of thing QA
        // wants a regression checklist for.
        List<ChangedFile> configFiles = pipelineProperties.isConfigChangeDetectionEnabled()
                ? gitDiffService.computeChangedConfigFiles(request.getRepoPath(), request.getBaseRef(), request.getHeadRef())
                : List.of();

        // Build tooling (build.gradle, pom.xml, wrapper/version catalogs,
        // Dockerfiles, CI pipelines) is recognized as config but carries no
        // business logic, so there are no rules or outcomes to test - the
        // checklist it produces just restates what a green build already
        // proves. Dropped unless explicitly asked for.
        if (!pipelineProperties.isBuildConfigTestCasesEnabled() && !configFiles.isEmpty()) {
            List<ChangedFile> withoutBuildFiles = configFiles.stream()
                    .filter(file -> !ConfigType.isBuildTooling(file.path()))
                    .toList();
            int dropped = configFiles.size() - withoutBuildFiles.size();
            if (dropped > 0) {
                log.info("Skipping {} build-tooling file(s) - no business logic to test. "
                                + "Set aiqa.pipeline.build-config-test-cases-enabled=true to include them.",
                        dropped);
            }
            configFiles = withoutBuildFiles;
        }

        List<CommitInfo> commitLog = gitDiffService.getCommitLog(
                request.getRepoPath(), request.getBaseRef(), request.getHeadRef());

        // aiqa.pipeline.max-changed-files is a BATCH SIZE, not a cap: a merge
        // touching more files than this is split into several LLM calls per
        // category and every file is covered. It used to truncate - keeping
        // the first N and silently discarding the rest, while still reporting
        // success - which meant a large merge could look fully processed with
        // most of its files never seen by the model.
        int batchSize = Math.max(1, pipelineProperties.getMaxChangedFiles());
        if (changedFiles.size() > batchSize) {
            log.info("{} changed source file(s) exceed the per-call batch size of {} - processing in {} batches "
                            + "so every file is covered.",
                    changedFiles.size(), batchSize, batchCount(changedFiles.size(), batchSize));
        }

        if (changedFiles.isEmpty() && configFiles.isEmpty()) {
            // Nothing to generate is a legitimately COMPLETE outcome, not a
            // gap - otherwise an empty merge would be re-attempted forever.
            return new GenerateTestsResponse(0, 0, 0, 0, commitLog, List.of(), null, null, List.of(),
                    List.of(), List.of(), List.of(), 100, MergeStatus.SUCCESS,
                    "No changed source or configuration files found between %s and %s.".formatted(
                            request.getBaseRef(), request.getHeadRef()));
        }

        // 2. Parse Changed Source Files (skipped entirely for a config-only
        // merge - there's nothing to parse, and no source-file scan needed).
        List<ClassInfo> classes = changedFiles.isEmpty()
                ? List.of()
                : sourceParsingService.parseChangedFiles(changedFiles);

        // 3. Find Dependencies + impact radius. Single assignment so the
        // prompt-building lambdas below can capture it.
        final ImpactResult impact = classes.isEmpty()
                ? EMPTY_IMPACT
                : impactAnalysisService.computeImpact(
                        classes, dependencyService.buildGraph(request.getRepoPath(), classes));

        // Backend/API-only projects (no UI - see ProjectShapeAnalyzer) get
        // manual test-case steps written as direct API calls (HTTP method +
        // endpoint + payload) instead of the default UI-click-through wording,
        // since there's no screen for a tester to walk through.
        boolean apiOnly = !classes.isEmpty() && projectShapeAnalyzer.isApiOnly(request.getRepoPath(), classes);

        String baseOutputDir = request.getOutputDir() != null
                ? request.getOutputDir()
                : pipelineProperties.getOutputDir();

        // Every project gets its own subfolder (named from the repo's "origin"
        // remote - see GitDiffService.resolveProjectName) under the configured
        // output dir, so pointing this platform at more than one repo doesn't
        // mix their test cases into one shared master catalog.
        String projectName = gitDiffService.resolveProjectName(request.getRepoPath());
        String outputDir = Path.of(baseOutputDir, projectName).toString();

        // Each run gets its own subfolder keyed by headRef, so a backfill
        // processing many merges (or repeated single-merge calls) never
        // overwrites a previous run's output - this was silently happening
        // before, since every run wrote to the same fixed "manual_test_cases.csv" /
        // TestFileName.java under the same shared outputDir.
        String runOutputDir = Path.of(outputDir,
                runFolderName(request.getHeadRef(), request.getCommitSequence())).toString();

        TestCaseMode mode = resolveMode(request.getTestCaseMode());

        List<ManualTestCase> businessTestCases = List.of();
        String manualCsvPath = null;
        List<TestCaseResult> generatedTests = List.of();
        List<String> failedCategories = new ArrayList<>();
        String automatedFailure = null;

        // What this run is expected to produce. Normally every configured
        // category; on a RESUME (see runFromBranch/backfill) the caller passes
        // only the categories a previous attempt left missing, so completed
        // work is never regenerated.
        // The rules a tester needs are usually one call away from the diff: a
        // service that refuses to act until a parent record exists, a validator,
        // a default. Impact analysis knows which classes those are but carries
        // only their names, so without this the model has never seen the code
        // that decides how the feature behaves. Computed once - it is identical
        // for every category and batch.
        final String relatedImplementation = changedFiles.isEmpty()
                ? ""
                : collectRelatedImplementation(request, impact, changedFiles);

        List<String> expectedCategories = resolveCategories(request, !configFiles.isEmpty());
        List<String> generatedCategories = new ArrayList<>();
        // Categories whose calls all succeeded but that produced nothing. This
        // is a normal outcome, not a failure: the prompts tell the model to
        // return nothing rather than pad a category that doesn't apply to the
        // change (SECURITY on a diff with no auth surface is the common case).
        // Tracked separately purely so the summary can say so - a run that
        // reports 100% coverage and 0 SECURITY cases otherwise looks like
        // something quietly went wrong.
        List<String> emptyCategories = new ArrayList<>();

        // 4 & 5. Send Context to LLM -> Generate Test Cases (business and/or automated)
        if (mode == TestCaseMode.MANUAL || mode == TestCaseMode.BOTH) {
            // Load once per run - the same catalog snapshot is used (filtered
            // per category below) as "what already exists" context for every
            // category's prompt, so the LLM can tell NEW from UPDATE instead
            // of generating blind and piling up duplicates every merge.
            List<ManualTestCase> masterCatalog = manualTestCaseGenerator.loadMasterCsv(outputDir, "all_manual_test_cases.csv");

            List<ManualTestCase> merged = new ArrayList<>();
            // Single-element array so the lambdas below can advance it - test
            // case IDs must keep counting across categories AND batches, or
            // every batch would restart at TC-001 and collide.
            int[] idCursor = {1};

            // Categories are requested one per call by default. Raising
            // aiqa.pipeline.categories-per-call groups them, so the identical
            // diff/impact/existing-case context is sent once per GROUP rather
            // than once per category - the single biggest lever on token spend,
            // at the cost of some depth per category.
            int perCall = Math.max(1, pipelineProperties.getCategoriesPerCall());
            for (List<String> group : partition(expectedCategories, perCall)) {
                String category = group.get(0);
                String capitalizedCategory = capitalize(category);
                List<String> groupForParser = group.size() > 1 ? group : List.of();

                List<ManualTestCase> existingForCategory = masterCatalog.stream()
                        .filter(tc -> group.stream().anyMatch(g -> capitalize(g).equalsIgnoreCase(tc.type())))
                        .toList();
                int cap = pipelineProperties.getMaxExistingTestCasesPerCategory();
                if (existingForCategory.size() > cap) {
                    // Most recently added entries for this category, on the
                    // heuristic that recent work is more likely to be touched
                    // again than something added long ago - see PipelineProperties.
                    existingForCategory = existingForCategory.subList(existingForCategory.size() - cap, existingForCategory.size());
                }

                String categorySystemPrompt = promptBuilder.businessTestCaseSystemPrompt(group, apiOnly);

                // One LLM call per batch of changed files, so a merge larger
                // than the batch size still gets EVERY file in front of the
                // model for this category. The dependency/impact context stays
                // global (computed once over all files) - only the diff payload
                // is split, since that's what actually drives prompt size.
                List<List<ChangedFile>> batches = partition(changedFiles, batchSize);
                boolean categoryFullyCovered = true;
                int casesBeforeGroup = merged.size();

                for (int i = 0; i < batches.size(); i++) {
                    List<ChangedFile> batch = batches.get(i);
                    final List<ManualTestCase> existing = existingForCategory;

                    // Each (category, batch) is an independent LLM call - one
                    // failing (e.g. every rotation candidate exhausted) must not
                    // discard the batches or categories that already succeeded.
                    // A category only counts as generated when EVERY batch
                    // succeeded, so a partial failure leaves it in
                    // missingCategories and a later resume re-runs it.
                    boolean covered = generateWithSplitting(
                            batch,
                            categorySystemPrompt,
                            files -> promptBuilder.buildBusinessTestCaseUserPrompt(
                                    files, classesFor(files, classes), impact, existing,
                                    relatedImplementation),
                            response -> {
                                List<ManualTestCase> cases = manualTestCaseGenerator.parse(
                                        response, capitalizedCategory, idCursor[0], groupForParser);
                                idCursor[0] += (int) cases.stream().filter(tc -> "NEW".equals(tc.action())).count();
                                merged.addAll(cases);
                            },
                            llmKeyStore.resolveFor(request.getLlmKeys()),
                            "Categories %s batch %d/%d".formatted(group, i + 1, batches.size()));

                    if (!covered) {
                        categoryFullyCovered = false;
                    }
                }

                // The whole group shares one call, so it succeeds or fails as a
                // unit - every category in it is marked accordingly, and a
                // resume re-runs the group's categories together.
                if (categoryFullyCovered) {
                    generatedCategories.addAll(group);
                    if (merged.size() == casesBeforeGroup) {
                        emptyCategories.addAll(group);
                        log.info("Categories {} produced no test cases - the model was asked for them and "
                                + "answered that none apply to this change. Counted as covered, not retried.", group);
                    }
                } else {
                    failedCategories.addAll(group);
                }
            }

            // Configuration files get their own dedicated category/prompt,
            // same NEW-vs-UPDATE treatment against the master catalog as
            // every other category - the category name "Configuration"
            // keeps them a distinct, filterable Type in the CSV rather than
            // folding them into POSITIVE/NEGATIVE/etc, since a config-file
            // check isn't really any of those.
            if (pipelineProperties.isConfigChangeDetectionEnabled() && !configFiles.isEmpty()
                    && expectedCategories.contains(CONFIG_CATEGORY)) {
                List<ManualTestCase> existingConfigCases = masterCatalog.stream()
                        .filter(tc -> "Configuration".equalsIgnoreCase(tc.type()))
                        .toList();
                int cap = pipelineProperties.getMaxExistingTestCasesPerCategory();
                if (existingConfigCases.size() > cap) {
                    existingConfigCases = existingConfigCases.subList(existingConfigCases.size() - cap, existingConfigCases.size());
                }

                String configSystemPrompt = promptBuilder.configTestCaseSystemPrompt();

                // Config files are batched on the same setting - they used to
                // bypass the limit entirely, so a merge touching hundreds of
                // .yml files sent all of them in a single prompt.
                List<List<ChangedFile>> configBatches = partition(configFiles, batchSize);
                boolean configFullyCovered = true;
                int casesBeforeConfig = merged.size();

                for (int i = 0; i < configBatches.size(); i++) {
                    final List<ManualTestCase> existingConfig = existingConfigCases;
                    boolean covered = generateWithSplitting(
                            configBatches.get(i),
                            configSystemPrompt,
                            files -> promptBuilder.buildConfigTestCaseUserPrompt(files, existingConfig),
                            response -> {
                                List<ManualTestCase> cases = manualTestCaseGenerator.parse(
                                        response, "Configuration", idCursor[0]);
                                idCursor[0] += (int) cases.stream().filter(tc -> "NEW".equals(tc.action())).count();
                                merged.addAll(cases);
                            },
                            llmKeyStore.resolveFor(request.getLlmKeys()),
                            "Configuration batch %d/%d".formatted(i + 1, configBatches.size()));

                    if (!covered) {
                        configFullyCovered = false;
                    }
                }

                if (configFullyCovered) {
                    generatedCategories.add(CONFIG_CATEGORY);
                    if (merged.size() == casesBeforeConfig) {
                        emptyCategories.add(CONFIG_CATEGORY);
                    }
                } else {
                    failedCategories.add(CONFIG_CATEGORY);
                }
            }

            businessTestCases = merged;

            if (!businessTestCases.isEmpty()) {
                manualCsvPath = manualTestCaseGenerator.writeCsv(businessTestCases, runOutputDir, "manual_test_cases.csv");
                // Merge into the master catalog: NEW cases are appended,
                // UPDATE cases replace the existing row they reference - see
                // ManualTestCaseGenerator.upsertMasterCsv. This is what makes
                // "existing test cases needing an update" actually update in
                // place instead of accumulating a duplicate every merge.
                manualTestCaseGenerator.upsertMasterCsv(
                        businessTestCases, outputDir, "all_manual_test_cases.csv", request.getHeadRef());
            }
        }

        // Automated test code only makes sense against actual source changes -
        // a config-only merge (empty classes) has nothing to generate tests for.
        if (!classes.isEmpty() && (mode == TestCaseMode.AUTOMATED || mode == TestCaseMode.BOTH)) {
            String codeSystemPrompt = promptBuilder.systemPrompt(classes);
            List<List<ChangedFile>> codeBatches = partition(changedFiles, batchSize);
            List<TestCaseResult> allGenerated = new ArrayList<>();
            List<String> batchFailures = new ArrayList<>();

            for (int i = 0; i < codeBatches.size(); i++) {
                boolean covered = generateWithSplitting(
                        codeBatches.get(i),
                        codeSystemPrompt,
                        files -> promptBuilder.buildUserPrompt(files, classesFor(files, classes), impact),
                        response -> allGenerated.addAll(testCaseGenerator.generateAndWrite(response, runOutputDir)),
                        llmKeyStore.resolveFor(request.getLlmKeys()),
                        "Automated batch %d/%d".formatted(i + 1, codeBatches.size()));

                if (!covered) {
                    batchFailures.add("batch " + (i + 1));
                }
            }

            generatedTests = allGenerated;
            if (!batchFailures.isEmpty()) {
                automatedFailure = "%d of %d batch(es) failed - %s"
                        .formatted(batchFailures.size(), codeBatches.size(), batchFailures);
            }
        }

        long newCaseCount = businessTestCases.stream().filter(tc -> "NEW".equals(tc.action())).count();
        long updatedCaseCount = businessTestCases.size() - newCaseCount;
        String summary = "Processed %d changed source file(s) and %d changed config file(s), parsed %d class(es), impact radius %d class(es). Mode=%s: %d business test case(s) (%d new, %d updated), %d automated test file(s)."
                .formatted(changedFiles.size(), configFiles.size(), classes.size(), impact.impactedClasses().size(),
                        mode, businessTestCases.size(), newCaseCount, updatedCaseCount, generatedTests.size());

        if (!impact.impactedMethods().isEmpty()) {
            summary += " Method-level impact radius: %d method(s).".formatted(impact.impactedMethods().size());
        }
        if (changedFiles.size() > batchSize) {
            summary += " Files were processed in %d batch(es) of up to %d - all %d file(s) covered."
                    .formatted(batchCount(changedFiles.size(), batchSize), batchSize, changedFiles.size());
        }
        if (!failedCategories.isEmpty()) {
            summary += " WARNING: %d categor%s failed and produced no test cases: %s.".formatted(
                    failedCategories.size(), failedCategories.size() == 1 ? "y" : "ies", failedCategories);
        }
        if (automatedFailure != null) {
            summary += " WARNING: automated test generation failed: " + automatedFailure;
        }
        if (!emptyCategories.isEmpty()) {
            summary += " %d categor%s applicable to this change and correctly returned no test cases: %s."
                    .formatted(emptyCategories.size(), emptyCategories.size() == 1 ? "y was not" : "ies were not",
                            emptyCategories);
        }

        List<String> missingCategories = expectedCategories.stream()
                .filter(c -> !generatedCategories.contains(c))
                .toList();
        int coveragePercent = expectedCategories.isEmpty() ? 100
                : (int) Math.round(100.0 * generatedCategories.size() / expectedCategories.size());
        MergeStatus status = missingCategories.isEmpty() ? MergeStatus.SUCCESS
                : (generatedCategories.isEmpty() ? MergeStatus.FAILED : MergeStatus.PARTIAL);

        if (!missingCategories.isEmpty()) {
            summary += " Coverage %d%% - missing categor%s: %s (a later run will regenerate just these)."
                    .formatted(coveragePercent, missingCategories.size() == 1 ? "y" : "ies", missingCategories);
        }

        return new GenerateTestsResponse(
                changedFiles.size() + configFiles.size(),
                classes.size(),
                impact.impactedClasses().size(),
                impact.impactedMethods().size(),
                commitLog,
                businessTestCases,
                manualCsvPath,
                downloadUrlFor(projectName, request.getHeadRef(), manualCsvPath),
                generatedTests,
                expectedCategories,
                List.copyOf(generatedCategories),
                missingCategories,
                coveragePercent,
                status,
                summary
        );
    }

    /** Category name used for config-file test cases - kept in one place since it's both a prompt label and a coverage key. */
    private static final String CONFIG_CATEGORY = "CONFIGURATION";

    /**
     * Sends one LLM call for {@code files}, splitting the file set in half and
     * recursing whenever the prompt is too large - so an oversized change is
     * broken down until it fits rather than failing and leaving those diffs
     * unprocessed.
     *
     * <p>Two independent triggers, because our estimate and the model's real
     * limit can disagree:
     * <ul>
     *   <li><b>Before sending</b> - the built prompt exceeds
     *       aiqa.pipeline.max-prompt-chars.</li>
     *   <li><b>After sending</b> - the provider rejected it as too long
     *       (see {@link #isPromptTooLarge}). Splitting on this is what stops a
     *       context-window overflow from silently costing a whole category:
     *       previously every model in the chain got the same oversized prompt,
     *       all failed identically, and the diffs were simply never covered.</li>
     * </ul>
     *
     * <p>Recursion bottoms out at a single file, whose diff is already capped
     * at 4000 chars - if even that is rejected, the failure is real and is
     * reported rather than retried forever.
     *
     * @return true when every file in {@code files} was successfully covered.
     */
    private boolean generateWithSplitting(List<ChangedFile> files,
                                           String systemPrompt,
                                           java.util.function.Function<List<ChangedFile>, String> userPromptBuilder,
                                           java.util.function.Consumer<String> responseHandler,
                                           LlmKeys keys,
                                           String label) {
        if (files.isEmpty()) {
            return true;
        }

        String userPrompt = userPromptBuilder.apply(files);
        int budget = pipelineProperties.getMaxPromptChars();

        if (userPrompt.length() + systemPrompt.length() > budget && files.size() > 1) {
            log.info("{}: prompt for {} file(s) is {} chars (budget {}) - splitting in half so no diff is skipped.",
                    label, files.size(), userPrompt.length() + systemPrompt.length(), budget);
            return splitAndRecurse(files, systemPrompt, userPromptBuilder, responseHandler, keys, label);
        }

        try {
            String response = llmClient.complete(systemPrompt, userPrompt, keys);
            responseHandler.accept(response);
            return true;
        } catch (Exception e) {
            if (com.company.aiqa.ai.router.ProviderCooldownRegistry.isPromptTooLarge(e) && files.size() > 1) {
                log.warn("{}: provider rejected the prompt for {} file(s) as too long - splitting and retrying "
                        + "rather than dropping these diffs.", label, files.size());
                return splitAndRecurse(files, systemPrompt, userPromptBuilder, responseHandler, keys, label);
            }
            // One line per failed batch, not a stack trace each time: a run with
            // no working provider fails identically for every batch and category,
            // and printing the full trace 12 times buried the single fact that
            // mattered under ~2,000 lines of Tomcat frames. The stack adds nothing
            // here anyway - the path is always the same - so it goes to debug.
            log.error("{}: LLM call failed for {} file(s): {}", label, files.size(), com.company.aiqa.ai.router.ProviderLogs.compactFailure(e));
            log.debug("{}: full failure detail", label, e);
            return false;
        }
    }

    private boolean splitAndRecurse(List<ChangedFile> files,
                                     String systemPrompt,
                                     java.util.function.Function<List<ChangedFile>, String> userPromptBuilder,
                                     java.util.function.Consumer<String> responseHandler,
                                     LlmKeys keys,
                                     String label) {
        int mid = files.size() / 2;
        // Both halves are attempted even if the first fails, so one bad half
        // doesn't hide the other half's results.
        boolean first = generateWithSplitting(files.subList(0, mid), systemPrompt, userPromptBuilder,
                responseHandler, keys, label);
        boolean second = generateWithSplitting(files.subList(mid, files.size()), systemPrompt, userPromptBuilder,
                responseHandler, keys, label);
        return first && second;
    }

    /** Splits a list into consecutive chunks of at most {@code size}; a list at or under the limit yields one chunk. */
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

    private static int batchCount(int total, int size) {
        return (total + size - 1) / size;
    }

    /**
     * The parsed classes belonging to one batch of changed files. Parsing and
     * dependency/impact analysis still run over ALL changed files (they're
     * local and cheap); only the per-prompt payload is split, so each batch
     * still gets the full, global impact picture as context.
     */
    private static List<ClassInfo> classesFor(List<ChangedFile> batch, List<ClassInfo> allClasses) {
        java.util.Set<String> paths = batch.stream()
                .map(ChangedFile::path)
                .collect(java.util.stream.Collectors.toSet());
        return allClasses.stream()
                .filter(c -> c.sourceFilePath() != null && paths.contains(c.sourceFilePath()))
                .toList();
    }

    /**
     * The categories a run should produce: the configured set (plus
     * CONFIGURATION when config files changed), or - when resuming a
     * partially-generated merge - only the subset the caller asked for.
     * Unknown names in onlyCategories are dropped rather than trusted, so a
     * stale history entry can't make the pipeline generate something that
     * isn't configured.
     */
    private List<String> resolveCategories(GenerateTestsRequest request, boolean hasConfigChanges) {
        List<String> all = new ArrayList<>(pipelineProperties.getTestCaseCategories());
        if (hasConfigChanges && pipelineProperties.isConfigChangeDetectionEnabled()) {
            all.add(CONFIG_CATEGORY);
        }

        List<String> only = request.getOnlyCategories();
        if (only == null || only.isEmpty()) {
            return all;
        }
        List<String> restricted = all.stream().filter(only::contains).toList();
        if (restricted.isEmpty()) {
            log.warn("onlyCategories {} matched none of the configured categories {} - falling back to all.",
                    only, all);
            return all;
        }
        log.info("Resuming generation for {} of {} categor(y/ies): {}", restricted.size(), all.size(), restricted);
        return restricted;
    }

    /**
     * Self-service entry point: given just a repo URL, an optional credential,
     * and a branch name, clones/fetches the repo, finds the most recent merge
     * commit on that branch, and runs the full pipeline against exactly that
     * merge's before/after state - no local checkout or manual ref lookup required.
     *
     * History-aware: if that merge was already processed (by a prior call to
     * this method, a webhook, or a backfill), this is a no-op unless
     * request.force is true. This is what lets a webhook (or a cron hitting
     * this endpoint) safely fire on every merge without doing duplicate work,
     * once /generate-tests-from-branch/backfill has covered everything older.
     */
    public GenerateTestsResponse runFromBranch(GenerateTestsFromBranchRequest request) {
        requireLlmCredentials(request.getLlmKeys());
        CredentialScheme scheme = resolveScheme(request.getProvider());
        String localPath = repoSyncService.syncRepo(request.getRepoUrl(), request.getAccessToken(), scheme);

        MergeCommitInfo mergeInfo = gitDiffService.findLatestMergeCommit(localPath, request.getBranch());

        boolean alreadyProcessed = mergeHistoryService.isProcessed(
                request.getRepoUrl(), request.getBranch(), mergeInfo.mergeSha());
        if (alreadyProcessed && !Boolean.TRUE.equals(request.getForce())) {
            log.info("Merge {} on branch '{}' already processed - skipping (pass \"force\": true to reprocess).",
                    mergeInfo.mergeSha(), request.getBranch());
            return new GenerateTestsResponse(0, 0, 0, 0, List.of(), List.of(), null, null, List.of(),
                    List.of(), List.of(), List.of(), 100, MergeStatus.SUCCESS,
                    "Merge %s on branch '%s' was already processed previously - nothing new to do. "
                            .formatted(mergeInfo.mergeSha(), request.getBranch())
                            + "Run /generate-tests-from-branch/backfill if you think history is out of date, "
                            + "or pass \"force\": true to reprocess this merge anyway.");
        }

        // Number this run the same way a backfill would, so a folder's sequence
        // means the same thing however it was produced. Costs one extra
        // first-parent walk - pure git object reads, and negligible next to the
        // LLM calls this method is about to make.
        GenerateTestsRequest inner = buildInnerRequest(request, localPath, mergeInfo,
                sequenceOf(localPath, request.getBranch(), mergeInfo.mergeSha()));

        log.info("Resolved latest merge on branch '{}': {} -> {}",
                request.getBranch(), mergeInfo.preMergeSha(), mergeInfo.mergeSha());

        return runAndRecord(request, mergeInfo, inner);
    }

    /**
     * Builds the inner per-merge request, carrying through output dir, mode
     * and LLM keys - and, when this merge is being RESUMED after a partial
     * run, restricting generation to just the categories still missing so
     * completed work is never regenerated.
     */
    private GenerateTestsRequest buildInnerRequest(GenerateTestsFromBranchRequest request, String localPath,
                                                    MergeCommitInfo mergeInfo, Integer commitSequence) {
        GenerateTestsRequest inner = new GenerateTestsRequest();
        inner.setRepoPath(localPath);
        inner.setBaseRef(mergeInfo.preMergeSha());
        inner.setHeadRef(mergeInfo.mergeSha());
        inner.setCommitSequence(commitSequence);
        if (request.getOutputDir() != null) {
            inner.setOutputDir(request.getOutputDir());
        }
        inner.setTestCaseMode(request.getTestCaseMode());
        inner.setLlmKeys(request.getLlmKeys());

        mergeHistoryService.findEntry(request.getRepoUrl(), request.getBranch(), mergeInfo.mergeSha())
                .filter(MergeHistoryEntry::isIncomplete)
                .filter(e -> !e.missingCategories().isEmpty())
                .ifPresent(e -> {
                    log.info("Merge {} is {} ({}% covered) - resuming only the missing categor(y/ies): {}",
                            mergeInfo.mergeSha(), e.status(), e.coveragePercent(), e.missingCategories());
                    inner.setOnlyCategories(e.missingCategories());
                });
        return inner;
    }

    /**
     * Runs one merge and records the outcome - including on failure, which is
     * the point: a merge that throws is recorded as FAILED (with the reason)
     * rather than silently left absent from history, so it can be found and
     * retried instead of being invisible.
     */
    private GenerateTestsResponse runAndRecord(GenerateTestsFromBranchRequest request, MergeCommitInfo mergeInfo,
                                                GenerateTestsRequest inner) {
        try {
            GenerateTestsResponse response = run(inner);
            mergeHistoryService.recordAttempt(
                    request.getRepoUrl(), request.getBranch(), mergeInfo.mergeSha(), mergeInfo.preMergeSha(),
                    response.summary(), response.status(),
                    response.missingCategories().isEmpty() ? null
                            : "Categories not generated: " + response.missingCategories(),
                    response.expectedCategories(), response.generatedCategories(),
                    response.businessTestCases().size());
            return response;
        } catch (Exception e) {
            mergeHistoryService.recordAttempt(
                    request.getRepoUrl(), request.getBranch(), mergeInfo.mergeSha(), mergeInfo.preMergeSha(),
                    "FAILED: " + e.getMessage(), MergeStatus.FAILED, e.getMessage(),
                    inner.getOnlyCategories() != null ? inner.getOnlyCategories() : List.of(),
                    List.of(), 0);
            throw e;
        }
    }

    /**
     * Catches a branch up on its entire merge history: walks every merge
     * commit reachable from request.branch (oldest first), skips any already
     * recorded in this repo+branch's merge history, and runs the pipeline
     * against the rest - recording each as processed as it goes.
     *
     * Safe to call repeatedly, including on a schedule: once a branch is
     * fully covered, later calls just report everything as already
     * processed and do no LLM work. That's the intended steady state -
     * run this once (or on a schedule) to establish/maintain history, and
     * let /generate-tests-from-branch (directly, or via a webhook) handle
     * new merges as they land without reprocessing anything older.
     *
     * A single merge failing (e.g. a transient LLM error) does not abort the
     * run or get marked as processed - it's recorded in the results as a
     * failure so a subsequent backfill call will retry just that merge.
     */
    public GenerateTestsBackfillResponse runBackfillAndCatchUp(GenerateTestsFromBranchRequest request) {
        requireLlmCredentials(request.getLlmKeys());
        CredentialScheme scheme = resolveScheme(request.getProvider());
        String localPath = repoSyncService.syncRepo(request.getRepoUrl(), request.getAccessToken(), scheme);

        List<MergeCommitInfo> allMerges = gitDiffService.findAllMergeCommits(localPath, request.getBranch());
        List<MergeRunResult> results = new ArrayList<>();
        int alreadyProcessed = 0;
        int succeeded = 0;
        int failed = 0;
        int partial = 0;
        int resumed = 0;

        for (int index = 0; index < allMerges.size(); index++) {
            MergeCommitInfo merge = allMerges.get(index);

            // 1-based position in the FULL chronological list, assigned before
            // the skip below so a commit keeps the same number whether or not
            // earlier ones were already complete on this run.
            int commitSequence = index + 1;

            if (mergeHistoryService.isProcessed(request.getRepoUrl(), request.getBranch(), merge.mergeSha())) {
                alreadyProcessed++;
                continue;
            }

            GenerateTestsRequest inner = buildInnerRequest(request, localPath, merge, commitSequence);
            boolean isResume = inner.getOnlyCategories() != null;

            GenerateTestsResponse response;
            try {
                response = runAndRecord(request, merge, inner);
            } catch (Exception e) {
                log.error("Backfill: failed to process merge {} on branch '{}': {}",
                        merge.mergeSha(), request.getBranch(), e.getMessage(), e);
                failed++;
                results.add(new MergeRunResult(merge.mergeSha(), merge.preMergeSha(), Instant.now().toString(),
                        new GenerateTestsResponse(0, 0, 0, 0, List.of(), List.of(), null, null, List.of(),
                                List.of(), List.of(), List.of(), 0, MergeStatus.FAILED,
                                "FAILED: " + e.getMessage() + " (recorded as FAILED - a later backfill will retry it)")));
                continue;
            }

            if (response.status() == MergeStatus.SUCCESS) {
                succeeded++;
            } else {
                // Generated something but not everything - counted separately
                // so "caught up" doesn't claim completion while gaps remain.
                partial++;
            }
            if (isResume) {
                resumed++;
            }
            results.add(new MergeRunResult(merge.mergeSha(), merge.preMergeSha(), Instant.now().toString(), response));
        }

        boolean caughtUp = (failed == 0 && partial == 0);
        String summary = ("Branch '%s': %d merge commit(s) total, %d already complete, %d newly completed, "
                + "%d partially generated, %d failed%s.%s")
                .formatted(request.getBranch(), allMerges.size(), alreadyProcessed, succeeded, partial, failed,
                        resumed > 0 ? " (" + resumed + " of them resumed from a previous partial run)" : "",
                        caughtUp ? " Fully caught up."
                                : " NOT fully caught up - rerun to finish the incomplete merge(s); only their missing categories will be regenerated.");
        log.info(summary);

        return new GenerateTestsBackfillResponse(
                allMerges.size(), alreadyProcessed, succeeded, failed, results, caughtUp, summary);
    }

    /**
     * Relative URL for downloading this run's CSV, or null when the run wrote
     * no CSV (nothing generated) - a link to a file that isn't there is worse
     * than no link, since a caller would retry it as though it were transient.
     *
     * <p>Deliberately relative: the service has no reliable view of the host,
     * scheme or proxy prefix the caller reached it through, and guessing wrong
     * produces a URL that looks authoritative and doesn't work.
     */
    /**
     * Character budget for the collaborator source fed to the manual prompt.
     * Large enough to carry the services a change actually reaches, small enough
     * that it cannot crowd out the diff or the existing test cases.
     */
    private static final int MAX_RELATED_SOURCE_CHARS = 50_000;

    /**
     * Source of the classes the change reaches into but the diff does not show.
     *
     * <p>Impact analysis already knows WHICH classes a change touches, but it
     * carries only their names, so the prompt has never seen the code that
     * decides how the feature behaves. That code is where preconditions live -
     * a service that refuses to act until a parent record exists, a validator
     * that rejects a value, a default filled in when a field is omitted. Without
     * it the model writes cases whose setup the system can never satisfy, and
     * expected results that contradict what the code plainly does.
     *
     * <p>Deliberately driven by the impact graph rather than by any feature or
     * project: whatever a commit touches, its collaborators come along.
     * Files already present in the diff are skipped - the model has those.
     */
    /**
     * The local checkout to work from: the caller's path when given, otherwise
     * a clone of repoUrl.
     *
     * <p>repoPath wins when both are set - naming an exact directory is an
     * explicit instruction, and silently cloning over it would be surprising.
     * With only a URL, RepoSyncService reuses any existing clone and fetches
     * it, so this is cheap to call repeatedly for different commits of one
     * repo.
     */
    /**
     * Refuses to start when there is no model to call.
     *
     * <p>Checked before any git or LLM work: without this the run clones,
     * diffs, parses and builds every prompt, then fails on the first call with
     * a provider-level error that reads like an outage rather than like
     * "nobody configured a key". Failing here says exactly what to do.
     *
     * <p>Credentials may come from three places, and any one is enough: keys on
     * the request, keys set via POST /api/v1/llm-keys, or server configuration.
     */
    private void requireLlmCredentials(LlmKeys requestKeys) {
        boolean onRequest = requestKeys != null && !requestKeys.isEmpty();
        if (onRequest || llmKeyStore.isConfigured() || llmClient.hasServerSideCredentials()) {
            return;
        }
        throw new IllegalStateException(
                "No LLM credentials are configured, so there is no model to generate with. "
                        + "Configure them once with POST /api/v1/llm-keys, e.g. "
                        + "{\"gemini\": \"...\", \"mistral\": \"...\"} - after that no generation "
                        + "request needs an \"llmKeys\" field. GET /api/v1/llm-keys lists every "
                        + "provider this build supports.");
    }

    private String resolveRepoCheckout(GenerateTestsRequest request) {
        String repoPath = request.getRepoPath();
        if (repoPath != null && !repoPath.isBlank()) {
            return repoPath;
        }
        String repoUrl = request.getRepoUrl();
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "Supply either \"repoPath\" (a checkout on this machine) or \"repoUrl\" "
                            + "(cloned on demand) so there is something to diff.");
        }
        String localPath = repoSyncService.syncRepo(
                repoUrl, request.getAccessToken(), resolveScheme(request.getProvider()));
        log.info("Resolved repoUrl {} to local checkout {}", repoUrl, localPath);
        return localPath;
    }

    private String collectRelatedImplementation(GenerateTestsRequest request,
                                                ImpactResult impact,
                                                List<ChangedFile> changedFiles) {
        java.util.Set<String> alreadyShown = changedFiles.stream()
                .map(ChangedFile::path)
                .collect(java.util.stream.Collectors.toSet());

        java.util.Set<String> wantedSimpleNames = new java.util.LinkedHashSet<>();
        impact.impactedClasses().forEach(name ->
                wantedSimpleNames.add(name.substring(name.lastIndexOf('.') + 1)));
        impact.directlyChangedClasses().forEach(name ->
                wantedSimpleNames.add(name.substring(name.lastIndexOf('.') + 1)));
        if (wantedSimpleNames.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        int included = 0;
        try (GitDiffService.SourceAtCommit source =
                     gitDiffService.openSourceAtCommit(request.getRepoPath(), request.getHeadRef())) {
            for (String path : source.paths()) {
                if (alreadyShown.contains(path) || !SourceLanguage.isSupported(path)) {
                    continue;
                }
                String fileName = path.substring(path.lastIndexOf('/') + 1);
                String simpleName = fileName.contains(".")
                        ? fileName.substring(0, fileName.lastIndexOf('.'))
                        : fileName;
                if (!wantedSimpleNames.contains(simpleName)) {
                    continue;
                }
                String body = source.read(path);
                if (body == null || body.isBlank()) {
                    continue;
                }
                if (sb.length() + body.length() > MAX_RELATED_SOURCE_CHARS) {
                    // Stop at a file boundary: half a class can read as though a
                    // check simply is not there, which is worse than omitting it.
                    break;
                }
                sb.append("### ").append(path).append("\n```java\n")
                        .append(body).append("\n```\n\n");
                included++;
            }
        } catch (Exception e) {
            // Context is an enhancement, never a reason to fail generation.
            log.warn("Could not read related implementation ({}), generating from the diff alone.",
                    e.getMessage());
            return "";
        }

        log.info("Related implementation for the prompt: {} collaborator file(s), {} chars.",
                included, sb.length());
        return sb.toString();
    }

    private String downloadUrlFor(String projectName, String commitRef, String csvPath) {
        if (csvPath == null || projectName == null || projectName.isBlank()) {
            return null;
        }
        String query = "projectName=" + URLEncoder.encode(projectName, StandardCharsets.UTF_8);
        if (commitRef != null && !commitRef.isBlank()) {
            query += "&commitHash=" + URLEncoder.encode(commitRef, StandardCharsets.UTF_8);
        }
        return "/api/v1/test-cases/download?" + query;
    }

    /**
     * Maps the optional GenerateTestsFromBranchRequest.provider hint to a
     * CredentialScheme. Returns null (meaning "auto-detect from hostname")
     * when unset or unrecognized rather than failing the request.
     */
    private CredentialScheme resolveScheme(String provider) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        return switch (provider.trim().toUpperCase()) {
            case "GITHUB" -> CredentialScheme.TOKEN_AS_USERNAME;
            case "GITLAB", "GENERIC" -> CredentialScheme.TOKEN_AS_PASSWORD;
            default -> {
                log.warn("Unrecognized provider hint '{}' - auto-detecting from repo URL instead", provider);
                yield null;
            }
        };
    }

    private TestCaseMode resolveMode(String requested) {
        String value = (requested != null && !requested.isBlank())
                ? requested
                : pipelineProperties.getDefaultTestCaseMode();
        try {
            return TestCaseMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unrecognized testCaseMode '{}', falling back to MANUAL", value);
            return TestCaseMode.MANUAL;
        }
    }

    /**
     * This commit's 1-based position in the branch's chronological history, or
     * null when it can't be determined - in which case the folder simply keeps
     * its bare hash rather than failing the run over a cosmetic prefix.
     */
    private Integer sequenceOf(String localPath, String branch, String mergeSha) {
        try {
            List<MergeCommitInfo> allMerges = gitDiffService.findAllMergeCommits(localPath, branch);
            for (int i = 0; i < allMerges.size(); i++) {
                if (allMerges.get(i).mergeSha().equals(mergeSha)) {
                    return i + 1;
                }
            }
        } catch (Exception e) {
            log.warn("Could not determine the sequence number for {} on '{}': {}", mergeSha, branch, e.getMessage());
        }
        return null;
    }

    /**
     * The run folder for one commit: {@code <seq>.<commitHash>} when the
     * sequence is known, plain {@code <commitHash>} otherwise.
     *
     * <p>The sequence prefix exists so the folder listing reads in the order
     * the merges actually landed. Sorting is left to whoever reads it - note
     * that plain lexical sort puts 10 before 2, so the numbers are for humans
     * scanning the list, not a sort key.
     *
     * <p>The dot is deliberate and must survive sanitising: it separates the
     * sequence from the hash unambiguously, which is what lets the automation
     * side find a commit's folder again by matching on the hash after it.
     */
    private String runFolderName(String headRef, Integer sequence) {
        String hash = sanitizeForPath(headRef);
        return sequence == null ? hash : sequence + "." + hash;
    }

    /** Turns a ref (sha, branch name, etc.) into a filesystem-safe folder name. */
    private String sanitizeForPath(String ref) {
        if (ref == null || ref.isBlank()) {
            return "run";
        }
        String safe = ref.replaceAll("[^a-zA-Z0-9]+", "_");
        return safe.length() > 60 ? safe.substring(0, 60) : safe;
    }

    /** POSITIVE -> Positive, for a readable Type column in the CSV. */
    private String capitalize(String category) {
        if (category == null || category.isBlank()) return category;
        String lower = category.toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
