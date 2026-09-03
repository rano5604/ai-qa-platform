package com.company.aiqa.execution.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The property carries the run's auth across the process boundary into the
 * execution subprocess; a malformed value must degrade to "no auth", never take
 * the run down.
 */
class AuthInjectingFilterTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty(AuthInjectingFilter.AUTH_HEADERS_PROPERTY);
    }

    @Test
    void parsesBase64EncodedHeadersTheRunnerSends() {
        String json = "{\"Authorization\":\"Bearer abc\",\"X-Tenant\":\"acme\"}";
        String encoded = java.util.Base64.getEncoder()
                .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.setProperty(AuthInjectingFilter.AUTH_HEADERS_PROPERTY, encoded);

        Map<String, String> headers = AuthInjectingFilter.configuredHeaders();

        assertEquals("Bearer abc", headers.get("Authorization"));
        assertEquals("acme", headers.get("X-Tenant"));
    }

    /** A raw-JSON value (hand-set, or an older caller) is still accepted. */
    @Test
    void toleratesRawJsonHeaders() {
        System.setProperty(AuthInjectingFilter.AUTH_HEADERS_PROPERTY,
                "{\"Authorization\":\"Bearer abc\",\"X-Tenant\":\"acme\"}");

        Map<String, String> headers = AuthInjectingFilter.configuredHeaders();

        assertEquals("Bearer abc", headers.get("Authorization"));
        assertEquals("acme", headers.get("X-Tenant"));
    }

    @Test
    void unsetPropertyYieldsNoHeaders() {
        assertTrue(AuthInjectingFilter.configuredHeaders().isEmpty());
    }

    @Test
    void malformedJsonYieldsNoHeadersRatherThanThrowing() {
        System.setProperty(AuthInjectingFilter.AUTH_HEADERS_PROPERTY, "not json {");

        assertTrue(AuthInjectingFilter.configuredHeaders().isEmpty());
    }
}
