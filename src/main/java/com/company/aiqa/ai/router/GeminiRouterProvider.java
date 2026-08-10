package com.company.aiqa.ai.router;

import com.company.aiqa.config.RouterProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Google Gemini via the generateContent REST endpoint - see
 * ai_router.providers.GeminiProvider. Named "GeminiRouterProvider" (not
 * "GeminiProvider") to avoid colliding with the existing standalone
 * GeminiService (ai.GeminiService), which remains a separate, independent
 * LlmClient selectable via aiqa.llm.provider=gemini - this class is only
 * used inside the router chain (aiqa.llm.provider=router).
 *
 * Gemini's shape differs from the OpenAI-compatible providers: the API key
 * is a query parameter (not an Authorization header), the model is part of
 * the URL path, and the response text is nested under
 * candidates[0].content.parts[0].text rather than choices[0].message.content.
 */
public class GeminiRouterProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiRouterProvider.class);

    /**
     * gemini-2.5-flash (and other "thinking" models) can spend its entire
     * token budget on internal reasoning and return finishReason=STOP with
     * zero output parts - a response that looks successful (HTTP 200, valid
     * JSON envelope) but contains nothing usable. This silently broke test
     * case generation whenever Gemini answered first in the chain. Capping
     * the thinking budget (and giving maxOutputTokens real headroom beyond
     * it) is the fix - mirrors the same hardening in the standalone
     * ai.GeminiService (aiqa.llm.provider=gemini).
     */
    private static final int THINKING_BUDGET = 512;

    /** Any run of the same character this long or longer means the model
     *  degenerated into a repetition loop instead of real content. */
    private static final Pattern DEGENERATE_REPETITION = Pattern.compile("(.)\\1{39,}", Pattern.DOTALL);

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public GeminiRouterProvider(RestClient restClient, RouterProperties props) {
        this.restClient = restClient;
        this.baseUrl = props.getGeminiBaseUrl();
        this.apiKey = props.getGeminiApiKey();
        this.model = props.getGeminiModel();
    }

    @Override
    public String name() { return "gemini"; }

    @Override
    public boolean available() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String model() { return model; }

    @Override
    public String complete(String systemPrompt, String userPrompt, double temperature, int maxTokens) throws Exception {
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userPrompt)))),
                "generationConfig", Map.of(
                        "temperature", temperature,
                        "maxOutputTokens", maxTokens,
                        "thinkingConfig", Map.of("thinkingBudget", THINKING_BUDGET)
                )
        );

        String uri = "%s/models/%s:generateContent?key=%s".formatted(baseUrl, model, apiKey);

        try {
            String rawResponse = restClient.post()
                    .uri(uri)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            String content = extractContent(rawResponse);
            if (content != null && DEGENERATE_REPETITION.matcher(content).find()) {
                // Not a quota problem - the model degenerated into repeating one
                // character. Treated as an ordinary failure so the router falls
                // through to the next candidate in the chain.
                throw new IllegalStateException(
                        "gemini returned degenerate repeated-character output (" + content.length() + " chars)");
            }
            return content;
        } catch (RestClientResponseException e) {
            String responseBody = e.getResponseBodyAsString();
            log.warn("gemini HTTP {} (model={}) - body: {}",
                    e.getStatusCode().value(), model, responseBody.isBlank() ? "[empty]" : responseBody);
            throw new IllegalStateException(
                    "gemini HTTP %d: %s".formatted(e.getStatusCode().value(),
                            responseBody.isBlank() ? e.getMessage() : responseBody), e);
        }
    }

    private String extractContent(String rawResponse) throws Exception {
        JsonNode root = objectMapper.readTree(rawResponse);
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            throw new IllegalStateException("gemini: unexpected response shape: " + rawResponse);
        }
        JsonNode parts = candidates.get(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            throw new IllegalStateException("gemini: unexpected response shape (no parts): " + rawResponse);
        }
        return parts.get(0).path("text").asText();
    }
}
