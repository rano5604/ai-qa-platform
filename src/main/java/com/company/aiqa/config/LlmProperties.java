package com.company.aiqa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds "aiqa.llm.provider" - which LlmClient implementation is active.
 * Valid values: "openai" (default), "gemini", or "router" (tries every
 * candidate configured under aiqa.router.chain in order, falling through to
 * the next whenever the current one is unconfigured or fails - see
 * ai.router.AiRouterService). Any other value (including leaving it unset
 * to something that isn't one of these three) means Spring finds NO
 * matching LlmClient bean and the application fails to start.
 */
@ConfigurationProperties(prefix = "aiqa.llm")
public class LlmProperties {

    private String provider = "openai";

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
}
