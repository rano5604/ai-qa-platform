package com.company.aiqa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the "aiqa.gemini" section of application.yml. Only used when
 * aiqa.llm.provider=gemini. apiKey should come from the GEMINI_API_KEY
 * environment variable, never hardcoded.
 */
@ConfigurationProperties(prefix = "aiqa.gemini")
public class GeminiProperties {

    private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";

    /**
     * As of mid-2026, Gemini 1.5 and 2.0 have both been retired - use a
     * current 2.5+ model. Check https://ai.google.dev/api/models (ListModels)
     * against your own key if this ever 404s again; available models change
     * over time and vary by account/region.
     */
    private String model = "gemini-2.5-flash";

    private String apiKey;

    private double temperature = 0.2;

    private int timeoutSeconds = 60;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
