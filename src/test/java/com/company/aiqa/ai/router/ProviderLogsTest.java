package com.company.aiqa.ai.router;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderLogsTest {

    @Test
    void namesEveryProviderReasonOnOneLine() {
        AllProvidersFailedException e = new AllProvidersFailedException(List.of(
                "gemini: HTTP 429 {\n  \"error\": {\n    \"message\": \"Resource exhausted\"\n  }\n}",
                "groq: HTTP 413 request too large",
                "mistral: HTTP 402 payment required"));

        String line = ProviderLogs.compactFailure(e);

        assertEquals(1, line.lines().count());
        assertTrue(line.contains("gemini"), line);
        assertTrue(line.contains("groq"), line);
        assertTrue(line.contains("mistral"), line);
        // The JSON body's structural lines carry nothing a reader needs.
        assertFalse(line.contains("\"error\""), line);
    }

    /**
     * The reported bug: taking the first line of this message yields the fixed
     * header and nothing else, so every failed case read "All providers failed:".
     */
    @Test
    void doesNotStopAtTheHeaderLine() {
        AllProvidersFailedException e = new AllProvidersFailedException(List.of("gemini: HTTP 401 API key not valid"));

        String line = ProviderLogs.compactFailure(e);

        assertFalse(line.equals("All providers failed:"), line);
        assertTrue(line.contains("401"), line);
    }

    @Test
    void fallsBackToTheTypeWhenThereIsNoMessage() {
        assertEquals("RuntimeException", ProviderLogs.compactFailure(new RuntimeException()));
    }
}
