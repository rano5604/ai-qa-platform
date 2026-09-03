package com.company.aiqa.auth;

import com.company.aiqa.model.AuthLoginSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Logs in to the target once at the start of a run and turns the response into
 * the auth header every request then carries.
 *
 * <p>This is the run-time half of the auth story (the source half is
 * {@link LoginEndpointScanner}). A real service hands out short-lived tokens
 * from a login endpoint rather than accepting a static one, so a run supplies
 * credentials and the platform does what a client would: POST them, read the
 * token out of the body, and hand it to {@code AuthInjectingFilter} as a header.
 *
 * <p>Everything the caller can leave out is filled from the target's own source
 * or a convention - the login URL and the token's JSON field from
 * {@link LoginEndpointScanner}, the method (POST), header
 * ({@code Authorization: Bearer <token>}) and content type from defaults - so
 * the minimum a caller provides is the credentials themselves.
 *
 * <p><b>Never fails the run.</b> A login that can't be located, is refused, or
 * returns no recognisable token produces an empty header map and a logged
 * reason; the suite then runs unauthenticated and the auth-wall diagnosis
 * explains the 401s, which is strictly better than aborting before a single
 * test executes.
 */
@Service
public class AuthTokenResolver {

    private static final Logger log = LoggerFactory.getLogger(AuthTokenResolver.class);

    /** Body fields tried in order when no explicit tokenPath is given and source discovery found none. */
    private static final List<String> TOKEN_CANDIDATES = List.of(
            "accessToken", "access_token", "token", "idToken", "id_token", "jwt", "authToken",
            "data.token", "data.accessToken", "data.access_token", "data.jwt");

    /**
     * Response headers tried (lowercased) after the body yields nothing - a
     * header-borne token is how a Spring JWT filter commonly answers signin, with
     * {@code Authorization: Bearer <token>} and a null token in the JSON body.
     */
    private static final List<String> HEADER_CANDIDATES = List.of(
            "authorization", "x-auth-token", "x-access-token", "access-token", "x-token", "token");

    private static final String DEFAULT_HEADER_NAME = "Authorization";
    private static final String DEFAULT_HEADER_TEMPLATE = "Bearer {token}";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * @param spec           how to log in - credentials required, the rest optional
     * @param baseUri        the run's target, used to resolve a login path to a full URL
     * @param targetRepoRoot the target's local checkout for source discovery, or null to skip it
     * @return the header(s) to inject on every request, or an empty map when login didn't yield a token
     */
    public Map<String, String> resolve(AuthLoginSpec spec, String baseUri, Path targetRepoRoot) {
        if (spec == null) {
            return Map.of();
        }

        LoginEndpointScanner.LoginDiscovery discovery =
                (spec.getUrl() == null || spec.getUrl().isBlank()
                        || spec.getTokenPath() == null || spec.getTokenPath().isBlank())
                        ? LoginEndpointScanner.discover(targetRepoRoot)
                        : new LoginEndpointScanner.LoginDiscovery(null, null, null);

        String url = resolveUrl(spec.getUrl(), discovery.path(), baseUri);
        if (url == null) {
            log.warn("Auth login requested but no login URL was given and none could be discovered from source; "
                    + "running unauthenticated. Supply login.url (e.g. {baseUri}/api/auth/signin).");
            return Map.of();
        }

        LoginResponse response = performLogin(spec, url);
        if (response == null) {
            return Map.of();
        }

        String tokenPath = firstNonBlank(spec.getTokenPath(), discovery.tokenPath());
        String token = extractToken(response.body(), response.headers(), tokenPath, spec.getTokenHeader(), mapper);
        if (token == null || token.isBlank()) {
            log.warn("Logged in at {} but found no token anywhere in the response - not in the body{}, nor in the "
                    + "auth headers {}; running unauthenticated. If the token is elsewhere, set login.tokenPath "
                    + "or login.tokenHeader.",
                    url, tokenPath == null ? " (tried " + TOKEN_CANDIDATES + ")" : " at '" + tokenPath + "'",
                    HEADER_CANDIDATES);
            return Map.of();
        }

        String headerName = firstNonBlank(spec.getHeaderName(), DEFAULT_HEADER_NAME);
        String template = firstNonBlank(spec.getHeaderTemplate(), DEFAULT_HEADER_TEMPLATE);
        String headerValue = template.replace("{token}", token);
        log.info("Auth login at {} succeeded; injecting header '{}' on every request.", url, headerName);
        return Map.of(headerName, headerValue);
    }

    /** The parts of a login response the token can hide in - body and headers both. */
    private record LoginResponse(String body, Map<String, String> headers) {
    }

    private LoginResponse performLogin(AuthLoginSpec spec, String url) {
        try {
            String method = firstNonBlank(spec.getMethod(), "POST").toUpperCase();
            String contentType = firstNonBlank(spec.getContentType(), "application/json");
            String body = spec.getBody() == null ? "" : mapper.writeValueAsString(spec.getBody());

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", contentType)
                    .header("Accept", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("Auth login POST {} returned {} - running unauthenticated. Body: {}",
                        url, response.statusCode(), truncate(response.body()));
                return null;
            }
            // Flatten headers to first-value-per-name, lowercased: a token in the
            // Authorization or X-Auth-Token response header is how a Spring JWT
            // filter usually answers signin, and reading only the body misses it.
            Map<String, String> headers = new java.util.LinkedHashMap<>();
            response.headers().map().forEach((name, values) -> {
                if (!values.isEmpty()) {
                    headers.put(name.toLowerCase(java.util.Locale.ROOT), values.get(0));
                }
            });
            return new LoginResponse(response.body(), headers);
        } catch (Exception e) {
            log.warn("Auth login to {} failed ({}); running unauthenticated.", url, e.toString());
            return null;
        }
    }

    /**
     * Reads the token out of the WHOLE login response - body and headers - because
     * a service may return it in either, and some (a Spring JWT filter) put it in
     * an {@code Authorization: Bearer <token>} response header with a null token
     * in the JSON. Resolution order:
     * <ol>
     *   <li>an explicit {@code tokenHeader}, if set - read exactly that header;
     *   <li>an explicit {@code tokenPath}, if set - read exactly that body path
     *       (a wrong guess fails loudly here rather than sending a null token);
     *   <li>otherwise auto: the common body fields first, then the common auth
     *       headers.
     * </ol>
     * A {@code "Bearer "} scheme prefix on a header value is stripped, since
     * {@code headerTemplate} re-wraps the bare token. Static and parameterised so
     * it is unit-testable without a network.
     */
    static String extractToken(String responseBody, Map<String, String> responseHeaders, String tokenPath,
                               String tokenHeader, ObjectMapper mapper) {
        Map<String, String> headers = responseHeaders == null ? Map.of() : responseHeaders;

        if (tokenHeader != null && !tokenHeader.isBlank()) {
            return stripScheme(headers.get(tokenHeader.toLowerCase(Locale.ROOT)));
        }

        JsonNode root = null;
        try {
            root = mapper.readTree(responseBody);
        } catch (Exception ignored) {
            // A non-JSON body just means the token isn't in the body - headers may
            // still carry it, so don't bail out here.
        }

        if (tokenPath != null && !tokenPath.isBlank()) {
            String fromPath = root == null ? null : textAtDottedPath(root, tokenPath);
            // An explicit body path that came back empty still lets a header win,
            // which is the exact null-token-in-body / token-in-header case.
            if (fromPath != null && !fromPath.isBlank()) {
                return fromPath;
            }
        } else if (root != null) {
            for (String candidate : TOKEN_CANDIDATES) {
                String value = textAtDottedPath(root, candidate);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }

        for (String candidate : HEADER_CANDIDATES) {
            String value = stripScheme(headers.get(candidate));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** Removes a leading {@code Bearer } (or other scheme) so headerTemplate re-wraps a bare token. */
    private static String stripScheme(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        String v = headerValue.trim();
        int space = v.indexOf(' ');
        // "Bearer eyJ..." -> "eyJ...". A value with no space is already bare.
        if (space > 0 && space < 12) {
            return v.substring(space + 1).trim();
        }
        return v;
    }

    /** Walks a dotted path ({@code data.token}) and returns the leaf as text, or null. */
    private static String textAtDottedPath(JsonNode root, String dottedPath) {
        JsonNode node = root;
        for (String segment : dottedPath.split("\\.")) {
            if (node == null || !node.isObject()) {
                return null;
            }
            node = node.get(segment);
        }
        // A JSON null (NullNode) is a value node but not a token - and its
        // asText() is the 4-char string "null", which must never be returned.
        // This is exactly the TailorBookApp shape: data.token is null in the body
        // and the real token is in the Authorization header.
        return node != null && node.isValueNode() && !node.isNull() ? node.asText() : null;
    }

    /**
     * Resolves the login URL: an explicit absolute URL wins; an explicit or
     * discovered path is joined to the run's baseUri; nothing usable yields null.
     */
    static String resolveUrl(String explicitUrl, String discoveredPath, String baseUri) {
        if (explicitUrl != null && !explicitUrl.isBlank()) {
            String u = explicitUrl.trim();
            if (u.startsWith("http://") || u.startsWith("https://")) {
                return u;
            }
            return joinBaseAndPath(baseUri, u);
        }
        if (discoveredPath != null && !discoveredPath.isBlank()) {
            return joinBaseAndPath(baseUri, discoveredPath);
        }
        return null;
    }

    private static String joinBaseAndPath(String baseUri, String path) {
        if (baseUri == null || baseUri.isBlank()) {
            return null;
        }
        String base = baseUri.trim().replaceAll("/+$", "");
        String p = path.startsWith("/") ? path : "/" + path;
        return base + p;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
