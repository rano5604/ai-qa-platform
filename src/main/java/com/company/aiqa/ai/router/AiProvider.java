package com.company.aiqa.ai.router;

/**
 * One backend the AiRouterService can call - the Java counterpart to
 * ai_router.base.Provider from the Python ai-router package this was
 * ported from. Each implementation wraps exactly one HTTP shape (OpenAI-
 * compatible /chat/completions, Gemini's generateContent, or Ollama's
 * local /api/chat).
 *
 * complete() must throw on ANY failure (missing key, network error, non-2xx
 * status, unexpected response shape) so AiRouterService can catch it and
 * fall through to the next provider in the chain - it must never return a
 * partial/garbage string as if it succeeded.
 */
public interface AiProvider {

    /** Short name used in aiqa.router.chain and in error/log messages, e.g. "gemini". */
    String name();

    /** Cheap check - is this provider configured enough to even try (e.g. has an API key)? */
    boolean available();

    /** Model id this provider is currently configured to call - for logging/metadata only. */
    String model();

    /**
     * Calls the backend and returns the assistant's text response.
     *
     * @throws Exception on any failure - missing config, network error, non-2xx
     *                    response, or a response that doesn't parse as expected.
     *                    The router treats any thrown exception as "try the next
     *                    provider", so implementations should NOT catch and
     *                    swallow errors here.
     */
    String complete(String systemPrompt, String userPrompt, double temperature, int maxTokens) throws Exception;
}
