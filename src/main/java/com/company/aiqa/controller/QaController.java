package com.company.aiqa.controller;

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
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class QaController {

    private final QaPipelineService pipelineService;
    private final MergeHistoryService mergeHistoryService;
    private final AutomationGenerationService automationGenerationService;
    private final AutomationExecutionService automationExecutionService;

    public QaController(QaPipelineService pipelineService,
                        MergeHistoryService mergeHistoryService,
                        AutomationGenerationService automationGenerationService,
                        AutomationExecutionService automationExecutionService) {
        this.pipelineService = pipelineService;
        this.mergeHistoryService = mergeHistoryService;
        this.automationGenerationService = automationGenerationService;
        this.automationExecutionService = automationExecutionService;
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
}
