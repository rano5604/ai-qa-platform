package com.company.aiqa.ai;

import com.company.aiqa.model.LlmKeys;

/**
 * Abstraction over "call an LLM with a system+user prompt, get text back".
 * Lets the pipeline stay provider-agnostic - see OpenAIService and
 * GeminiService for the two single-provider implementations, and
 * ai.router.AiRouterService for a multi-provider fallback chain, selected
 * via aiqa.llm.provider ("openai" default, "gemini", or "router").
 */
public interface LlmClient {

    /** Calls the LLM using the server-configured credentials. */
    String complete(String systemPrompt, String userPrompt);

    /**
     * Calls the LLM using per-request credentials (see LlmKeys): only the
     * providers whose key the caller supplied are active for this call;
     * every other provider is skipped entirely rather than attempted.
     * Passing null (or an empty LlmKeys) falls back to the server-configured
     * credentials, which is what this default does - implementations that
     * genuinely support per-request keys override it.
     */
    default String complete(String systemPrompt, String userPrompt, LlmKeys keys) {
        return complete(systemPrompt, userPrompt);
    }
}
