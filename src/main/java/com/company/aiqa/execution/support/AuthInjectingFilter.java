package com.company.aiqa.execution.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adds run-supplied authentication headers to every generated request, from
 * outside the generated code.
 *
 * <p><b>Why this exists.</b> Most real targets sit behind authentication, and
 * the generator is deliberately forbidden from inventing auth it cannot see in
 * the diff (the grounding rule that stops "Unauthorized access to calculator").
 * The result, against an auth-gated service, is a whole run of 401s: the
 * TailorBookApp rollup returned {@code {"error":"Unauthorized","message":
 * "Authentication required or token invalid"}} to 401 of 644 failing tests, and
 * every precondition create 401'd too, so the tests that depended on those ids
 * then died with nothing on the wire. None of that is a defect in the target or
 * in the tests - it is one missing token, repeated hundreds of times.
 *
 * <p>Auth is therefore supplied at execution time, exactly like the base URI:
 * the caller hands {@code execute-automation} a header map, the runner passes it
 * to the subprocess as a system property, and this filter puts it on the wire.
 * Installed globally by {@link HttpCaptureListener} rather than written into
 * each script, for the same reason capture is - a per-script instruction is only
 * as reliable as the model's memory of it, and one script that forgot would run
 * unauthenticated and fail for a reason that looks like a defect.
 *
 * <p><b>Only fills a gap, never overrides.</b> A header already present on the
 * request is left exactly as the test set it. That preserves the point of a
 * negative auth case - a test that deliberately sends a malformed or absent
 * {@code Authorization} to prove the service rejects it keeps doing so; this
 * only supplies auth to the requests that carried none.
 *
 * <p>Runs in the execution subprocess, with no Spring context - same contract as
 * {@link HttpCaptureListener}.
 */
public final class AuthInjectingFilter implements Filter {

    /**
     * Base64 of a JSON object of header-name to header-value, e.g. the encoding
     * of {@code {"Authorization":"Bearer eyJ..."}}. Base64 rather than the raw
     * JSON because this crosses a process boundary as a command-line {@code -D}
     * argument, and JSON's quotes and spaces are mangled by Windows argument
     * quoting; base64's alphabet has neither. Absent or blank means no injection,
     * and this filter is then a no-op that need not even be installed.
     */
    public static final String AUTH_HEADERS_PROPERTY = "aiqa.auth.headers";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, String> headers;

    public AuthInjectingFilter(Map<String, String> headers) {
        this.headers = headers == null ? Map.of() : headers;
    }

    /**
     * Reads and parses {@link #AUTH_HEADERS_PROPERTY} from the current process.
     * Returns an empty map - never throws - when the property is unset, blank, or
     * not the JSON object shape expected, so a malformed value degrades to "no
     * auth injected" rather than failing the whole run.
     */
    public static Map<String, String> configuredHeaders() {
        String raw = System.getProperty(AUTH_HEADERS_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            String json = decodeToJson(raw);
            Map<String, String> parsed = new LinkedHashMap<>();
            MAPPER.readTree(json).fields().forEachRemaining(e -> {
                if (e.getValue() != null && !e.getValue().isNull()) {
                    parsed.put(e.getKey(), e.getValue().asText());
                }
            });
            return parsed;
        } catch (Exception e) {
            System.err.println("[aiqa] Ignoring malformed " + AUTH_HEADERS_PROPERTY + ": " + e.getMessage());
            return Map.of();
        }
    }

    /**
     * The runner base64-encodes the JSON to survive command-line argument
     * quoting. Decode that; but tolerate a raw-JSON value too (a value that
     * already starts with '{'), so a hand-set property or an older caller still
     * works.
     */
    private static String decodeToJson(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) {
            return trimmed;
        }
        return new String(java.util.Base64.getDecoder().decode(trimmed), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public Response filter(FilterableRequestSpecification requestSpec,
                           FilterableResponseSpecification responseSpec,
                           FilterContext ctx) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
            // Only fill a gap - a header the test set itself (a deliberate bad
            // token, say) must survive untouched.
            if (!requestSpec.getHeaders().hasHeaderWithName(header.getKey())) {
                requestSpec.header(header.getKey(), header.getValue());
            }
        }
        return ctx.next(requestSpec, responseSpec);
    }
}
