package com.company.aiqa.controller;

import com.company.aiqa.error.NotFoundException;
import com.company.aiqa.git.MergeHistoryService;
import com.company.aiqa.model.ExecuteAutomationRequest;
import com.company.aiqa.model.ExecuteAutomationResponse;
import com.company.aiqa.model.GenerateAutomationRequest;
import com.company.aiqa.model.GenerateAutomationResponse;
import com.company.aiqa.model.GenerateTestsBackfillResponse;
import com.company.aiqa.model.GenerateTestsFromBranchRequest;
import com.company.aiqa.model.GenerateTestsRequest;
import com.company.aiqa.model.GenerateTestsResponse;
import com.company.aiqa.model.MergeHistoryEntry;
import com.company.aiqa.service.AutomationExecutionService;
import com.company.aiqa.service.AutomationGenerationService;
import com.company.aiqa.service.QaPipelineService;
import com.company.aiqa.service.TestCaseDownloadService;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class QaController {

    private final QaPipelineService pipelineService;
    private final MergeHistoryService mergeHistoryService;
    private final AutomationGenerationService automationGenerationService;
    private final AutomationExecutionService automationExecutionService;
    private final TestCaseDownloadService testCaseDownloadService;

    public QaController(QaPipelineService pipelineService,
                        MergeHistoryService mergeHistoryService,
                        AutomationGenerationService automationGenerationService,
                        AutomationExecutionService automationExecutionService,
                        TestCaseDownloadService testCaseDownloadService) {
        this.pipelineService = pipelineService;
        this.mergeHistoryService = mergeHistoryService;
        this.automationGenerationService = automationGenerationService;
        this.automationExecutionService = automationExecutionService;
        this.testCaseDownloadService = testCaseDownloadService;
    }

    /**
     * Runs the full pipeline against a repo you've already cloned locally:
     * git diff -> parse -> dependencies -> impact -> LLM -> test cases.
     *
     * By default this produces business/functional test cases for QA (a CSV
     * of feature-level scenarios, no code). Set "testCaseMode" to "AUTOMATED"
     * for JUnit/Jest/pytest/etc. source files instead, or "BOTH" for both.
     *
     * Supply "llmKeys" to choose which models run this request. A model is
     * used only if its key is present; anything left out is inactive and is
     * never called (no wasted attempt, no "not configured" noise).
     *
     * <p>Identify the repo by EITHER "repoPath" (a checkout already on this
     * machine) OR "repoUrl" (cloned on demand; an existing clone is reused and
     * fetched). With "repoUrl" a single call can target any commit of any repo,
     * with no clone step of your own. For one specific commit, set "headRef" to
     * it and "baseRef" to its parent - or to the literal "EMPTY_TREE" when it is
     * the repository's first commit, which has no parent.
     *
     * Example:
     * POST /api/v1/generate-tests
     * {
     *   "repoPath": "/repos/ai-qa-platform",
     *   "baseRef": "origin/main",
     *   "headRef": "origin/release/1.2",
     *   "testCaseMode": "MANUAL",
     *   "llmKeys": {
     *     "gemini": "AIza...",
     *     "groq": "gsk_..."
     *   }
     * }
     * Here only Gemini and Groq are tried (Gemini first, then Groq if it
     * fails); mistral/github/ollama are skipped entirely.
     */
    @PostMapping("/generate-tests")
    public GenerateTestsResponse generateTests(@Valid @RequestBody GenerateTestsRequest request) {
        return pipelineService.run(request);
    }

    /**
     * Self-service variant: give it a repo URL, a credential, and a branch
     * name - it clones/fetches the repo itself, finds the latest merge
     * commit on that branch, and runs the same pipeline against it.
     * No local path or manual ref lookup required. Same testCaseMode
     * options as /generate-tests (defaults to "MANUAL").
     *
     * Works against GitHub, GitLab, or any other git host over HTTPS - the
     * credential scheme is auto-detected from repoUrl's hostname, or you can
     * set "provider" explicitly ("GITHUB" / "GITLAB" / "GENERIC") when
     * pointing at a self-hosted instance the auto-detection can't identify
     * (e.g. reached by bare IP). See GitProperties (aiqa.git.*) to configure
     * server-side credentials per host instead of passing accessToken.
     *
     * Example (GitHub):
     * POST /api/v1/generate-tests-from-branch
     * {
     *   "repoUrl": "https://github.com/rano5604/FinFlow.git",
     *   "branch": "Release",
     *   "accessToken": "ghp_..."
     * }
     *
     * Example (self-hosted GitLab, reached by IP):
     * POST /api/v1/generate-tests-from-branch
     * {
     *   "repoUrl": "http://10.88.230.18/emv-tsp/token-vault-manager.git",
     *   "branch": "Release",
     *   "accessToken": "glpat-...",
     *   "provider": "GITLAB"
     * }
     */
    @PostMapping("/generate-tests-from-branch")
    public GenerateTestsResponse generateTestsFromBranch(@Valid @RequestBody GenerateTestsFromBranchRequest request) {
        return pipelineService.runFromBranch(request);
    }

    /**
     * Catches a branch up on ALL of its merge history, not just the latest
     * merge: walks every merge commit reachable from "branch", oldest first,
     * and runs the pipeline against everything that isn't already COMPLETE.
     *
     * Three outcomes per merge:
     *   - SUCCESS in history -> skipped entirely;
     *   - PARTIAL            -> resumed, regenerating ONLY its missing
     *                            categories (completed work is never redone);
     *   - FAILED / never seen -> run from scratch.
     *
     * Call this once (or on a schedule) to establish history for a branch you
     * haven't run this platform against before. It's safe to call repeatedly:
     * once a branch is fully covered, later calls do no LLM work until a new
     * merge lands, so this doubles as a catch-up safety net alongside the
     * webhooks - and as the mechanism that finishes any merge whose
     * generation was left incomplete. Same request shape and testCaseMode
     * options as /generate-tests-from-branch; "force" does not apply here,
     * since this endpoint's job is precisely to work out what still needs
     * doing.
     *
     * Example:
     * POST /api/v1/generate-tests-from-branch/backfill
     * {
     *   "repoUrl": "https://github.com/rano5604/FinFlow.git",
     *   "branch": "Release",
     *   "accessToken": "ghp_..."
     * }
     */
    @PostMapping("/generate-tests-from-branch/backfill")
    public GenerateTestsBackfillResponse backfill(@Valid @RequestBody GenerateTestsFromBranchRequest request) {
        return pipelineService.runBackfillAndCatchUp(request);
    }

    /**
     * Turns the manual test cases ALREADY generated for one commit into
     * runnable REST Assured automation - API testing only.
     *
     * "commitHash" is the folder name under generated-tests/&lt;project&gt;/,
     * i.e. the same value that was used as headRef when those cases were
     * produced. This is a second pass over existing output: it does not
     * regenerate manual cases, touch merge history, or re-analyse the branch.
     *
     * Only cases that can be exercised over HTTP are automated. Cases
     * describing UI steps or configuration checks are skipped and reported in
     * skippedNonApi, rather than becoming scripts that couldn't run. The
     * generated files land in the same commit folder as the manual cases.
     *
     * This endpoint GENERATES ONLY - it sends no HTTP traffic at baseUri and
     * cannot mutate anything. To run what it produced, call
     * POST /api/v1/execute-automation with the same commit hash.
     *
     * Example:
     * POST /api/v1/generate-automation
     * {
     *   "repoUrl": "https://github.com/org/repo.git",
     *   "branch": "main",
     *   "commitHash": "4df458deab5a8d8d4748696d753b4aa54fdcf304",
     *   "baseUri": "http://localhost:8080",
     *   "llmKeys": { "gemini": "AIza..." }
     * }
     */
    @PostMapping("/generate-automation")
    public GenerateAutomationResponse generateAutomation(@Valid @RequestBody GenerateAutomationRequest request) {
        return automationGenerationService.generate(request);
    }

    /**
     * RUNS the automation already generated for a commit, with TestNG.
     *
     * Generation and execution are separate endpoints on purpose. Generating
     * costs LLM tokens and touches nothing outside the output folder; executing
     * costs nothing, needs no credential, and fires real HTTP traffic -
     * including whatever POST/PUT/DELETE the test cases describe - at whatever
     * baseUri points to. As an option on the generate call, every generation
     * request was one mistyped boolean away from mutating live data.
     *
     * Nothing is generated here: if the commit has no script on disk, that is
     * reported rather than silently produced. Generate first with
     * POST /api/v1/generate-automation.
     *
     * Each result carries the HTTP requests and responses that produced it, so
     * a failure can be triaged without re-running anything by hand
     * (credential-bearing headers are redacted). TestNG's own HTML/XML report
     * is left in the commit's folder under test-report/ and its path is
     * returned in testExecutionSummary.reportPath.
     *
     * baseUri overrides the default baked in at generation time, so the same
     * commit's scripts can be pointed at any environment without regenerating.
     * NEVER point it at production.
     *
     * Example:
     * POST /api/v1/execute-automation
     * {
     *   "projectName": "QueueManagement",
     *   "commitHash": "4df458deab5a8d8d4748696d753b4aa54fdcf304",
     *   "baseUri": "http://localhost:8082"
     * }
     */
    @PostMapping("/execute-automation")
    public ExecuteAutomationResponse executeAutomation(@Valid @RequestBody ExecuteAutomationRequest request) {
        return automationExecutionService.execute(request);
    }

    /**
     * Lists every merge this repo+branch has recorded as processed, oldest
     * first - useful for confirming a backfill covered what you expected,
     * or for auditing what's already been run.
     *
     * Example: GET /api/v1/merge-history?repoUrl=https://github.com/rano5604/FinFlow.git&branch=Release
     */
    @GetMapping("/merge-history")
    public List<MergeHistoryEntry> mergeHistory(@RequestParam String repoUrl, @RequestParam String branch) {
        return mergeHistoryService.load(repoUrl, branch);
    }

    /**
     * Lists merges with outstanding work - PARTIAL (some test-case categories
     * generated, some missing) or FAILED (nothing usable produced). Each entry
     * carries its missingCategories, coveragePercent, attemptCount and
     * failureReason, so you can see exactly what's incomplete and why.
     *
     * These are picked up automatically by
     * POST /generate-tests-from-branch/backfill, which regenerates ONLY the
     * missing categories rather than redoing completed work. This endpoint is
     * for visibility - to confirm a branch really is fully covered, or to see
     * what a backfill will do before running it.
     *
     * Example: GET /api/v1/merge-history/incomplete?repoUrl=https://github.com/org/repo.git&branch=main
     */
    @GetMapping("/merge-history/incomplete")
    public List<MergeHistoryEntry> incompleteMerges(@RequestParam String repoUrl, @RequestParam String branch) {
        return mergeHistoryService.findIncomplete(repoUrl, branch);
    }

    /**
     * Downloads generated manual test cases as a CSV attachment.
     *
     * <p>The generate endpoints return {@code manualTestCasesCsvPath}, but that
     * is a path on the SERVER's disk - no use to a caller on another machine.
     * This returns the file itself.
     *
     * <p>Identify the project by either {@code projectName} or {@code repoUrl}.
     * With {@code commitHash} you get that single run's cases; without it, the
     * project-wide rollup of every commit generated so far.
     *
     * <p>{@code commitHash} must be the FULL hash - folders are named for the
     * full hash, so an abbreviated one finds nothing and answers 404. The
     * {@code manualTestCasesDownloadUrl} on a generate response is already
     * built correctly; prefer copying that over assembling this by hand.
     *
     * Examples:
     * GET /api/v1/test-cases/download?projectName=CalculatorTest&commitHash=f602859d2281e871e60c4eb18a0942e83bcf3717
     * GET /api/v1/test-cases/download?repoUrl=https://github.com/org/repo.git
     */
    @GetMapping("/test-cases/download")
    public ResponseEntity<byte[]> downloadTestCases(@RequestParam(required = false) String projectName,
                                                    @RequestParam(required = false) String repoUrl,
                                                    @RequestParam(required = false) String commitHash) {
        TestCaseDownloadService.Download download =
                testCaseDownloadService.load(projectName, repoUrl, commitHash);

        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(download.fileName()).build().toString())
                .body(download.content());
    }

    /**
     * Something the caller asked for isn't there - a commit with nothing
     * generated for it yet, a script file that doesn't exist - answered as 404
     * with the reason rather than a 500 and a stack trace.
     */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<String> handleNotFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    /**
     * A malformed or contradictory request - a missing repoPath/repoUrl, an
     * unresolvable ref, an unknown provider - answered as 400.
     *
     * <p>These used to share the 404 handler above, so "supply either repoPath
     * or repoUrl" came back as Not Found and read like the endpoint itself was
     * wrong. 404 now means only what it says; see {@link NotFoundException}.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    /**
     * A request the server cannot act on yet - no LLM credentials configured,
     * most often - answered as 400 WITH the explanation.
     *
     * <p>Without this it surfaced as a bare 500 "Internal Server Error" and no
     * message at all, which reads like the platform crashed rather than like a
     * setup step nobody has done. The body carries the instruction, since that
     * is the only place a caller will see it.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<java.util.Map<String, Object>> handleNotReady(IllegalStateException e) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.put("configureAt", "POST /api/v1/llm-keys");
        return ResponseEntity.badRequest().body(body);
    }
}
