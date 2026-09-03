package com.company.aiqa.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The token extraction and URL resolution are where a login goes subtly wrong -
 * a token one level deeper than expected, a login path joined to a baseUri with
 * a trailing slash. Pinned here without a network.
 */
class AuthTokenResolverTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final Map<String, String> NO_HEADERS = Map.of();

    @Test
    void extractsATopLevelTokenByCommonFieldNameWithoutAnExplicitPath() {
        String body = "{\"token\":\"abc.def.ghi\",\"expiresIn\":3600}";
        assertEquals("abc.def.ghi", AuthTokenResolver.extractToken(body, NO_HEADERS, null, null, mapper));
    }

    @Test
    void extractsAccessTokenAheadOfAPlainTokenWhenBothPresent() {
        String body = "{\"token\":\"legacy\",\"accessToken\":\"preferred\"}";
        assertEquals("preferred", AuthTokenResolver.extractToken(body, NO_HEADERS, null, null, mapper));
    }

    @Test
    void followsAnExplicitDottedPathIntoAnEnvelope() {
        String body = "{\"success\":true,\"data\":{\"token\":\"deep\"}}";
        assertEquals("deep", AuthTokenResolver.extractToken(body, NO_HEADERS, "data.token", null, mapper));
    }

    @Test
    void findsADeepTokenViaCandidateListWhenNoPathGiven() {
        String body = "{\"data\":{\"accessToken\":\"deep\"}}";
        assertEquals("deep", AuthTokenResolver.extractToken(body, NO_HEADERS, null, null, mapper));
    }

    @Test
    void malformedBodyStillLetsAHeaderTokenThrough() {
        // Not-JSON body must not stop header extraction - the token may be there.
        assertEquals("h.tok", AuthTokenResolver.extractToken("not json",
                Map.of("authorization", "Bearer h.tok"), null, null, mapper));
    }

    /** The TailorBookApp shape: signin returns Authorization: Bearer <jwt> with data.token = null. */
    @Test
    void extractsABearerTokenFromTheAuthorizationHeaderWhenTheBodyTokenIsNull() {
        String body = "{\"status\":\"success\",\"data\":{\"token\":null,\"type\":\"Bearer\"}}";
        Map<String, String> headers = Map.of("authorization", "Bearer eyJhbGciOi.JZ.sig");

        assertEquals("eyJhbGciOi.JZ.sig",
                AuthTokenResolver.extractToken(body, headers, null, null, mapper));
    }

    @Test
    void anExplicitTokenHeaderIsReadAndStripped() {
        Map<String, String> headers = Map.of("x-auth-token", "Bearer raw.jwt");
        assertEquals("raw.jwt",
                AuthTokenResolver.extractToken("{}", headers, null, "X-Auth-Token", mapper));
    }

    @Test
    void aBodyTokenStillWinsOverAHeaderWhenPresent() {
        String body = "{\"accessToken\":\"from-body\"}";
        Map<String, String> headers = Map.of("authorization", "Bearer from-header");
        assertEquals("from-body", AuthTokenResolver.extractToken(body, headers, null, null, mapper));
    }

    @Test
    void noTokenAnywhereYieldsNull() {
        String body = "{\"status\":\"success\",\"data\":{\"token\":null}}";
        assertNull(AuthTokenResolver.extractToken(body, NO_HEADERS, null, null, mapper));
    }

    @Test
    void absoluteExplicitUrlWins() {
        assertEquals("http://host:8083/api/auth/signin",
                AuthTokenResolver.resolveUrl("http://host:8083/api/auth/signin", "/discovered", "http://host:8083"));
    }

    @Test
    void aPathIsJoinedToBaseUriWithoutDoubleSlash() {
        assertEquals("http://host:8083/api/auth/signin",
                AuthTokenResolver.resolveUrl("/api/auth/signin", null, "http://host:8083/"));
    }

    @Test
    void aDiscoveredPathIsUsedWhenNoExplicitUrl() {
        assertEquals("http://host:8083/api/auth/signin",
                AuthTokenResolver.resolveUrl(null, "/api/auth/signin", "http://host:8083"));
    }

    @Test
    void noUrlAnywhereResolvesToNull() {
        assertNull(AuthTokenResolver.resolveUrl(null, null, "http://host:8083"));
    }
}
