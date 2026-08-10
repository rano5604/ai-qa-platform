package com.company.aiqa.controller;

import com.company.aiqa.git.MergeHistoryService;
import com.company.aiqa.model.GenerateTestsBackfillResponse;
import com.company.aiqa.model.GenerateTestsFromBranchRequest;
import com.company.aiqa.model.GenerateTestsRequest;
import com.company.aiqa.model.GenerateTestsResponse;
import com.company.aiqa.model.MergeHistoryEntry;
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

    public QaController(QaPipelineService pipelineService, MergeHistoryService mergeHistoryService) {
        this.pipelineService = pipelineService;
        this.mergeHistoryService = mergeHistoryService;
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
