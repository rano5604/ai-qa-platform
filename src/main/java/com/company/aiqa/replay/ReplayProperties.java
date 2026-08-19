package com.company.aiqa.replay;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the report's "Send again" button.
 *
 * <p>The button re-issues a captured request from the reader's browser, and a
 * report opened from disk has no origin - so unless the service under test
 * happens to allow cross-origin calls from {@code null}, the browser blocks it
 * before it leaves. Routing the call through this platform instead removes the
 * restriction entirely: a server has no same-origin policy.
 */
@ConfigurationProperties(prefix = "aiqa.report.replay")
public class ReplayProperties {

    /** Set false to drop the proxy endpoint and leave the button doing a direct browser call. */
    private boolean enabled = true;

    /**
     * Where the report should reach this platform. Only needs setting when the
     * report is read somewhere that cannot see localhost - a shared drive, a
     * teammate's machine, a container host.
     */
    private String platformBaseUrl = "";

    /** Longest a replayed call may take before it is abandoned. */
    private int timeoutSeconds = 30;

    /** Response bodies longer than this are truncated in the replay result. */
    private int maxBodyChars = 200_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getPlatformBaseUrl() { return platformBaseUrl; }
    public void setPlatformBaseUrl(String platformBaseUrl) { this.platformBaseUrl = platformBaseUrl; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public int getMaxBodyChars() { return maxBodyChars; }
    public void setMaxBodyChars(int maxBodyChars) { this.maxBodyChars = maxBodyChars; }
}
