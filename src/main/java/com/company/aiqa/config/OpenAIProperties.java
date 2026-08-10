package com.company.aiqa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the "aiqa.openai" section of application.yml.
 * apiKey should be supplied via the OPENAI_API_KEY environment variable,
 * never committed to source control.
 */
@ConfigurationProperties(prefix = "aiqa.openai")
public class OpenAIProperties {

    /** Base URL of the chat-completions compatible endpoint. */
    private String baseUrl = "https://api.openai.com/v1";

    /** Model name, e.g. gpt-4o-mini. */
    private String model = "gpt-4o-mini";

    /** API key - resolved from ${OPENAI_API_KEY}. */
    private String apiKey;

    /** Request timeout in seconds. */
    private int timeoutSeconds = 60;

    /** Sampling temperature. */
    private double temperature = 0.2;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
}
