package com.company.aiqa.webhook;

import com.company.aiqa.config.GitLabProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * Receives GitLab webhook deliveries.
 *
 * Configure in GitLab under Settings -> Webhooks -> Add new webhook:
 *   URL:          https://your-host/api/v1/webhooks/gitlab
 *   Secret Token: same value as aiqa.gitlab.webhook-secret / GITLAB_WEBHOOK_SECRET
 *   Trigger:      check "Merge request events" (leave "Push events" unchecked
 *                 unless something else in your pipeline needs it - this
 *                 endpoint only looks at Merge Request Hook deliveries).
 *
 * GitLab's webhook UI only offers a branch filter for Push events, not for
 * Merge request events - so target-branch filtering happens here in code,
 * via aiqa.gitlab.watched-base-branches (see GitLabProperties).
 *
 * Unlike GitHub, GitLab doesn't sign the payload with an HMAC - it just
 * echoes the configured Secret Token back in the X-Gitlab-Token header, so
 * verification here is a direct (constant-time) string comparison.
 *
 * GitLab can't reach a bare "localhost:8080" - for local testing, tunnel it
 * first, e.g. `ngrok http 8080`, and use the ngrok URL as the webhook URL.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class GitLabWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GitLabWebhookController.class);

    private final GitLabWebhookService webhookService;
    private final GitLabProperties gitLabProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GitLabWebhookController(GitLabWebhookService webhookService, GitLabProperties gitLabProperties) {
        this.webhookService = webhookService;
        this.gitLabProperties = gitLabProperties;
    }

    @PostMapping("/gitlab")
    public ResponseEntity<String> handleWebhook(
            @RequestHeader("X-Gitlab-Event") String eventType,
            @RequestHeader(value = "X-Gitlab-Token", required = false) String tokenHeader,
            @RequestBody String rawPayload) {

        log.info("Received GitLab webhook delivery: event={}", eventType);

        if (!isValidToken(tokenHeader)) {
            log.warn("Rejected webhook delivery with invalid/missing token (event={})", eventType);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid token");
        }

        if (!"Merge Request Hook".equals(eventType)) {
            log.info("Ignored: event type '{}' is not 'Merge Request Hook' - check the webhook's "
                    + "Trigger checkboxes if you expected this to be a merge request event.", eventType);
            return ResponseEntity.ok("Ignored event type: " + eventType);
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(rawPayload);
        } catch (Exception e) {
            log.warn("Malformed JSON payload on GitLab webhook delivery: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Malformed JSON payload");
        }

        String action = payload.path("object_attributes").path("action").asText();
        String targetBranch = payload.path("object_attributes").path("target_branch").asText();

        // GitLab sends one of: open, close, reopen, update, approved,
        // unapproved, approval, unapproval, merge. "approved" fires once the
        // MR's required-approval threshold is met (mirrors GitHub's
        // pull_request_review "approved" event, pre-merge) - available on
        // every GitLab tier, not just Premium/Ultimate.
        boolean isApproved = "approved".equals(action);
        if (!isApproved) {
            log.info("Ignored: action='{}' (only 'approved' triggers test generation). "
                    + "If you clicked GitLab's own 'Test' button, note it usually sends action='open', "
                    + "not 'approved' - approve a real MR instead to test end-to-end.", action);
            return ResponseEntity.ok("Ignored: action=%s".formatted(action));
        }
        if (!webhookService.isWatchedBaseBranch(targetBranch)) {
            log.info("Ignored: target branch '{}' is not in aiqa.gitlab.watched-base-branches", targetBranch);
            return ResponseEntity.ok("Ignored: target branch '%s' is not watched".formatted(targetBranch));
        }

        webhookService.handleApprovedMergeRequest(payload);
        return ResponseEntity.accepted().body("Test generation triggered");
    }

    private boolean isValidToken(String tokenHeader) {
        String secret = gitLabProperties.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            // No secret configured - allow through, but this should only ever be
            // true in local/dev testing. Always set a secret in any shared environment.
            log.warn("No webhook secret configured - accepting unauthenticated payload. Do not do this in production.");
            return true;
        }
        if (tokenHeader == null) {
            return false;
        }
        return MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8),
                tokenHeader.getBytes(StandardCharsets.UTF_8));
    }
}
