package com.company.aiqa.webhook;

import com.company.aiqa.config.GitLabProperties;
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
 * Reacts to an "approved" GitLab merge_request webhook event by syncing the
 * target repo locally and running the generate-tests pipeline against the
 * MR's pre-merge diff - the GitLab counterpart to GitHubWebhookService's
 * pull_request_review "approved" handling.
 *
 * GitLab's MR payload carries object_attributes.diff_refs.{base_sha,head_sha},
 * the same before/after pair GitHub gives via pull_request.base.sha /
 * pull_request.head.sha, so no extra repo inspection is needed to find them -
 * unlike a merge-triggered flow, which has to walk history to find the
 * merge commit after the fact.
 *
 * History-aware: each MR's head sha is recorded in this repo+branch's merge
 * history once processed, so re-approval notifications, GitLab's own retry
 * on a failed delivery, or a race between two approvers don't trigger
 * duplicate work for the same diff.
 */
@Service
public class GitLabWebhookService {

    private static final Logger log = LoggerFactory.getLogger(GitLabWebhookService.class);

    private final RepoSyncService repoSyncService;
    private final QaPipelineService pipelineService;
    private final MergeHistoryService mergeHistoryService;
    private final GitLabProperties gitLabProperties;

    public GitLabWebhookService(RepoSyncService repoSyncService,
                                 QaPipelineService pipelineService,
                                 MergeHistoryService mergeHistoryService,
                                 GitLabProperties gitLabProperties) {
        this.repoSyncService = repoSyncService;
        this.pipelineService = pipelineService;
        this.mergeHistoryService = mergeHistoryService;
        this.gitLabProperties = gitLabProperties;
    }

    public boolean isWatchedBaseBranch(String targetBranch) {
        String[] watched = gitLabProperties.getWatchedBaseBranches();
        return watched.length == 0 || Arrays.asList(watched).contains(targetBranch);
    }

    @Async
    public void handleApprovedMergeRequest(JsonNode payload) {
        String cloneUrl = payload.path("project").path("git_http_url").asText();
        String targetBranch = payload.path("object_attributes").path("target_branch").asText();
        JsonNode diffRefs = payload.path("object_attributes").path("diff_refs");
        String baseSha = diffRefs.path("base_sha").asText();
        String headSha = diffRefs.path("head_sha").asText();
        int mrIid = payload.path("object_attributes").path("iid").asInt();

        log.info("MR !{} approved ({} -> {}), target branch '{}'", mrIid, baseSha, headSha, targetBranch);

        if (baseSha.isBlank() || headSha.isBlank()) {
            log.warn("MR !{}: payload missing object_attributes.diff_refs base_sha/head_sha - skipping", mrIid);
            return;
        }

        if (mergeHistoryService.isProcessed(cloneUrl, targetBranch, headSha)) {
            log.info("MR !{}: head {} already processed on branch '{}' - skipping (duplicate/redelivered webhook).",
                    mrIid, headSha, targetBranch);
            return;
        }

        try {
            String localPath = repoSyncService.syncRepo(cloneUrl);

            GenerateTestsRequest request = new GenerateTestsRequest();
            request.setRepoPath(localPath);
            request.setBaseRef(baseSha);
            request.setHeadRef(headSha);

            GenerateTestsResponse response = pipelineService.run(request);
            mergeHistoryService.recordAttempt(cloneUrl, targetBranch, headSha, baseSha, response.summary(),
                    response.status(),
                    response.missingCategories().isEmpty() ? null
                            : "Categories not generated: " + response.missingCategories(),
                    response.expectedCategories(), response.generatedCategories(),
                    response.businessTestCases().size());
            log.info("MR !{}: {}", mrIid, response.summary());
        } catch (Exception e) {
            // Recorded as FAILED (rather than left absent from history) so the
            // merge is discoverable via /merge-history/incomplete and picked up
            // by the next backfill, instead of only being retried if a
            // redelivery happens to arrive.
            mergeHistoryService.recordAttempt(cloneUrl, targetBranch, headSha, baseSha,
                    "FAILED: " + e.getMessage(), com.company.aiqa.model.MergeStatus.FAILED, e.getMessage(),
                    java.util.List.of(), java.util.List.of(), 0);
            // Swallow here since this runs off the webhook thread; log loudly instead.
            // In production, push this failure to wherever your team watches for
            // pipeline health (Slack, PagerDuty, a status dashboard, etc).
            // Deliberately NOT marked as processed, so a redelivery (or a manual
            // retry) will pick it up again.
            log.error("Test generation failed for MR !{}: {}", mrIid, e.getMessage(), e);
        }
    }
}
