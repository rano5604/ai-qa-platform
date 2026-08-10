package com.company.aiqa.webhook;

import com.company.aiqa.config.GitHubProperties;
import com.company.aiqa.git.MergeHistoryService;
import com.company.aiqa.git.RepoSyncService;
import com.company.aiqa.model.GenerateTestsRequest;
import com.company.aiqa.model.GenerateTestsResponse;
import com.company.aiqa.service.QaPipelineService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Arrays;

/**
 * Reacts to an "approved" GitHub pull_request_review webhook event by
 * syncing the target repo locally and running the existing generate-tests
 * pipeline against the PR's base/head commits.
 *
 * Note: this fires on review APPROVAL, which happens before the merge
 * commit exists. base.sha / head.sha give the diff that's about to be
 * merged, which is what we want to generate tests against ahead of time.
 * If you'd rather trigger strictly after the merge lands on the release
 * branch, listen for "pull_request" events with action=="closed" and
 * pull_request.merged==true instead - see GitHubWebhookController.
 *
 * History-aware: each PR's head sha is recorded in this repo+branch's
 * merge history once processed, keyed the same way runFromBranch's merges
 * are, so a redelivered webhook (GitHub retries on timeout/5xx) doesn't
 * trigger duplicate work.
 */
@Service
public class GitHubWebhookService {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookService.class);

    private final RepoSyncService repoSyncService;
    private final QaPipelineService pipelineService;
    private final MergeHistoryService mergeHistoryService;
    private final GitHubProperties gitHubProperties;

    public GitHubWebhookService(RepoSyncService repoSyncService,
                                 QaPipelineService pipelineService,
                                 MergeHistoryService mergeHistoryService,
                                 GitHubProperties gitHubProperties) {
        this.repoSyncService = repoSyncService;
        this.pipelineService = pipelineService;
        this.mergeHistoryService = mergeHistoryService;
        this.gitHubProperties = gitHubProperties;
    }

    public boolean isWatchedBaseBranch(String baseBranch) {
        String[] watched = gitHubProperties.getWatchedBaseBranches();
        return watched.length == 0 || Arrays.asList(watched).contains(baseBranch);
    }

    /**
     * Runs synchronously by default; annotate the caller to fire-and-forget
     * if PR generation is slow enough that you want to return the webhook
     * 202 immediately (requires @EnableAsync on the application class).
     */
    @Async
    public void handleApprovedPullRequest(JsonNode payload) {
        String cloneUrl = payload.path("repository").path("clone_url").asText();
        String baseBranch = payload.path("pull_request").path("base").path("ref").asText();
        String baseSha = payload.path("pull_request").path("base").path("sha").asText();
        String headSha = payload.path("pull_request").path("head").path("sha").asText();
        int prNumber = payload.path("pull_request").path("number").asInt();

        log.info("PR #{} approved ({} -> {}), base branch '{}'", prNumber, headSha, baseSha, baseBranch);

        if (mergeHistoryService.isProcessed(cloneUrl, baseBranch, headSha)) {
            log.info("PR #{}: head {} already processed on branch '{}' - skipping (likely a redelivered webhook).",
                    prNumber, headSha, baseBranch);
            return;
        }

        try {
            String localPath = repoSyncService.syncRepo(cloneUrl);

            GenerateTestsRequest request = new GenerateTestsRequest();
            request.setRepoPath(localPath);
            request.setBaseRef(baseSha);
            request.setHeadRef(headSha);

            GenerateTestsResponse response = pipelineService.run(request);
            mergeHistoryService.recordAttempt(cloneUrl, baseBranch, headSha, baseSha, response.summary(),
                    response.status(),
                    response.missingCategories().isEmpty() ? null
                            : "Categories not generated: " + response.missingCategories(),
                    response.expectedCategories(), response.generatedCategories(),
                    response.businessTestCases().size());
            log.info("PR #{}: {}", prNumber, response.summary());
        } catch (Exception e) {
            // Recorded as FAILED (rather than left absent from history) so the
            // merge is discoverable via /merge-history/incomplete and picked up
            // by the next backfill, instead of only being retried if a
            // redelivery happens to arrive.
            mergeHistoryService.recordAttempt(cloneUrl, baseBranch, headSha, baseSha,
                    "FAILED: " + e.getMessage(), com.company.aiqa.model.MergeStatus.FAILED, e.getMessage(),
                    java.util.List.of(), java.util.List.of(), 0);
            // Swallow here since this runs off the webhook thread; log loudly instead.
            // In production, push this failure to wherever your team watches for
            // pipeline health (Slack, PagerDuty, a status dashboard, etc).
            // Deliberately NOT marked as processed, so a redelivery (or a manual
            // retry) will pick it up again.
            log.error("Test generation failed for PR #{}: {}", prNumber, e.getMessage(), e);
        }
    }
}
