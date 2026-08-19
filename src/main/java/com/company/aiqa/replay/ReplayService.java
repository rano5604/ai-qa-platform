package com.company.aiqa.replay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Re-issues one captured request on behalf of a report, and holds the token
 * that says the caller is entitled to ask.
 *
 * <p><b>Why a token.</b> This endpoint answers cross-origin calls from any
 * page, because a report opened from disk has no origin to allow. Without a
 * secret, any website open in the same browser could drive a developer's local
 * platform into making arbitrary HTTP requests - including to hosts only that
 * machine can reach. The token is generated per process and written into the
 * reports this process produces, so a page that was never given it cannot use
 * the proxy. It is not a user credential and is never persisted; reports
 * written before a restart simply fall back to the direct browser call.
 */
@Service
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    /** Headers the JDK client owns - setting them by hand throws. */
    private static final Set<String> RESTRICTED = Set.of(
            "host", "connection", "content-length", "expect", "upgrade");

    private final ReplayProperties properties;
    private final String token = UUID.randomUUID().toString();
    private final HttpClient client;

    public ReplayService(ReplayProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                // A replayed 302 should be visible as a 302, not silently resolved.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** The secret this process's reports embed. */
    public String token() {
        return token;
    }

    public boolean isAuthorized(String supplied) {
        return token.equals(supplied);
    }

    /** Performs the call and reports what came back - including a failure, which is itself a result worth showing. */
    public ReplayResult send(ReplayRequest request) {
        long started = System.nanoTime();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(request.uri()))
                    .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())));

            String method = request.method() == null ? "GET" : request.method().toUpperCase(Locale.ROOT);
            String body = request.body() == null ? "" : request.body();
            builder.method(method, body.isEmpty()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));

            if (request.headers() != null) {
                request.headers().forEach((name, value) -> {
                    if (name != null && value != null && !RESTRICTED.contains(name.toLowerCase(Locale.ROOT))) {
                        builder.header(name, value);
                    }
                });
            }

            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            log.info("Replayed {} {} from a report -> {}", method, request.uri(), response.statusCode());
            return new ReplayResult(response.statusCode(), flatten(response.headers().map()),
                    truncate(response.body()), millisSince(started), null);
        } catch (Exception e) {
            // Returned, not thrown: "connection refused" answers the reader's
            // question as usefully as a status code would, and throwing would
            // reach the page as an opaque proxy failure instead of as the
            // outcome of the call they asked for.
            log.info("Replay of {} {} failed: {}", request.method(), request.uri(), e.toString());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new ReplayResult(0, Map.of(), null, millisSince(started), e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    private long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private Map<String, String> flatten(Map<String, List<String>> headers) {
        Map<String, String> flat = new LinkedHashMap<>();
        headers.forEach((name, values) -> flat.put(name, String.join(", ", values)));
        return flat;
    }

    private String truncate(String body) {
        if (body == null || body.length() <= properties.getMaxBodyChars()) {
            return body;
        }
        return body.substring(0, properties.getMaxBodyChars()) + "\n... (truncated)";
    }
}
