package com.company.aiqa.ai;

import com.company.aiqa.model.LlmKeys;
import com.company.aiqa.config.OpenAIProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Step: "Send Context to LLM" - OpenAI-compatible implementation.
 *
 * Thin client around the OpenAI-compatible /chat/completions endpoint.
 * The base URL is configurable (aiqa.openai.base-url) so this also works
 * against Azure OpenAI-compatible gateways or a self-hosted proxy.
 *
 * Active when aiqa.llm.provider=openai (the default) - see GeminiService
 * for the alternative provider.
 */
@Service
@ConditionalOnProperty(prefix = "aiqa.llm", name = "provider", havingValue = "openai", matchIfMissing = true)
public class OpenAIService implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAIService.class);

    private final RestClient restClient;
    private final OpenAIProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAIService(RestClient restClient, OpenAIProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /**
     * Sends system + user prompt and returns the raw assistant text
     * (expected to be a JSON array per PromptBuilder's contract).
     */
    @Override
    public String complete(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, userPrompt, (LlmKeys) null);
    }

    /**
     * Per-request credentials: a caller-supplied llmKeys.openai takes precedence
     * over the configured one for this call only. Passing null (or an LlmKeys
     * with no "openai" entry) uses the configured key exactly as before.
     */
    @Override
    public String complete(String systemPrompt, String userPrompt, LlmKeys keys) {
        String apiKey = (keys != null && keys.getOpenai() != null && !keys.getOpenai().isBlank())
                ? keys.getOpenai()
                : properties.getApiKey();

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "No OpenAI API key configured. Set the OPENAI_API_KEY environment variable, "
                            + "or supply llmKeys.openai in the request.");
        }
        if (properties.getModel() == null || properties.getModel().isBlank()) {
            throw new IllegalStateException(
                    "No OpenAI model configured (aiqa.openai.model is blank) - this alone can cause a 404 from the API.");
        }

        Map<String, Object> body = Map.of(
                "model", properties.getModel(),
                "temperature", properties.getTemperature(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        try {
            String rawResponse = restClient.post()
                    .uri(properties.getBaseUrl() + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            return extractContent(rawResponse);
        } catch (RestClientResponseException e) {
            // This is the useful case: OpenAI returned a non-2xx status. The
            // response body (usually {"error": {"message": "...", "type": "...", ...}})
            // is exactly what explains a 404/401/429/etc - surface it instead of
            // letting it get discarded.
            String responseBody = e.getResponseBodyAsString();
            log.error("LLM call failed: HTTP {} calling {} (model={}) - body: {}",
                    e.getStatusCode().value(), properties.getBaseUrl(), properties.getModel(),
                    responseBody.isBlank() ? "[empty]" : responseBody);
            throw new IllegalStateException(
                    "LLM call failed: HTTP %d - %s".formatted(
                            e.getStatusCode().value(),
                            responseBody.isBlank() ? e.getMessage() : responseBody),
                    e);
        } catch (Exception e) {
            log.error("LLM call failed: {}", e.getMessage(), e);
            throw new IllegalStateException("LLM call failed: " + e.getMessage(), e);
        }
    }

    private String extractContent(String rawResponse) throws Exception {
        JsonNode root = objectMapper.readTree(rawResponse);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException("Unexpected LLM response shape: " + rawResponse);
        }
        return choices.get(0).path("message").path("content").asText();
    }
}
