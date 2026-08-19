package com.company.aiqa.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Fetches an OpenAPI document and parses it, in whichever of the two formats it
 * arrives in.
 *
 * <p>Never throws. A spec is an enhancement: when it cannot be reached the
 * generator falls back to reading DTOs out of the source, which is what it did
 * before this existed. Turning an unreachable service into a failed generation
 * would trade a better prompt for no prompt at all.
 */
@Service
public class OpenApiSpecLoader {

    private static final Logger log = LoggerFactory.getLogger(OpenApiSpecLoader.class);

    /** A spec is a few hundred KB at most; anything larger is not one. */
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private final ObjectMapper json = new ObjectMapper();
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private final HttpClient http;
    private final Duration timeout;

    public OpenApiSpecLoader(OpenApiProperties properties) {
        this.timeout = Duration.ofMillis(properties.getTimeoutMillis());
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                // A docs path very often redirects - /v3/api-docs to
                // /v3/api-docs/, or http to https behind a proxy.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** The parsed document, or empty with the reason logged. */
    public Optional<JsonNode> load(OpenApiSource source, Path repoRoot) {
        try {
            String body = source.isUrl() ? fetch(source.url()) : readFile(source, repoRoot);
            if (body == null || body.isBlank()) {
                return Optional.empty();
            }
            JsonNode root = parse(body);
            if (root == null || !looksLikeOpenApi(root)) {
                log.info("{} answered, but the body is not an OpenAPI document - ignoring it.", source.describe());
                return Optional.empty();
            }
            return Optional.of(root);
        } catch (Exception e) {
            // Interruption is not a "spec unavailable" - restore the flag and
            // let the caller's own cancellation handling see it.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.info("Could not load {}: {}", source.describe(), e.toString());
            return Optional.empty();
        }
    }

    private String fetch(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json, application/yaml, text/yaml, */*")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.info("{} returned {} - not a served OpenAPI document.", url, response.statusCode());
            return null;
        }
        String body = response.body();
        if (body != null && body.length() > MAX_BYTES) {
            log.info("{} returned {} bytes, past the {} byte ceiling - ignoring it.",
                    url, body.length(), MAX_BYTES);
            return null;
        }
        return body;
    }

    private String readFile(OpenApiSource source, Path repoRoot) throws IOException {
        if (repoRoot == null) {
            return null;
        }
        Path file = repoRoot.resolve(source.filePath());
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) {
            return null;
        }
        return Files.readString(file);
    }

    /**
     * Content type is unreliable - springdoc serves JSON as
     * {@code application/json} but a file share serves YAML as
     * {@code text/plain} - so the body decides. JSON is also valid YAML, but
     * not the reverse, so JSON is tried first and is exact.
     */
    private JsonNode parse(String body) {
        String trimmed = body.stripLeading();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return json.readTree(body);
            } catch (IOException e) {
                log.debug("Body starts like JSON but does not parse as JSON: {}", e.toString());
            }
        }
        try {
            return yaml.readTree(body);
        } catch (IOException e) {
            log.debug("Body does not parse as YAML either: {}", e.toString());
            return null;
        }
    }

    /**
     * A swagger-ui HTML page and a login redirect both return 200 with a body.
     * Only a document with paths is worth anything downstream.
     */
    private boolean looksLikeOpenApi(JsonNode root) {
        return root.isObject()
                && (root.has("openapi") || root.has("swagger"))
                && root.path("paths").isObject();
    }
}
