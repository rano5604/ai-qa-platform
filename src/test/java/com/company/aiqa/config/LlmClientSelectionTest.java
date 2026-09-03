package com.company.aiqa.config;

import com.company.aiqa.ai.GeminiService;
import com.company.aiqa.ai.OpenAIService;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * From the 2026-08-20 mms backfill: a server started with no application.yml
 * on the classpath selected OpenAI by default, reported no credential problem
 * up front, and then failed 16 times over with "No OpenAI API key configured"
 * while working Gemini/Groq/Mistral keys sat unused in the environment.
 */
class LlmClientSelectionTest {

    // ------------------------------------------------- provider selection ----

    /**
     * The blank case IS the incident: an unresolvable property must not be
     * readable as a choice of provider.
     */
    @Test
    void anUnsetProviderFailsStartupAndSaysTheConfigIsProbablyMissing() {
        LlmProperties properties = new LlmProperties();

        IllegalStateException e = assertThrows(IllegalStateException.class, properties::validate);

        assertTrue(e.getMessage().contains("aiqa.llm.provider is not set"), e.getMessage());
        assertTrue(e.getMessage().contains("target/classes/application.yml"), e.getMessage());
    }

    /** A chain entry set as the selector - the mistake application.yml warns about. */
    @Test
    void aChainEntryIsNotAProviderSelector() {
        LlmProperties properties = new LlmProperties();
        properties.setProvider("groq");

        IllegalStateException e = assertThrows(IllegalStateException.class, properties::validate);

        assertTrue(e.getMessage().contains("matches no LlmClient implementation"), e.getMessage());
        assertTrue(e.getMessage().contains("aiqa.router.chain"), e.getMessage());
    }

    @Test
    void everySupportedProviderIsAccepted() {
        for (String valid : new String[] {"openai", "gemini", "router", " Router "}) {
            LlmProperties properties = new LlmProperties();
            properties.setProvider(valid);
            properties.validate();   // must not throw
        }
    }

    // ------------------------------------------------------- credentials ----

    /**
     * The interface default answers true, which let a keyless OpenAIService
     * past QaPipelineService.requireLlmCredentials - the guard whose entire job
     * is to refuse this run before a single prompt is built.
     */
    @Test
    void aKeylessOpenAiClientReportsNoServerSideCredentials() {
        assertFalse(openAiWithKey(null).hasServerSideCredentials());
        assertFalse(openAiWithKey("").hasServerSideCredentials());
        assertFalse(openAiWithKey("   ").hasServerSideCredentials());
    }

    @Test
    void aConfiguredOpenAiClientReportsCredentials() {
        assertTrue(openAiWithKey("sk-test").hasServerSideCredentials());
    }

    @Test
    void geminiAnswersTheSameWay() {
        GeminiProperties blank = new GeminiProperties();
        blank.setApiKey("");
        assertFalse(new GeminiService(RestClient.create(), blank).hasServerSideCredentials());

        GeminiProperties configured = new GeminiProperties();
        configured.setApiKey("test-key");
        assertTrue(new GeminiService(RestClient.create(), configured).hasServerSideCredentials());
    }

    /** The valid list is what both the conditionals and the message rely on. */
    @Test
    void theValidListMatchesTheImplementationsThatHaveABean() {
        assertEquals(java.util.List.of("openai", "gemini", "router"), LlmProperties.VALID_PROVIDERS);
    }

    private OpenAIService openAiWithKey(String key) {
        OpenAIProperties properties = new OpenAIProperties();
        properties.setApiKey(key);
        return new OpenAIService(RestClient.create(), properties);
    }
}
