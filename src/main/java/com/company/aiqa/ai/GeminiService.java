package com.company.aiqa.ai;

import com.company.aiqa.model.LlmKeys;
import com.company.aiqa.config.GeminiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Step: "Send Context to LLM" - Gemini implementation.
 *
 * Gemini's generateContent API has a materially different shape from
 * OpenAI's chat/completions:
 *   - the API key goes in a query parameter, not an Authorization header
 *   - the model is part of the URL path, not the request body
 *   - system/user prompts are "systemInstruction" + "contents", not a
 *     messages array with role fields
 *   - the response text is nested under candidates[0].content.parts[0].text
 *     rather than choices[0].message.content
 *
 * Active only when aiqa.llm.provider=gemini.
 */
@Service
@ConditionalOnProperty(prefix = "aiqa.llm", name = "provider", havingValue = "gemini")
public class GeminiService implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiService.class);

    /**
     * gemini-2.5-flash is a "thinking" model. If we don't cap the thinking
     * budget, it can spend its entire token allowance on internal reasoning
     * and return finishReason=STOP with zero output parts - a response that
     * looks successful (HTTP 200, valid JSON envelope) but contains nothing
     * usable. Capping this to a small budget (or 0 to disable thinking
     * entirely) is the fix for that failure mode.
     */
    private static final int THINKING_BUDGET = 512;

    /** Hard ceiling so one generation can't silently run away on tokens/cost. */
    private static final int MAX_OUTPUT_TOKENS = 8192;

    /** Any run of the same character this long or longer means the model is
     *  degenerating into a repetition loop instead of producing real content
     *  (seen in practice as walls of "*", "A", or "a" that break JSON parsing). */
    private static final Pattern DEGENERATE_REPETITION = Pattern.compile("(.)\\1{39,}", Pattern.DOTALL);

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_DELAY_MS = 1000;

    private final RestClient restClient;
    private final GeminiProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GeminiService(RestClient restClient, GeminiProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, userPrompt, (LlmKeys) null);
    }

    /**
     * Per-request credentials: a caller-supplied llmKeys.gemini takes precedence
     * over the configured one for this call only. Passing null (or an LlmKeys
     * with no "gemini" entry) uses the configured key exactly as before.
     */
    @Override
    public String complete(String systemPrompt, String userPrompt, LlmKeys keys) {
        String apiKey = (keys != null && keys.getGemini() != null && !keys.getGemini().isBlank())
                ? keys.getGemini()
                : properties.getApiKey();

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "No Gemini API key configured. Set the GEMINI_API_KEY environment variable, "
                            + "or supply llmKeys.gemini in the request.");
        }
        if (properties.getModel() == null || properties.getModel().isBlank()) {
            throw new IllegalStateException("No Gemini model configured (aiqa.gemini.model is blank).");
        }

        Exception lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String result = attemptComplete(systemPrompt, userPrompt, apiKey);
                if (attempt > 1) {
                    log.info("Gemini call succeeded on attempt {}/{}", attempt, MAX_ATTEMPTS);
                }
                return result;
            } catch (RetryableGeminiException | RestClientException e) {
                lastFailure = e;
                log.warn("Gemini call attempt {}/{} failed ({}): {}",
                        attempt, MAX_ATTEMPTS, e.getClass().getSimpleName(), e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleepBeforeRetry(attempt);
                }
            }
        }

        throw new IllegalStateException(
                "Gemini call failed after " + MAX_ATTEMPTS + " attempts: "
                        + (lastFailure != null ? lastFailure.getMessage() : "unknown error"),
                lastFailure);
    }

    private String attemptComplete(String systemPrompt, String userPrompt, String apiKey) {
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of(
                        "parts", List.of(Map.of("text", systemPrompt))
                ),
                "contents", List.of(
                        Map.of("role", "user", "parts", List.of(Map.of("text", userPrompt)))
                ),
                "generationConfig", Map.of(
                        "temperature", properties.getTemperature(),
                        "maxOutputTokens", MAX_OUTPUT_TOKENS,
                        "thinkingConfig", Map.of("thinkingBudget", THINKING_BUDGET)
                )
        );

        String uri = "%s/models/%s:generateContent?key=%s"
                .formatted(properties.getBaseUrl(), properties.getModel(), apiKey);

        try {
            String rawResponse = restClient.post()
                    .uri(uri)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            String content = extractContent(rawResponse);

            if (isDegenerateOutput(content)) {
                // Looks like a successful call, but the model spiralled into
                // repeating one character instead of generating real content.
                // Treat it the same as a transient failure and let the retry
                // loop try again - a second attempt usually produces normal
                // output.
                throw new RetryableGeminiException(
                        "Gemini returned degenerate repeated-character output ("
                                + content.length() + " chars) instead of valid content");
            }

            return content;
        } catch (RestClientResponseException e) {
            String responseBody = e.getResponseBodyAsString();
            log.error("Gemini call failed: HTTP {} (model={}) - body: {}",
                    e.getStatusCode().value(), properties.getModel(),
                    responseBody.isBlank() ? "[empty]" : responseBody);
            throw new IllegalStateException(
                    "Gemini call failed: HTTP %d - %s".formatted(
                            e.getStatusCode().value(),
                            responseBody.isBlank() ? e.getMessage() : responseBody),
                    e);
        } catch (RetryableGeminiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Gemini call failed: {}", e.getMessage(), e);
            throw new IllegalStateException("Gemini call failed: " + e.getMessage(), e);
        }
    }

    private String extractContent(String rawResponse) throws Exception {
        JsonNode root = objectMapper.readTree(rawResponse);

        // A blocked prompt (safety filter) also comes back as HTTP 200 with no
        // candidates, but with a promptFeedback.blockReason - surface that
        // distinctly since retrying won't help without changing the prompt.
        JsonNode blockReason = root.path("promptFeedback").path("blockReason");
        if (!blockReason.isMissingNode() && !blockReason.isNull()) {
            throw new IllegalStateException(
                    "Gemini blocked the prompt (blockReason=" + blockReason.asText() + "): " + rawResponse);
        }

        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            throw new IllegalStateException("Unexpected Gemini response shape: " + rawResponse);
        }

        JsonNode candidate = candidates.get(0);
        String finishReason = candidate.path("finishReason").asText("");
        JsonNode parts = candidate.path("content").path("parts");

        if (!parts.isArray() || parts.isEmpty()) {
            if ("MAX_TOKENS".equals(finishReason)) {
                // Ran out of budget entirely inside "thinking" - retryable,
                // since a fresh attempt often finishes within budget.
                throw new RetryableGeminiException(
                        "Gemini hit MAX_TOKENS before producing output (thinking budget exhausted): " + rawResponse);
            }
            throw new IllegalStateException("Unexpected Gemini response shape (no parts): " + rawResponse);
        }

        return parts.get(0).path("text").asText();
    }

    private boolean isDegenerateOutput(String content) {
        return content != null && DEGENERATE_REPETITION.matcher(content).find();
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(RETRY_BASE_DELAY_MS * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Marks a failure as worth retrying rather than failing the merge outright. */
    private static class RetryableGeminiException extends RuntimeException {
        RetryableGeminiException(String message) {
            super(message);
        }
    }
}
