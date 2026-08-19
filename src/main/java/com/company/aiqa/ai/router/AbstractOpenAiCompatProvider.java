package com.company.aiqa.ai.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * Shared base for OpenAI-compatible chat-completions providers - Groq,
 * Mistral, and GitHub Models all speak the same /chat/completions shape
 * (see ai_router._openai_compat.OpenAICompatProvider in the Python
 * original), differing only by endpoint URL, model id, and API key.
 */
public abstract class AbstractOpenAiCompatProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(AbstractOpenAiCompatProvider.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String endpoint;
    private final String apiKey;
    private final String model;

    protected AbstractOpenAiCompatProvider(RestClient restClient, String endpoint, String apiKey, String model) {
        this.restClient = restClient;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public boolean available() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, double temperature, int maxTokens) throws Exception {
        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", temperature,
                "max_tokens", maxTokens,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        try {
            String rawResponse = restClient.post()
                    .uri(endpoint)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            return extractContent(rawResponse);
        } catch (RestClientResponseException e) {
            String responseBody = e.getResponseBodyAsString();
            log.warn("{} HTTP {} calling {} (model={}) - body: {}",
                    name(), e.getStatusCode().value(), endpoint, model, ProviderLogs.oneLine(responseBody.isBlank() ? "[empty]" : responseBody));
            throw new IllegalStateException(
                    "%s HTTP %d: %s".formatted(name(), e.getStatusCode().value(),
                            responseBody.isBlank() ? e.getMessage() : responseBody), e);
        }
    }

    private String extractContent(String rawResponse) throws Exception {
        JsonNode root = objectMapper.readTree(rawResponse);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException(name() + ": unexpected response shape: " + rawResponse);
        }
        return choices.get(0).path("message").path("content").asText();
    }
}
