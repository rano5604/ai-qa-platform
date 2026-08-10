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
 * Anthropic's Messages API. Distinct enough from OpenAI's shape to need its
 * own adapter:
 *   - auth is an {@code x-api-key} header, not {@code Authorization: Bearer}
 *   - an {@code anthropic-version} header is mandatory
 *   - the system prompt is a top-level {@code system} field, NOT a message
 *     with role "system"
 *   - {@code max_tokens} is required, not optional
 *   - the reply is {@code content[0].text}, not {@code choices[0].message.content}
 */
public class AnthropicProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProvider.class);
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public AnthropicProvider(RestClient restClient, String baseUrl, String apiKey, String model) {
        this.restClient = restClient;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String name() {
        return "anthropic";
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
                "max_tokens", maxTokens,
                "temperature", temperature,
                "system", systemPrompt,
                "messages", List.of(Map.of("role", "user", "content", userPrompt))
        );

        try {
            String raw = restClient.post()
                    .uri(baseUrl + "/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode content = objectMapper.readTree(raw).path("content");
            if (!content.isArray() || content.isEmpty()) {
                throw new IllegalStateException("anthropic: unexpected response shape: " + raw);
            }
            String text = content.get(0).path("text").asText("");
            if (text.isBlank()) {
                throw new IllegalStateException("anthropic: empty content in response: " + raw);
            }
            return text;
        } catch (RestClientResponseException e) {
            String responseBody = e.getResponseBodyAsString();
            log.warn("anthropic HTTP {} (model={}) - body: {}", e.getStatusCode().value(), model,
                    responseBody.isBlank() ? "[empty]" : responseBody);
            throw new IllegalStateException("anthropic HTTP %d: %s".formatted(
                    e.getStatusCode().value(), responseBody.isBlank() ? e.getMessage() : responseBody), e);
        }
    }
}
