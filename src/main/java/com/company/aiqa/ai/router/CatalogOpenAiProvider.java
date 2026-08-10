package com.company.aiqa.ai.router;

import org.springframework.web.client.RestClient;

/**
 * An OpenAI-compatible provider built straight from a
 * {@link LlmProviderCatalog.Entry} - which is why adding OpenAI, Groq,
 * DeepSeek, OpenRouter, Together, Fireworks, Perplexity, xAI, Cerebras,
 * NVIDIA, Cohere, GitHub Models, LM Studio, vLLM (or any future clone)
 * needs no new class at all: they differ only by base URL, model, and key.
 *
 * <p>Reuses {@link AbstractOpenAiCompatProvider} for the actual HTTP call, so
 * request/response handling and error semantics stay identical across every
 * provider in this family.
 */
public class CatalogOpenAiProvider extends AbstractOpenAiCompatProvider {

    private final String name;

    public CatalogOpenAiProvider(RestClient restClient, String name, String baseUrl, String apiKey, String model) {
        super(restClient, joinChatCompletions(baseUrl), apiKey, model);
        this.name = name;
    }

    /** Providers publish a base URL; the chat endpoint is always "<base>/chat/completions". */
    private static String joinChatCompletions(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        return base + "/chat/completions";
    }

    @Override
    public String name() {
        return name;
    }
}
