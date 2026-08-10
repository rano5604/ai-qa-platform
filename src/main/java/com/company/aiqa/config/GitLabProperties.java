package com.company.aiqa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the "aiqa.gitlab" section of application.yml.
 *
 * Only holds settings specific to the GitLab webhook endpoint. The
 * credential used to clone/fetch repos (including GitLab ones) lives in
 * GitProperties ("aiqa.git"), which is shared across all git hosts.
 */
@ConfigurationProperties(prefix = "aiqa.gitlab")
public class GitLabProperties {

    /**
     * Secret token configured on the GitLab webhook ("Secret token" field),
     * compared directly against the X-Gitlab-Token header. Unlike GitHub,
     * GitLab does not sign the payload - it just echoes this value back.
     */
    private String webhookSecret;

    /** Only react to merges targeting these branches (empty = all branches). */
    private String[] watchedBaseBranches = new String[0];

    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }

    public String[] getWatchedBaseBranches() { return watchedBaseBranches; }
    public void setWatchedBaseBranches(String[] watchedBaseBranches) { this.watchedBaseBranches = watchedBaseBranches; }
}
