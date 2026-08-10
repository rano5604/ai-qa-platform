package com.company.aiqa.webhook;

import com.company.aiqa.config.GitHubProperties;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Receives GitHub webhook deliveries.
 *
 * Configure in GitHub under Settings -> Webhooks -> Add webhook:
 *   Payload URL:  https://your-host/api/v1/webhooks/github
 *   Content type: application/json
 *   Secret:       same value as aiqa.github.webhook-secret / GITHUB_WEBHOOK_SECRET
 *   Events:       "Pull request reviews" (this is what fires on approval)
 *
 * GitHub can't reach a bare "localhost:8080" - for local testing, tunnel it
 * first, e.g. `ngrok http 8080`, and use the ngrok URL as the payload URL.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class GitHubWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookController.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final GitHubWebhookService webhookService;
    private final GitHubProperties gitHubProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GitHubWebhookController(GitHubWebhookService webhookService, GitHubProperties gitHubProperties) {
        this.webhookService = webhookService;
        this.gitHubProperties = gitHubProperties;
    }

    @PostMapping("/github")
    public ResponseEntity<String> handleWebhook(
            @RequestHeader("X-GitHub-Event") String eventType,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signatureHeader,
            @RequestBody String rawPayload) {

        log.info("Received GitHub webhook delivery: event={}", eventType);

        if (!isValidSignature(rawPayload, signatureHeader)) {
            log.warn("Rejected webhook delivery with invalid signature (event={})", eventType);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
        }

        if (!"pull_request_review".equals(eventType)) {
            log.info("Ignored: event type '{}' is not 'pull_request_review' - check the webhook's "
                    + "'Which events...' setting if you expected this to be a review event.", eventType);
            return ResponseEntity.ok("Ignored event type: " + eventType);
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(rawPayload);
        } catch (Exception e) {
            log.warn("Malformed JSON payload on GitHub webhook delivery: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Malformed JSON payload");
        }

        String action = payload.path("action").asText();
        String reviewState = payload.path("review").path("state").asText();
        String baseBranch = payload.path("pull_request").path("base").path("ref").asText();

        boolean isApproval = "submitted".equals(action) && "approved".equalsIgnoreCase(reviewState);
        if (!isApproval) {
            log.info("Ignored: action='{}', review.state='{}' (only a submitted 'approved' review triggers "
                    + "test generation). GitHub's own 'Redeliver'/ping test payloads won't match this.",
                    action, reviewState);
            return ResponseEntity.ok("Ignored: action=%s, review.state=%s".formatted(action, reviewState));
        }
        if (!webhookService.isWatchedBaseBranch(baseBranch)) {
            log.info("Ignored: base branch '{}' is not in aiqa.github.watched-base-branches", baseBranch);
            return ResponseEntity.ok("Ignored: base branch '%s' is not watched".formatted(baseBranch));
        }

        webhookService.handleApprovedPullRequest(payload);
        return ResponseEntity.accepted().body("Test generation triggered");
    }

    private boolean isValidSignature(String payload, String signatureHeader) {
        String secret = gitHubProperties.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            // No secret configured - allow through, but this should only ever be
            // true in local/dev testing. Always set a secret in any shared environment.
            log.warn("No webhook secret configured - accepting unsigned payload. Do not do this in production.");
            return true;
        }
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] computed = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String computedHex = "sha256=" + HexFormat.of().formatHex(computed);
            return MessageDigest.isEqual(
                    computedHex.getBytes(StandardCharsets.UTF_8),
                    signatureHeader.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            log.error("Signature verification failed: {}", e.getMessage());
            return false;
        }
    }
}
