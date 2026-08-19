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

    /**
     * Only for a quota that really is exhausted for the day - see
     * {@link #isDailyQuota}. This used to apply to EVERY 429, which turned a
     * per-minute rate limit that clears in 30 seconds into an hour with no
     * provider at all: three consecutive mms runs reported "cooling down until"
     * for a model that had been ready again within a minute of the first one.
     */
    private static final Duration QUOTA_COOLDOWN = Duration.ofMinutes(60);

    /**
     * A rate limit that names no delay. Short, because the common case is a
     * per-minute window - and a provider benched slightly too long costs a run,
     * while one benched slightly too briefly costs a single wasted request.
     */
    private static final Duration RATE_LIMIT_COOLDOWN = Duration.ofMinutes(2);
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

        // A wrong model id or a rejected key fails identically on every call,
        // for as long as the configuration says so. Benching it replaces the
        // one message that names the problem - "Model does not exist or you do
        // not have access to it" - with "cooling down until ...", so the
        // operator sees a temporary-looking symptom instead of the sentence
        // telling them exactly what to change. Cost of not benching: one fast
        // 404 per call until it's fixed.
        if (isConfigurationFailure(failure)) {
            log.warn("Model '{}' is misconfigured, not benched: {}. Fix the model id or key - a cooldown would "
                    + "only hide this behind 'cooling down' on every later run.",
                    provider, ProviderLogs.oneLine(collectMessages(failure)));
            return;
        }

        boolean exhausted = isQuotaFailure(failure);
        Duration cooldown;
        if (!exhausted) {
            cooldown = ERROR_COOLDOWN;
        } else {
            // Providers usually say how long to wait - a Retry-After header,
            // Gemini's "retryDelay": "31s", Groq's "try again in 7.5s". Taking
            // them at their word beats any constant we could pick.
            cooldown = retryAfter(failure)
                    .or(() -> statedDelay(failure))
                    .orElse(isDailyQuota(failure) ? QUOTA_COOLDOWN : RATE_LIMIT_COOLDOWN);
        }

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
    /**
     * A failure that will repeat identically until someone edits configuration:
     * a model id the provider doesn't have, or a credential it won't accept.
     *
     * <p>Distinct from a quota failure, which resolves on its own with time,
     * and from a transient one, which may resolve on the next call. Neither
     * cooldown helps here - only a change to the config does.
     */
    static boolean isConfigurationFailure(Throwable failure) {
        String text = messagesOf(failure).toLowerCase(Locale.ROOT);
        // Checked before the credential words below, since "model_not_found"
        // is the more specific and more common misconfiguration.
        if (text.contains("model_not_found")
                || text.contains("model does not exist")
                || text.contains("does not exist or you do not have access")
                || text.contains("unknown model")
                || text.contains("model not found")) {
            return true;
        }
        return text.contains("api key not valid")
                || text.contains("invalid api key")
                || text.contains("incorrect api key")
                || text.contains("api_key_invalid")
                || text.contains("invalid_api_key");
    }

    /** "retryDelay": "31s" (Gemini) and "try again in 7.5s" / "in 1m30s" (OpenAI-compatible). */
    private static final java.util.regex.Pattern STATED_DELAY = java.util.regex.Pattern.compile(
            "(?:retrydelay\"?\\s*[:=]\\s*\"?|try again in\\s+)(?:(\\d+)m)?(\\d+(?:\\.\\d+)?)s");

    /**
     * The wait the provider itself named in the response body, as opposed to
     * the Retry-After header - most of them put it in one place or the other,
     * rarely both.
     */
    private java.util.Optional<Duration> statedDelay(Throwable failure) {
        java.util.regex.Matcher m = STATED_DELAY.matcher(collectMessages(failure).toLowerCase(Locale.ROOT));
        if (!m.find()) {
            return java.util.Optional.empty();
        }
        try {
            long minutes = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
            double seconds = Double.parseDouble(m.group(2));
            // Round up: waiting a fraction of a second too little just fails again.
            return java.util.Optional.of(Duration.ofMinutes(minutes).plusSeconds((long) Math.ceil(seconds)));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    /**
     * Whether the quota is gone for the day rather than for this minute. Only
     * this deserves the hour-long bench; a per-minute window clears while a
     * single batch is still running.
     */
    private boolean isDailyQuota(Throwable failure) {
        String text = collectMessages(failure).toLowerCase(Locale.ROOT);
        return text.contains("per day")
                || text.contains("perday")
                || text.contains("daily")
                || text.contains("insufficient_quota")
                || text.contains("exceeded your current quota")
                || text.contains("billing");
    }

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
