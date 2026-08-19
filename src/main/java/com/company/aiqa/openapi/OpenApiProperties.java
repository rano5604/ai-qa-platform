package com.company.aiqa.openapi;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits on reading a target's OpenAPI document.
 *
 * <p>The sizes matter more than they look. The automation prompt is already
 * large enough that Groq's on-demand tier rejects it outright, and a 53-path
 * spec with 76 schemas rendered in full is comfortably bigger than the endpoint
 * list and implementation source put together. So the contract is capped like
 * every other section, and the caps are here rather than as constants because
 * the right ceiling depends on which provider a deployment routes to.
 */
@ConfigurationProperties(prefix = "aiqa.openapi")
public class OpenApiProperties {

    /** Set false to skip spec discovery entirely and use source-derived DTOs only. */
    private boolean enabled = true;

    /** Per-request ceiling. A docs endpoint answers fast or is not there. */
    private long timeoutMillis = 5000;

    /**
     * How many candidate URLs to try before giving up. The locator produces a
     * handful per component; without a bound, a repo of twenty services with
     * none of them running spends twenty timeouts on every generation.
     */
    private int maxAttempts = 6;

    /** Characters of rendered contract allowed into the prompt. */
    private int maxPromptChars = 22000;

    /**
     * How deep to follow a nested schema. Three reaches {@code data.tiers[].rate}
     * - a wrapper, the payload, and one collection inside it - which covers the
     * envelopes these services actually use.
     */
    private int maxDepth = 3;

    /** Fields per payload. A schema with more than this is a page of noise. */
    private int maxFieldsPerSchema = 45;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int getMaxPromptChars() {
        return maxPromptChars;
    }

    public void setMaxPromptChars(int maxPromptChars) {
        this.maxPromptChars = maxPromptChars;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public int getMaxFieldsPerSchema() {
        return maxFieldsPerSchema;
    }

    public void setMaxFieldsPerSchema(int maxFieldsPerSchema) {
        this.maxFieldsPerSchema = maxFieldsPerSchema;
    }
}
