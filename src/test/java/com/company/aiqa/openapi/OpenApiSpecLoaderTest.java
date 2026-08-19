package com.company.aiqa.openapi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loader's contract is that it never throws. A spec is an enhancement; an
 * unreachable one has to fall back to source-derived DTOs, not fail the
 * generation that was going to work without it.
 */
class OpenApiSpecLoaderTest {

    private final OpenApiSpecLoader loader = new OpenApiSpecLoader(properties());

    private static OpenApiProperties properties() {
        OpenApiProperties p = new OpenApiProperties();
        p.setTimeoutMillis(500);
        return p;
    }

    @Test
    void readsAYamlSpecFromTheRepo(@TempDir Path repo) throws IOException {
        Files.writeString(repo.resolve("openapi.yaml"), """
                openapi: 3.0.1
                paths:
                  /orders:
                    get:
                      responses:
                        '200':
                          description: OK
                """);

        var spec = loader.load(OpenApiSource.file("svc", "openapi.yaml", "test"), repo);

        assertTrue(spec.isPresent());
        assertEquals("3.0.1", spec.get().path("openapi").asText());
    }

    @Test
    void readsAJsonSpecFromTheRepo(@TempDir Path repo) throws IOException {
        Files.writeString(repo.resolve("openapi.json"),
                "{\"openapi\":\"3.1.0\",\"paths\":{\"/orders\":{}}}");

        assertTrue(loader.load(OpenApiSource.file("svc", "openapi.json", "test"), repo).isPresent());
    }

    /**
     * A swagger-ui page answers 200 with a body. Accepting it would hand the
     * extractor HTML and produce an empty contract that looks like a real one.
     */
    @Test
    void rejectsABodyThatIsNotAnOpenApiDocument(@TempDir Path repo) throws IOException {
        Files.writeString(repo.resolve("openapi.json"), "<html><body>Swagger UI</body></html>");

        assertTrue(loader.load(OpenApiSource.file("svc", "openapi.json", "test"), repo).isEmpty());
    }

    /** Valid JSON, but no paths - a config endpoint, not a spec. */
    @Test
    void rejectsJsonWithoutPaths(@TempDir Path repo) throws IOException {
        Files.writeString(repo.resolve("openapi.json"), "{\"status\":\"UP\"}");

        assertTrue(loader.load(OpenApiSource.file("svc", "openapi.json", "test"), repo).isEmpty());
    }

    @Test
    void returnsEmptyForAMissingFile(@TempDir Path repo) {
        assertTrue(loader.load(OpenApiSource.file("svc", "nope.json", "test"), repo).isEmpty());
    }

    /** Nothing is listening on port 1; this must come back empty, not throw. */
    @Test
    void returnsEmptyWhenTheHostRefusesTheConnection() {
        assertTrue(loader.load(
                OpenApiSource.url("svc", "http://127.0.0.1:1/v3/api-docs", "test"), null).isEmpty());
    }

    @Test
    void returnsEmptyForAMalformedUrl() {
        assertTrue(loader.load(OpenApiSource.url("svc", "not a url", "test"), null).isEmpty());
    }
}
