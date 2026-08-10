package com.company.aiqa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the "aiqa.github" section of application.yml.
 *
 * Only holds settings specific to the GitHub webhook endpoint. The
 * credential used to clone/fetch repos (including GitHub ones) now lives
 * in GitProperties ("aiqa.git"), which is shared across all git hosts.
 */
@ConfigurationProperties(prefix = "aiqa.github")
public class GitHubProperties {

    /** Shared secret configured on the GitHub webhook, used to verify X-Hub-Signature-256. */
    private String webhookSecret;

    /** Only react to reviews on PRs targeting these base branches (empty = all branches). */
    private String[] watchedBaseBranches = new String[0];

    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }

    public String[] getWatchedBaseBranches() { return watchedBaseBranches; }
    public void setWatchedBaseBranches(String[] watchedBaseBranches) { this.watchedBaseBranches = watchedBaseBranches; }
}
