package com.company.aiqa.service;

import com.company.aiqa.ai.LlmClient;
import com.company.aiqa.ai.PromptBuilder;
import com.company.aiqa.config.CredentialScheme;
import com.company.aiqa.config.PipelineProperties;
import com.company.aiqa.dependency.DependencyService;
import com.company.aiqa.git.GitDiffService;
import com.company.aiqa.git.MergeHistoryService;
import com.company.aiqa.git.ProjectDocReader;
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

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    /**
     * Name of the note left in a commit's run folder whenever the run produced
     * no manual CSV - whether nothing changed, every category failed, or the
     * model correctly had nothing to say. Its whole content is the run summary
     * the API response carries, so the folder and the response never disagree
     * about why there are no test cases.
     *
     * <p>One filename for all of those on purpose: the rule a reader needs is
     * "a commit folder with no CSV has a note saying why", and that only works
     * if there is one name to look for.
     */
    private static final String NO_TEST_CASES_FILE = "no-test-cases.txt";

    /** The README snapshot synced into each run's folder as the context it was generated against. */
    private static final String README_SNAPSHOT_FILE = "README.snapshot.md";

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
    private final GeneratedRunLocator runLocator;

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
                              LlmKeyStore llmKeyStore,
                              GeneratedRunLocator runLocator) {
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
        this.runLocator = runLocator;
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
        //
        // An existing folder for this commit is reused whenever one is found -
        // checked via GeneratedRunLocator, which already knows both shapes a
        // folder can be named (backfill's "<seq>.<hash>", or the bare hash a
        // caller gets by leaving commitSequence unset). Without this, a direct
        // POST /generate-tests for a commit backfill had already produced -
        // made with no commitSequence, or the wrong one - created a SIBLING
        // folder instead of finding the real one, so a resume's onlyCategories
        // merged into a folder holding nothing to merge with. Only a commit
        // with NO folder yet falls through to fresh creation, which is where
        // commitSequence still matters - it is what gives a backfilled folder
        // its ordering the very first time.
        String located = runLocator.runOutputDir(baseOutputDir, projectName, request.getHeadRef());
        String runOutputDir = resolveRunOutputDir(Files.isDirectory(Path.of(located)), located, outputDir,
                request.getHeadRef(), request.getCommitSequence());

        // The project README as architectural/business context (see
        // ProjectDocReader), plus whether THIS commit changed it. Read once and
        // reused for every category/batch, same as the collaborator source.
        // whether-changed is a third change signal beside source and config: a
        // commit that only edits the README changes neither, so without this its
        // refreshed intent would never reach a run at all.
        boolean readmeChanged = gitDiffService.computeChangedReadme(
                request.getRepoPath(), request.getBaseRef(), request.getHeadRef()).isPresent();
        final String projectReadme = collectProjectReadme(request);
        // Sync the README locally beside the generated tests, so each processed
        // commit's folder records the exact architectural context it was
        // generated against - traceable, and downloadable with the run.
        persistReadmeSnapshot(runOutputDir, projectReadme);

        if (changedFiles.isEmpty() && configFiles.isEmpty()) {
            if (readmeChanged) {
                // A README-only change is NOT "nothing changed": the business
                // context shifted. There is no source or config diff to generate
                // cases from, so none are produced - but the commit is recorded
                // as processed (SUCCESS), with a note saying the context was
                // refreshed, rather than skipped as empty and re-attempted
                // forever. The refreshed README informs the next code change.
                String readmeOnly = ("Only the project README changed between %s and %s - architectural/business "
                        + "context refreshed and synced locally. No source or configuration change, so no new test "
                        + "cases were generated; the updated README informs the next code change's cases.")
                        .formatted(request.getBaseRef(), request.getHeadRef());
                writeRunNote(runOutputDir, readmeOnly);
                return new GenerateTestsResponse(0, 0, 0, 0, commitLog, List.of(), null, null, List.of(),
                        List.of(), List.of(), List.of(), 100, MergeStatus.SUCCESS, readmeOnly);
            }
            // Nothing to generate is a legitimately COMPLETE outcome, not a
            // gap - otherwise an empty merge would be re-attempted forever.
            String noChanges = "No changed source or configuration files found between %s and %s.".formatted(
                    request.getBaseRef(), request.getHeadRef());
            // The commit still gets its folder, holding that one sentence.
            // Without it the sequence numbers under generated-tests/<project>/
            // have holes, and a hole is indistinguishable from a commit that
            // failed or was never processed - so someone goes looking for
            // output that was never owed. An empty-but-present folder answers
            // the question the numbering raises.
            writeRunNote(runOutputDir, noChanges);
            return new GenerateTestsResponse(0, 0, 0, 0, commitLog, List.of(), null, null, List.of(),
                    List.of(), List.of(), List.of(), 100, MergeStatus.SUCCESS, noChanges);
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
        // Shared and mutable on purpose. Splitting a batch shrinks the DIFF in
        // the prompt but not this - the collaborator source rides along in full
        // on every call, so a run whose fixed context alone exceeds the
        // provider's ceiling fails identically at 40 files and at 1. When
        // splitting runs out of room, this is what gives way, and the smaller
        // value is then used by every later call in the run.
        final java.util.concurrent.atomic.AtomicReference<String> relatedImplementation =
                new java.util.concurrent.atomic.AtomicReference<>(changedFiles.isEmpty()
                        ? ""
                        : collectRelatedImplementation(request, impact, changedFiles));

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
        // Only meaningful on a resume: how many cases the earlier run had
        // produced that this one preserved, and how many the file ends up
        // holding. Without them the summary reports the run's own output as
        // though it were the whole file.
        int keptFromEarlierRun = 0;
        int casesInCsv = 0;
        // A category whose batches did not all succeed, but which produced
        // cases from the ones that did. It is neither covered nor empty, and
        // reporting it as "failed and produced no test cases" - which the
        // summary did - is false in front of the cases themselves.
        List<String> partiallyGeneratedCategories = new ArrayList<>();
        int casesFromIncompleteCategories = 0;
        // Categories this change gave no input to at all - the source-diff
        // categories on a config-only merge. Not a failure and not an
        // achievement; see the batches.isEmpty() branch below.
        List<String> notApplicableCategories = new ArrayList<>();

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

                // No batches means no source files, so nothing was ever asked
                // of these categories. Crediting them as covered - which the
                // loop below did by leaving categoryFullyCovered at its initial
                // true - is what let a config-only merge whose ONLY real call
                // failed be recorded as SUCCESS at 100%. It also put
                // CONFIGURATION in generatedCategories from here while the
                // dedicated config block below put it in failedCategories, so
                // one summary reported it as both. Not applicable is a third
                // outcome: neither generated nor missing, and excluded from the
                // coverage denominator so it is never retried forever either.
                if (batches.isEmpty()) {
                    notApplicableCategories.addAll(group);
                    continue;
                }

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
                                    relatedImplementation.get(), projectReadme),
                            response -> {
                                List<ManualTestCase> cases = manualTestCaseGenerator.parse(
                                        response, capitalizedCategory, idCursor[0], groupForParser);
                                idCursor[0] += (int) cases.stream().filter(tc -> "NEW".equals(tc.action())).count();
                                merged.addAll(cases);
                            },
                            llmKeyStore.resolveFor(request.getLlmKeys()),
                            "Categories %s batch %d/%d".formatted(group, i + 1, batches.size()),
                            relatedImplementation);

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
                    if (merged.size() > casesBeforeGroup) {
                        // Some batches answered. Those cases are real and are
                        // kept; the category is still incomplete because the
                        // files in the failed batch were never looked at.
                        partiallyGeneratedCategories.addAll(group);
                        casesFromIncompleteCategories += merged.size() - casesBeforeGroup;
                    }
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
                    if (merged.size() > casesBeforeConfig) {
                        partiallyGeneratedCategories.add(CONFIG_CATEGORY);
                        casesFromIncompleteCategories += merged.size() - casesBeforeConfig;
                    }
                }
            }

            businessTestCases = merged;

            if (!businessTestCases.isEmpty()) {
                // A RESUME regenerates only the categories that failed last
                // time, so writing its output over the file would discard every
                // case the earlier run produced for the categories it is NOT
                // touching. A full run has regenerated everything and should
                // replace.
                boolean resume = request.getOnlyCategories() != null && !request.getOnlyCategories().isEmpty();
                if (resume) {
                    ManualTestCaseGenerator.RunCsvMerge write = manualTestCaseGenerator
                            .writeCsvPreservingOtherCategories(businessTestCases, runOutputDir,
                                    "manual_test_cases.csv", expectedCategories);
                    manualCsvPath = write.path();
                    keptFromEarlierRun = write.kept();
                    casesInCsv = write.merged().size();
                } else {
                    manualCsvPath = manualTestCaseGenerator.writeCsv(businessTestCases, runOutputDir,
                            "manual_test_cases.csv");
                    casesInCsv = businessTestCases.size();
                }
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
        // Two different things used to be reported as one. A category that
        // produced nothing and a category that produced eleven cases before a
        // batch failed are both "not covered", but only the first produced no
        // test cases - and the run whose summary said so was returning five.
        summary += categoryWarnings(failedCategories, partiallyGeneratedCategories,
                casesFromIncompleteCategories);
        if (automatedFailure != null) {
            summary += " WARNING: automated test generation failed: " + automatedFailure;
        }
        if (!emptyCategories.isEmpty()) {
            summary += " %d categor%s applicable to this change and correctly returned no test cases: %s."
                    .formatted(emptyCategories.size(), emptyCategories.size() == 1 ? "y was not" : "ies were not",
                            emptyCategories);
        }

        if (!notApplicableCategories.isEmpty()) {
            summary += " %d categor%s no input from this change and %s not attempted: %s."
                    .formatted(notApplicableCategories.size(),
                            notApplicableCategories.size() == 1 ? "y had" : "ies had",
                            notApplicableCategories.size() == 1 ? "was" : "were",
                            notApplicableCategories);
        }

        Coverage coverage = coverageOf(expectedCategories, notApplicableCategories, generatedCategories,
                !businessTestCases.isEmpty() || !generatedTests.isEmpty());
        List<String> missingCategories = coverage.missing();
        int coveragePercent = coverage.coveragePercent();
        MergeStatus status = coverage.status();

        if (!missingCategories.isEmpty()) {
            summary += " Coverage %d%% - missing categor%s: %s (a later run will regenerate just these)."
                    .formatted(coveragePercent, missingCategories.size() == 1 ? "y" : "ies", missingCategories);
        }
        // On a resume the run's own output is not what the file holds, and
        // reporting only the former reads as though the rest had been lost -
        // which, before writeCsvPreservingOtherCategories, it had been.
        if (keptFromEarlierRun > 0) {
            summary += " Resume: kept %d case(s) from categories this run did not regenerate; %s now holds %d."
                    .formatted(keptFromEarlierRun, "manual_test_cases.csv", casesInCsv);
        }

        // Every processed commit leaves a folder. Before this, the CSV write
        // was gated on having cases, so a commit that generated nothing left
        // nothing at all - and a backfill reporting "2 newly completed" against
        // a directory holding one folder is indistinguishable from a backfill
        // that silently skipped them. The note carries the run summary, so the
        // folder says exactly what the API said.
        if (manualCsvPath == null && generatedTests.isEmpty()) {
            writeRunNote(runOutputDir, summary);
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
     * Below this, the surviving implementation context is too fragmentary to
     * tell the model anything - half a method body is worse than none - so the
     * next shrink drops it entirely rather than halving again.
     */
    private static final int MIN_RELATED_IMPLEMENTATION_CHARS = 4_000;

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
        return generateWithSplitting(files, systemPrompt, userPromptBuilder, responseHandler, keys, label, null);
    }

    /**
     * @param shrinkableContext run-level context carried on every call regardless
     *        of how few files remain - the collaborator source. Splitting cannot
     *        reduce it, so when a single file is still rejected as too large this
     *        is what gives way. Null when the caller has no such context.
     */
    private boolean generateWithSplitting(List<ChangedFile> files,
                                           String systemPrompt,
                                           java.util.function.Function<List<ChangedFile>, String> userPromptBuilder,
                                           java.util.function.Consumer<String> responseHandler,
                                           LlmKeys keys,
                                           String label,
                                           java.util.concurrent.atomic.AtomicReference<String> shrinkableContext) {
        if (files.isEmpty()) {
            return true;
        }

        String userPrompt = userPromptBuilder.apply(files);
        int budget = pipelineProperties.getMaxPromptChars();

        if (userPrompt.length() + systemPrompt.length() > budget && files.size() > 1) {
            log.info("{}: prompt for {} file(s) is {} chars (budget {}) - splitting in half so no diff is skipped.",
                    label, files.size(), userPrompt.length() + systemPrompt.length(), budget);
            return splitAndRecurse(files, systemPrompt, userPromptBuilder, responseHandler, keys, label,
                    shrinkableContext);
        }

        try {
            String response = llmClient.complete(systemPrompt, userPrompt, keys);
            responseHandler.accept(response);
            return true;
        } catch (Exception e) {
            if (com.company.aiqa.ai.router.ProviderCooldownRegistry.isPromptTooLarge(e)) {
                if (files.size() > 1) {
                    log.warn("{}: provider rejected the prompt for {} file(s) as too long - splitting and retrying "
                            + "rather than dropping these diffs.", label, files.size());
                    return splitAndRecurse(files, systemPrompt, userPromptBuilder, responseHandler, keys, label,
                            shrinkableContext);
                }
                // One file left and still too large: there is no diff left to
                // split, so the fixed context is the only thing that can go.
                if (shrinkContext(shrinkableContext, label)) {
                    return generateWithSplitting(files, systemPrompt, userPromptBuilder, responseHandler, keys,
                            label, shrinkableContext);
                }
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
                                     String label,
                                     java.util.concurrent.atomic.AtomicReference<String> shrinkableContext) {
        int mid = files.size() / 2;
        // Both halves are attempted even if the first fails, so one bad half
        // doesn't hide the other half's results.
        boolean first = generateWithSplitting(files.subList(0, mid), systemPrompt, userPromptBuilder,
                responseHandler, keys, label, shrinkableContext);
        boolean second = generateWithSplitting(files.subList(mid, files.size()), systemPrompt, userPromptBuilder,
                responseHandler, keys, label, shrinkableContext);
        return first && second;
    }

    /**
     * Halves the run's shared implementation context, on a line boundary, and
     * drops it entirely once halving would leave something too fragmentary to
     * help. Returns false when there is nothing left to give up, which is the
     * signal to report the batch as failed.
     *
     * <p>Deliberately shared across the run: the batch that discovers the
     * ceiling pays for the discovery once, and every later call in the run
     * starts from the smaller context instead of rediscovering it.
     */
    /**
     * The warning sentences for categories that did not complete.
     *
     * <p>Two different outcomes used to share one sentence. A category that
     * produced nothing and a category that produced eleven cases before a batch
     * failed are both "not covered", but only the first produced no test cases -
     * and the run whose summary said "2 categories failed and produced no test
     * cases" was returning five of them, in the same response, two fields up.
     * Someone reading that has to decide which half of their own report to
     * believe.
     */
    static String categoryWarnings(List<String> failedCategories, List<String> partiallyGenerated,
                                   int casesFromIncomplete) {
        StringBuilder warnings = new StringBuilder();
        List<String> producedNothing = failedCategories.stream()
                .filter(c -> !partiallyGenerated.contains(c))
                .toList();
        if (!producedNothing.isEmpty()) {
            warnings.append(" WARNING: %d categor%s failed and produced no test cases: %s.".formatted(
                    producedNothing.size(), producedNothing.size() == 1 ? "y" : "ies", producedNothing));
        }
        if (!partiallyGenerated.isEmpty()) {
            warnings.append((" WARNING: %d categor%s incomplete - %d case(s) were generated before a batch "
                    + "failed, and are kept, but the files in the failed batch were never examined: %s.").formatted(
                    partiallyGenerated.size(), partiallyGenerated.size() == 1 ? "y is" : "ies are",
                    casesFromIncomplete, partiallyGenerated));
        }
        return warnings.toString();
    }

    static boolean shrinkContext(java.util.concurrent.atomic.AtomicReference<String> context, String label) {
        if (context == null) {
            return false;
        }
        String current = context.get();
        if (current == null || current.isEmpty()) {
            return false;
        }
        int half = current.length() / 2;
        String smaller = "";
        if (half >= MIN_RELATED_IMPLEMENTATION_CHARS) {
            int boundary = current.lastIndexOf('\n', half);
            smaller = current.substring(0, boundary > 0 ? boundary : half);
        }
        context.set(smaller);
        log.warn("{}: a single file is still too large for the provider. The diff cannot be split further, so the "
                + "related implementation context is cut from {} to {} char(s) and the call retried - every later "
                + "batch in this run uses the smaller context too.", label, current.length(), smaller.length());
        return true;
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
     * Answers "has this branch moved since it was last backfilled?" without
     * generating anything - the same clone/fetch and merge walk
     * runBackfillAndCatchUp does, stopped before the first call to run().
     *
     * <p>This is the one merge-history-adjacent operation that legitimately
     * needs the network and a credential: /merge-history and
     * /merge-history/incomplete only ever read what is already recorded
     * locally, but "is there anything NEW" is a question about the remote,
     * and there is no way to answer it without asking the remote.
     */
    public CheckNewCommitsResponse checkForNewCommits(CheckNewCommitsRequest request) {
        CredentialScheme scheme = resolveScheme(request.getProvider());
        String localPath = repoSyncService.syncRepo(request.getRepoUrl(), request.getAccessToken(), scheme);
        String projectName = gitDiffService.resolveProjectName(localPath);

        List<MergeCommitInfo> allMerges = gitDiffService.findAllMergeCommits(localPath, request.getBranch());
        List<String> newCommitShas = allMerges.stream()
                .map(MergeCommitInfo::mergeSha)
                .filter(sha -> !mergeHistoryService.isProcessed(request.getRepoUrl(), request.getBranch(), sha))
                .toList();

        boolean hasNewCommits = !newCommitShas.isEmpty();
        String summary = hasNewCommits
                ? ("%d of %d merge commit(s) on '%s' are not yet processed. "
                        + "Run POST /generate-tests-from-branch/backfill to catch up.")
                        .formatted(newCommitShas.size(), allMerges.size(), request.getBranch())
                : "Branch '%s' is fully caught up - all %d merge commit(s) are already processed."
                        .formatted(request.getBranch(), allMerges.size());
        log.info(summary);

        return new CheckNewCommitsResponse(projectName, request.getRepoUrl(), request.getBranch(),
                allMerges.size(), allMerges.size() - newCommitShas.size(), newCommitShas, hasNewCommits, summary);
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
     * Cap on the README fed as architectural/business context. Smaller than the
     * source cap: a README is prose, so it earns its place by intent, not
     * volume, and it competes with the diff and collaborator source for the same
     * prompt budget - a book-length one truncates rather than crowding them out.
     */
    private static final int MAX_README_CHARS = 16_000;

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

    /**
     * Reads the target's README at the commit under test, as architectural and
     * business context for generation. Best-effort: any failure yields "" and
     * generation proceeds exactly as before this existed - context is an
     * enhancement, never a reason to fail. See {@link ProjectDocReader}.
     */
    private String collectProjectReadme(GenerateTestsRequest request) {
        try (GitDiffService.SourceAtCommit source =
                     gitDiffService.openSourceAtCommit(request.getRepoPath(), request.getHeadRef())) {
            return ProjectDocReader.readReadme(source, MAX_README_CHARS);
        } catch (Exception e) {
            log.warn("Could not read the project README ({}), generating without it.", e.getMessage());
            return "";
        }
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

    /** What a run's category bookkeeping adds up to. */
    record Coverage(List<String> applicable, List<String> missing, int coveragePercent, MergeStatus status) {}

    /**
     * Turns the category lists into a coverage percentage and a status.
     *
     * <p>The load-bearing part is {@code notApplicable}. Coverage is measured
     * against what the change actually gave the pipeline something to do with:
     * counting a category the commit could never exercise either inflates the
     * percentage (when it is credited, which is how a config-only merge whose
     * only real call failed was recorded SUCCESS at 100% and then skipped
     * forever) or strands the merge as permanently incomplete (when it is not).
     *
     * <p>FAILED stays reserved for a run that produced nothing at all. A run
     * holding cases is PARTIAL however few categories completed, and the
     * distinction is load-bearing: MergeHistoryService preserves a FAILED
     * status verbatim and only recomputes a PARTIAL one from accumulated
     * progress, so calling a productive run FAILED freezes it there.
     */
    /**
     * Which folder a commit's generation output belongs in: the one already on
     * disk, or - only when nothing is there yet - a freshly named one.
     *
     * <p>Split out from the I/O so the decision itself is testable without a
     * filesystem. {@code located} is whatever GeneratedRunLocator resolved
     * (which already knows both folder shapes - bare hash, and backfill's
     * "&lt;seq&gt;.&lt;hash&gt;"); {@code locatedExists} is the caller's own
     * {@code Files.isDirectory} check on it, done once, before this is called.
     *
     * <p>Existing wins unconditionally - even a bare-hash folder is reused in
     * preference to minting a "&lt;seq&gt;.&lt;hash&gt;" sibling, because two
     * folders for one commit is the exact problem this exists to prevent.
     * commitSequence only ever matters for the fresh-creation branch: it gives
     * a commit its ordering the first time a folder is made for it, same as
     * before this method existed.
     */
    static String resolveRunOutputDir(boolean locatedExists, String located, String outputDir,
                                       String headRef, Integer commitSequence) {
        return locatedExists ? located : Path.of(outputDir, runFolderName(headRef, commitSequence)).toString();
    }

    static Coverage coverageOf(List<String> expected, List<String> notApplicable,
                                List<String> generated, boolean producedSomething) {
        List<String> applicable = expected.stream().filter(c -> !notApplicable.contains(c)).toList();
        List<String> missing = applicable.stream().filter(c -> !generated.contains(c)).toList();
        int percent = applicable.isEmpty() ? 100
                : (int) Math.round(100.0 * (applicable.size() - missing.size()) / applicable.size());
        MergeStatus status = missing.isEmpty() ? MergeStatus.SUCCESS
                : (generated.isEmpty() && !producedSomething ? MergeStatus.FAILED : MergeStatus.PARTIAL);
        return new Coverage(applicable, missing, percent, status);
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
    private static String runFolderName(String headRef, Integer sequence) {
        String hash = sanitizeForPath(headRef);
        return sequence == null ? hash : sequence + "." + hash;
    }

    /**
     * Creates the commit's run folder and writes the "nothing to generate"
     * sentence into it.
     *
     * Deliberately never throws: this runs on a path that has already
     * succeeded and has nothing to record, so failing to leave a note must not
     * turn that into a failed merge which a later backfill then retries
     * forever. A warning in the log is the whole remedy.
     */
    /**
     * Writes the README used as context beside the generated tests, so the
     * commit's folder carries the exact architectural/business doc it was
     * generated against - the "sync the README locally" half of using it. Only
     * when there is one; best-effort, and never fails a run over a snapshot.
     */
    static void persistReadmeSnapshot(String runOutputDir, String projectReadme) {
        if (projectReadme == null || projectReadme.isBlank()) {
            return;
        }
        Path snapshot = Path.of(runOutputDir, README_SNAPSHOT_FILE);
        try {
            Files.createDirectories(Path.of(runOutputDir));
            Files.writeString(snapshot, projectReadme, StandardCharsets.UTF_8);
            log.info("Synced project README locally to {}", snapshot);
        } catch (IOException e) {
            log.warn("Could not write README snapshot {}: {}", snapshot, e.getMessage());
        }
    }

    static void writeRunNote(String runOutputDir, String message) {
        Path note = Path.of(runOutputDir, NO_TEST_CASES_FILE);
        try {
            Files.createDirectories(Path.of(runOutputDir));
            Files.writeString(note, message + System.lineSeparator(), StandardCharsets.UTF_8);
            log.info("No test cases for this commit - wrote {}", note);
        } catch (IOException e) {
            log.warn("Could not write {}: {}", note, e.getMessage());
        }
    }

    /** Turns a ref (sha, branch name, etc.) into a filesystem-safe folder name. */
    private static String sanitizeForPath(String ref) {
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
