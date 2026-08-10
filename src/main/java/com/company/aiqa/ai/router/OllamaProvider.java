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

/**
 * A local model served by Ollama - no API key, nothing leaves the machine.
 * See ai_router.providers.OllamaProvider. available() always returns true
 * (there's no key to check); if Ollama isn't running, the connection error
 * on complete() is what makes the router fall through to the next provider
 * (or fail, if Ollama is last in the chain).
 */
public class OllamaProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaProvider.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String host;
    private final String model;

    public OllamaProvider(RestClient restClient, RouterProperties props) {
        this.restClient = restClient;
        this.host = props.getOllamaHost() == null ? "" : props.getOllamaHost().replaceAll("/+$", "");
        this.model = props.getOllamaModel();
    }

    @Override
    public String name() { return "ollama"; }

    @Override
    public boolean available() { return true; }

    @Override
    public String model() { return model; }

    @Override
    public String complete(String systemPrompt, String userPrompt, double temperature, int maxTokens) throws Exception {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "stream", false,
                "options", Map.of("temperature", temperature, "num_predict", maxTokens)
        );

        try {
            String rawResponse = restClient.post()
                    .uri(host + "/api/chat")
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            return extractContent(rawResponse);
        } catch (RestClientResponseException e) {
            String responseBody = e.getResponseBodyAsString();
            log.warn("ollama HTTP {} (model={}) - body: {}",
                    e.getStatusCode().value(), model, responseBody.isBlank() ? "[empty]" : responseBody);
            throw new IllegalStateException(
                    "ollama HTTP %d: %s".formatted(e.getStatusCode().value(),
                            responseBody.isBlank() ? e.getMessage() : responseBody), e);
        }
    }

    private String extractContent(String rawResponse) throws Exception {
        JsonNode root = objectMapper.readTree(rawResponse);
        JsonNode content = root.path("message").path("content");
        if (content.isMissingNode()) {
            throw new IllegalStateException("ollama: unexpected response shape: " + rawResponse);
        }
        return content.asText();
    }
}
