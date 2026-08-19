package com.company.aiqa.ai.router;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The registry of LLM providers this platform knows how to talk to.
 *
 * <p>Adding a provider is a <b>one-line addition here</b> - no new class, no
 * change to the router - because the overwhelming majority of the market
 * speaks OpenAI's {@code /chat/completions} shape. Only genuinely different
 * wire formats need their own adapter, and there are just three of those
 * (Gemini, Anthropic, Ollama).
 *
 * <p>Model ids and free-tier availability change frequently; the defaults here
 * are a reasonable starting point, and callers can override the model per
 * provider through configuration or a "custom" entry.
 *
 * <p><b>Not included, deliberately:</b> AWS Bedrock and Google Vertex AI. Both
 * authenticate with request signing (SigV4) / GCP service-account credentials
 * rather than a bearer token, so they can't be driven by an API-key string.
 * Reach them through an OpenAI-compatible gateway (LiteLLM, Bedrock Access
 * Gateway, or similar) and register that as a {@code custom} provider.
 */
public final class LlmProviderCatalog {

    /** The wire format a provider speaks - determines which adapter is built. */
    public enum WireFormat {
        /** OpenAI's /chat/completions - the de-facto standard, used by most of the market. */
        OPENAI_COMPAT,
        /** Google's generateContent (key as query param, model in the path). */
        GEMINI,
        /** Anthropic's /v1/messages (x-api-key + anthropic-version headers, system prompt as a top-level field). */
        ANTHROPIC,
        /** Ollama's local /api/chat - no API key; the supplied value is the host URL. */
        OLLAMA
    }

    /** One known provider: where it lives, what it speaks, and a sane default model. */
    public record Entry(String name, WireFormat wireFormat, String baseUrl, String defaultModel) {
    }

    private static final Map<String, Entry> CATALOG = new LinkedHashMap<>();

    private static void add(String name, WireFormat format, String baseUrl, String defaultModel) {
        CATALOG.put(name, new Entry(name, format, baseUrl, defaultModel));
    }

    static {
        // --- OpenAI and the many providers that copied its API ---
        add("openai", WireFormat.OPENAI_COMPAT, "https://api.openai.com/v1", "gpt-4o-mini");
        add("groq", WireFormat.OPENAI_COMPAT, "https://api.groq.com/openai/v1", "openai/gpt-oss-120b");
        add("mistral", WireFormat.OPENAI_COMPAT, "https://api.mistral.ai/v1", "mistral-large-latest");
        add("deepseek", WireFormat.OPENAI_COMPAT, "https://api.deepseek.com/v1", "deepseek-chat");
        add("openrouter", WireFormat.OPENAI_COMPAT, "https://openrouter.ai/api/v1", "meta-llama/llama-3.3-70b-instruct:free");
        add("together", WireFormat.OPENAI_COMPAT, "https://api.together.xyz/v1", "meta-llama/Llama-3.3-70B-Instruct-Turbo");
        add("fireworks", WireFormat.OPENAI_COMPAT, "https://api.fireworks.ai/inference/v1", "accounts/fireworks/models/llama-v3p3-70b-instruct");
        add("perplexity", WireFormat.OPENAI_COMPAT, "https://api.perplexity.ai", "sonar");
        add("xai", WireFormat.OPENAI_COMPAT, "https://api.x.ai/v1", "grok-2-latest");
        add("cerebras", WireFormat.OPENAI_COMPAT, "https://api.cerebras.ai/v1", "llama3.3-70b");
        add("nvidia", WireFormat.OPENAI_COMPAT, "https://integrate.api.nvidia.com/v1", "meta/llama-3.3-70b-instruct");
        add("cohere", WireFormat.OPENAI_COMPAT, "https://api.cohere.ai/compatibility/v1", "command-r-plus");
        add("github", WireFormat.OPENAI_COMPAT, "https://models.github.ai/inference", "openai/gpt-4o-mini");
        // Local / self-hosted servers that expose an OpenAI-compatible API.
        add("lmstudio", WireFormat.OPENAI_COMPAT, "http://localhost:1234/v1", "local-model");
        add("vllm", WireFormat.OPENAI_COMPAT, "http://localhost:8000/v1", "local-model");

        // --- Providers with their own wire format ---
        add("gemini", WireFormat.GEMINI, "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash");
        add("anthropic", WireFormat.ANTHROPIC, "https://api.anthropic.com/v1", "claude-sonnet-4-5");
        add("ollama", WireFormat.OLLAMA, "http://localhost:11434", "qwen2.5-coder:7b");
    }

    private LlmProviderCatalog() {
    }

    public static Optional<Entry> find(String name) {
        return name == null ? Optional.empty()
                : Optional.ofNullable(CATALOG.get(name.trim().toLowerCase(Locale.ROOT)));
    }

    /** Every provider name this platform recognizes - used in error messages and docs. */
    public static java.util.Set<String> knownProviders() {
        return java.util.Collections.unmodifiableSet(CATALOG.keySet());
    }
}
