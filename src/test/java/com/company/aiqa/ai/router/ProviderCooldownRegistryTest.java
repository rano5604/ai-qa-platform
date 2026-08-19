package com.company.aiqa.ai.router;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderCooldownRegistryTest {

    private final ProviderCooldownRegistry registry = new ProviderCooldownRegistry();

    /**
     * The Cerebras case: a wrong model id answers 404 for as long as the config
     * says so. Benching it swaps the sentence naming the fix for "cooling down
     * until ...", which reads like something that will pass on its own.
     */
    @Test
    void aWrongModelIdIsNotBenched() {
        registry.recordFailure("cerebras", new RuntimeException(
                "cerebras HTTP 404: {\"message\":\"Model does not exist or you do not have access to it.\","
                        + "\"type\":\"not_found_error\",\"param\":\"model\",\"code\":\"model_not_found\"}"));

        assertFalse(registry.isCoolingDown("cerebras"));
    }

    @Test
    void aRejectedKeyIsNotBenched() {
        registry.recordFailure("gemini", new RuntimeException("gemini HTTP 400: API_KEY_INVALID - API key not valid"));

        assertFalse(registry.isCoolingDown("gemini"));
    }

    @Test
    void aRateLimitIsStillBenched() {
        registry.recordFailure("groq", new RuntimeException("groq HTTP 429: rate limit reached for model"));

        assertTrue(registry.isCoolingDown("groq"));
    }

    @Test
    void aTransientFailureIsStillBenched() {
        registry.recordFailure("mistral", new RuntimeException("mistral HTTP 503: service unavailable"));

        assertTrue(registry.isCoolingDown("mistral"));
    }

    @Test
    void anOversizedPromptIsNotBenched() {
        registry.recordFailure("groq", new RuntimeException("groq HTTP 413: Request too large for model"));

        assertFalse(registry.isCoolingDown("groq"));
    }

    /**
     * Gemini's free tier limits by the minute and says so in the body. Benching
     * for an hour turned a 31-second wait into three consecutive mms runs with
     * no provider available at all.
     */
    @Test
    void aPerMinuteLimitIsBenchedForTheDelayTheProviderNamed() {
        registry.recordFailure("gemini", new RuntimeException(
                "gemini HTTP 429: {\"error\":{\"code\":429,\"message\":\"Resource has been exhausted\","
                        + "\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"31s\"}]}}"));

        Instant until = registry.cooldownEndsAt("gemini");
        assertNotNull(until);
        assertTrue(until.isBefore(Instant.now().plusSeconds(60)), "benched until " + until);
    }

    @Test
    void groqsPhrasingIsUnderstoodToo() {
        registry.recordFailure("groq", new RuntimeException(
                "groq HTTP 429: rate limit reached, please try again in 7.5s"));

        assertTrue(registry.cooldownEndsAt("groq").isBefore(Instant.now().plusSeconds(30)));
    }

    @Test
    void aRateLimitWithNoStatedDelayGetsAShortBenchNotAnHour() {
        registry.recordFailure("groq", new RuntimeException("groq HTTP 429: too many requests"));

        Instant until = registry.cooldownEndsAt("groq");
        assertTrue(until.isBefore(Instant.now().plusSeconds(5 * 60)), "benched until " + until);
    }

    /** A quota that is gone for the day is the one case an hour is right. */
    @Test
    void aDailyQuotaStillGetsTheLongBench() {
        registry.recordFailure("gemini", new RuntimeException(
                "gemini HTTP 429: Quota exceeded for quota metric 'Generate requests per day'"));

        assertTrue(registry.cooldownEndsAt("gemini").isAfter(Instant.now().plusSeconds(30 * 60)));
    }
}
