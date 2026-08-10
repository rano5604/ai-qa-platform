package com.company.aiqa.ai.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which models are temporarily out of action, so an exhausted one
 * rotates out <b>across requests</b> rather than being retried (and failing)
 * on every single call.
 *
 * <p>Without this, a model that has burned its daily quota would still be
 * tried first on every subsequent request - wasting a network round-trip, and
 * on a per-category generation run that's one wasted call per category per
 * merge. Benching it means the next request goes straight to a model that
 * can actually answer.
 *
 * <p>Two cooldown lengths, because the two failure modes are very different:
 * <ul>
 *   <li><b>Quota / rate limit</b> (HTTP 429, "insufficient_quota",
 *       "RESOURCE_EXHAUSTED") - long bench, since free tiers typically reset
 *       on a daily window. A provider-supplied {@code Retry-After} wins when
 *       present, because that's authoritative.</li>
 *   <li><b>Anything else</b> (5xx, network blip, malformed response) - short
 *       bench, since these are usually transient.</li>
 * </ul>
 *
 * <p>Cooldowns expire on their own, so a benched model returns to rotation
 * with no restart and no manual intervention. State is in-memory and
 * per-instance: a restart clears it (worst case, one wasted retry), and in a
 * multi-instance deployment each node learns independently.
 */
@Component
public class ProviderCooldownRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderCooldownRegistry.class);

    private static final Duration QUOTA_COOLDOWN = Duration.ofMinutes(60);
    private static final Duration ERROR_COOLDOWN = Duration.ofMinutes(5);

    /** provider name -> instant it becomes eligible again. */
    private final Map<String, Instant> benchedUntil = new ConcurrentHashMap<>();

    /** True when this provider is currently benched and should be skipped. */
    public boolean isCoolingDown(String provider) {
        Instant until = benchedUntil.get(key(provider));
        if (until == null) {
            return false;
        }
        if (Instant.now().isAfter(until)) {
            benchedUntil.remove(key(provider));   // expired - back in rotation
            return false;
        }
        return true;
    }

    public Instant cooldownEndsAt(String provider) {
        return benchedUntil.get(key(provider));
    }

    /** Clears any bench after a successful call - a working model shouldn't stay penalised. */
    public void recordSuccess(String provider) {
        benchedUntil.remove(key(provider));
    }

    /**
     * Benches a provider based on why it failed. Quota/rate-limit failures get
     * the long cooldown; everything else gets the short one.
     */
    public void recordFailure(String provider, Throwable failure) {
        // "Your prompt is too long" says nothing about the model's health - it
        // says the REQUEST was too big. Benching for it would take a perfectly
        // working model out of rotation and make every following category fail
        // too, while the caller's own fix (splitting the batch - see
        // QaPipelineService.generateWithSplitting) is already underway.
        if (isPromptTooLarge(failure)) {
            log.debug("Model '{}' rejected an oversized prompt - not benching it; the request will be split and retried.",
                    provider);
            return;
        }

        boolean exhausted = isQuotaFailure(failure);
        Duration cooldown = exhausted
                ? retryAfter(failure).orElse(QUOTA_COOLDOWN)
                : ERROR_COOLDOWN;

        Instant until = Instant.now().plus(cooldown);
        benchedUntil.put(key(provider), until);

        log.warn("Model '{}' benched until {} ({}) - rotation will skip it until then.",
                provider, until, exhausted ? "quota/rate limit exhausted" : "transient failure");
    }

    /**
     * Recognises "your prompt is too long" across providers. Lives here (the
     * lowest layer that sees raw provider errors) so both the cooldown logic
     * and the pipeline's batch-splitting can share one definition.
     *
     * <p>This is a property of the REQUEST, not the model - which is why it
     * never benches a provider, and why the caller's correct response is to
     * split the input and retry rather than rotate to another model that would
     * reject the identical prompt.
     */
    public static boolean isPromptTooLarge(Throwable failure) {
        String text = messagesOf(failure).toLowerCase(Locale.ROOT);
        return text.contains("context_length_exceeded")
                || text.contains("context length")
                || text.contains("maximum context")
                || text.contains("too many tokens")
                || text.contains("prompt is too long")
                || text.contains("input is too long")
                || text.contains("request too large")
                || text.contains("reduce the length")
                || text.contains("string_above_max_length");
    }

    /** Walks the cause chain - the useful signal is often in a wrapped exception. */
    private static String messagesOf(Throwable failure) {
        StringBuilder sb = new StringBuilder();
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 5) {
            if (current.getMessage() != null) {
                sb.append(current.getMessage()).append(' ');
            }
            current = current.getCause();
        }
        return sb.toString();
    }

    /** Recognises the quota/rate-limit signals used across the major providers. */
    private boolean isQuotaFailure(Throwable failure) {
        String message = collectMessages(failure).toLowerCase(Locale.ROOT);
        return message.contains("429")
                || message.contains("too many requests")
                || message.contains("rate limit")
                || message.contains("rate_limit")
                || message.contains("insufficient_quota")
                || message.contains("resource_exhausted")
                || message.contains("quota");
    }

    /** Honours an explicit Retry-After (in seconds) when the provider supplied one. */
    private java.util.Optional<Duration> retryAfter(Throwable failure) {
        if (failure instanceof org.springframework.web.client.RestClientResponseException e) {
            org.springframework.http.HttpHeaders headers = e.getResponseHeaders();
            if (headers != null) {
                String header = headers.getFirst("Retry-After");
                if (header != null && !header.isBlank()) {
                    try {
                        return java.util.Optional.of(Duration.ofSeconds(Long.parseLong(header.trim())));
                    } catch (NumberFormatException ignored) {
                        // Retry-After may also be an HTTP-date; fall back to the default.
                    }
                }
            }
        }
        return java.util.Optional.empty();
    }

    /** Walks the cause chain - the useful signal is often in a wrapped exception. */
    private String collectMessages(Throwable failure) {
        StringBuilder sb = new StringBuilder();
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 5) {
            if (current.getMessage() != null) {
                sb.append(current.getMessage()).append(' ');
            }
            current = current.getCause();
        }
        return sb.toString();
    }

    private String key(String provider) {
        return provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    }
}
