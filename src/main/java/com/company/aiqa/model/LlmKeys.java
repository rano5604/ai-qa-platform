package com.company.aiqa.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-request LLM credentials, supplied in the body of any test-case
 * generation request:
 *
 * <pre>
 * "llmKeys": {
 *   "openai":     "sk-...",
 *   "anthropic":  "sk-ant-...",
 *   "gemini":     "AIza...",
 *   "groq":       "gsk_...",
 *   "deepseek":   "sk-...",
 *   "ollama":     "http://localhost:11434",
 *   "custom": [
 *     { "name": "my-gateway", "baseUrl": "https://llm.internal/v1", "model": "llama-3.3-70b", "apiKey": "..." }
 *   ]
 * }
 * </pre>
 *
 * <p><b>Activation rule</b> - the whole point of this type: a model is ACTIVE
 * for a request only if that request supplied its key. Any model whose key is
 * absent or blank is INACTIVE - never constructed, never called. Supply only
 * the models you want used.
 *
 * <p><b>Any provider, no code change.</b> Keys are held in an open map
 * (Jackson {@code @JsonAnySetter}), so any provider name the catalog knows
 * about - see {@code LlmProviderCatalog} - can be supplied without touching
 * this class. Unknown names are reported rather than silently ignored.
 *
 * <p>Two entries are special: {@code ollama} takes a host URL rather than an
 * API key (it's a local server with no auth), and {@code custom} is a list of
 * arbitrary OpenAI-compatible endpoints rather than a single string.
 *
 * <p>NOTE: these values are secrets. They live only for the duration of the
 * request, are never logged (see {@link #toString()}), and are never written
 * to generated output or merge history.
 */
public class LlmKeys {

    /** provider name (lower-case) -> API key / host URL. */
    private final Map<String, String> keys = new LinkedHashMap<>();

    /** Arbitrary OpenAI-compatible endpoints that aren't in the built-in catalog. */
    private List<CustomProvider> custom = new ArrayList<>();

    /** Any JSON property that isn't "custom" is treated as a provider key. */
    @JsonAnySetter
    public void put(String provider, Object value) {
        if (provider == null || value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (!text.isBlank()) {
            keys.put(provider.trim().toLowerCase(Locale.ROOT), text);
        }
    }

    @JsonAnyGetter
    public Map<String, String> getKeys() {
        return keys;
    }

    public List<CustomProvider> getCustom() {
        return custom == null ? List.of() : custom;
    }

    public void setCustom(List<CustomProvider> custom) {
        this.custom = custom;
    }

    /** The key/host supplied for a provider, or null when the caller didn't activate it. */
    public String get(String provider) {
        return provider == null ? null : keys.get(provider.toLowerCase(Locale.ROOT));
    }

    public boolean has(String provider) {
        String v = get(provider);
        return v != null && !v.isBlank();
    }

    /** True when this object activates nothing at all. */
    public boolean isEmpty() {
        return keys.isEmpty() && getCustom().isEmpty();
    }

    /**
     * Provider names this request activated, in the order supplied. Used both
     * to build the rotation chain and to log WHICH models are active without
     * ever logging a key value.
     */
    public List<String> activatedProviderNames() {
        List<String> active = new ArrayList<>(keys.keySet());
        for (CustomProvider c : getCustom()) {
            if (c != null && c.getName() != null && !c.getName().isBlank()) {
                active.add(c.getName().trim().toLowerCase(Locale.ROOT));
            }
        }
        return active;
    }

    // --- Convenience accessors kept so the single-provider services
    // --- (GeminiService / OpenAIService) keep working unchanged.
    public String getGemini() { return get("gemini"); }
    public String getOpenai() { return get("openai"); }
    public String getOllama() { return get("ollama"); }

    /** An OpenAI-compatible endpoint supplied inline by the caller. */
    public static class CustomProvider {
        private String name;
        private String baseUrl;
        private String model;
        private String apiKey;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        @Override
        public String toString() {
            return "CustomProvider[name=" + name + ", baseUrl=" + baseUrl + ", model=" + model + ", apiKey=***]";
        }
    }

    /** Deliberately redacted - prevents a stray log/debug call from leaking a key. */
    @Override
    public String toString() {
        return "LlmKeys[active=" + activatedProviderNames() + "]";
    }
}
