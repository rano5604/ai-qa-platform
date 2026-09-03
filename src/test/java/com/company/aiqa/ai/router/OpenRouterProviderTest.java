package com.company.aiqa.ai.router;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This platform is meant to call OpenRouter's zero-cost catalog only. Pins
 * that a paid-looking model id can never get through construction, whether
 * as the primary model (fails startup) or a fallback entry (dropped, not
 * fatal) - see OpenRouterProvider's javadoc for why this is enforced in code
 * rather than left as a config convention.
 */
class OpenRouterProviderTest {

    private static Map<String, Object> extraBodyFields(OpenRouterProvider provider) {
        try {
            var method = AbstractOpenAiCompatProvider.class.getDeclaredMethod("extraBodyFields");
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) method.invoke(provider);
            return result;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void freeSuffixedPrimaryModelIsAccepted() {
        OpenRouterProvider provider = new OpenRouterProvider(
                RestClient.create(), "key", "z-ai/glm-5.2:free", List.of(), List.of(), true);
        assertEquals("z-ai/glm-5.2:free", provider.model());
    }

    @Test
    void theFreeRouterAliasIsAccepted() {
        OpenRouterProvider provider = new OpenRouterProvider(
                RestClient.create(), "key", "openrouter/free", List.of(), List.of(), true);
        assertEquals("openrouter/free", provider.model());
    }

    @Test
    void aPaidPrimaryModelFailsConstructionRatherThanBeingCalled() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                new OpenRouterProvider(RestClient.create(), "key", "openai/gpt-4o", List.of(), List.of(), true));
        assertTrue(ex.getMessage().contains("not a free-tier model id"));
    }

    @Test
    void aPaidFallbackEntryIsDroppedNotFatal() {
        OpenRouterProvider provider = new OpenRouterProvider(
                RestClient.create(), "key", "openrouter/free",
                List.of("z-ai/glm-5.2:free", "openai/gpt-4o", "minimax/minimax-m2.7:free"),
                List.of(), true);

        @SuppressWarnings("unchecked")
        List<String> models = (List<String>) extraBodyFields(provider).get("models");
        assertEquals(List.of("z-ai/glm-5.2:free", "minimax/minimax-m2.7:free"), models);
    }

    @Test
    void moreThanThreeFallbackModelsAreTruncatedRatherThanFailingEveryCall() {
        // Verified live: OpenRouter answers 400 "'models' array must have 3
        // items or fewer" for a longer list - see OpenRouterProvider's javadoc.
        OpenRouterProvider provider = new OpenRouterProvider(
                RestClient.create(), "key", "openrouter/free",
                List.of("a:free", "b:free", "c:free", "d:free", "e:free"),
                List.of(), true);

        @SuppressWarnings("unchecked")
        List<String> models = (List<String>) extraBodyFields(provider).get("models");
        assertEquals(3, models.size());
        assertEquals(List.of("a:free", "b:free", "c:free"), models);
    }

    @Test
    void providerOrderAndAllowFallbacksReachTheRequestBody() {
        OpenRouterProvider provider = new OpenRouterProvider(
                RestClient.create(), "key", "openrouter/free",
                List.of(), List.of("Together", "DeepInfra"), false);

        @SuppressWarnings("unchecked")
        Map<String, Object> providerField = (Map<String, Object>) extraBodyFields(provider).get("provider");
        assertEquals(List.of("Together", "DeepInfra"), providerField.get("order"));
        assertEquals(false, providerField.get("allow_fallbacks"));
    }
}
