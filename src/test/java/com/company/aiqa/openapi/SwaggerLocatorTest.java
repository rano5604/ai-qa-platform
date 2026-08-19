package com.company.aiqa.openapi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fixtures are mms's real layout: nine Maven modules, one of them carrying
 * the application.yml, a context path of /mms and a docs path overridden to
 * /api-docs. Guessing springdoc's /v3/api-docs default there produces a 404.
 */
class SwaggerLocatorTest {

    private final SwaggerLocator locator = new SwaggerLocator();

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static List<String> urls(List<OpenApiSource> sources) {
        return sources.stream().filter(OpenApiSource::isUrl).map(OpenApiSource::url).toList();
    }

    @Test
    void readsTheOverriddenDocsPathAndContextPath(@TempDir Path repo) throws IOException {
        write(repo.resolve("mms-app/src/main/resources/application.yml"), """
                server:
                  port: 8080
                  servlet:
                    context-path: /mms
                springdoc:
                  api-docs:
                    path: /api-docs
                """);

        List<String> urls = urls(locator.locate(repo, "http://169.58.37.242:8007/mms"));

        assertEquals("http://169.58.37.242:8007/mms/api-docs", urls.get(0), urls.toString());
    }

    /** Nothing declared: springdoc's own default, on the base URI the run uses. */
    @Test
    void fallsBackToTheSpringdocDefault(@TempDir Path repo) throws IOException {
        write(repo.resolve("src/main/resources/application.yml"), "server:\n  port: 8080\n");

        List<String> urls = urls(locator.locate(repo, "http://localhost:9000"));

        assertEquals("http://localhost:9000/v3/api-docs", urls.get(0), urls.toString());
    }

    /**
     * application.yml plus application-dev.yml plus application-prod.yml is one
     * deployable, not three. Treating each file as its own component invented
     * two extra mms-apps, each carrying the default docs path that the real one
     * overrides.
     */
    @Test
    void profileFilesDoNotBecomeSeparateComponents(@TempDir Path repo) throws IOException {
        write(repo.resolve("mms-app/src/main/resources/application.yml"), """
                server:
                  servlet:
                    context-path: /mms
                springdoc:
                  api-docs:
                    path: /api-docs
                """);
        write(repo.resolve("mms-app/src/main/resources/application-dev.yml"), "server:\n  port: 9999\n");
        write(repo.resolve("mms-app/src/main/resources/application-prod.yml"), "server:\n  port: 7777\n");

        List<OpenApiSource> sources = locator.locate(repo, "http://host:8007/mms");

        assertEquals(1, sources.stream().map(OpenApiSource::component).distinct().count(), sources.toString());
        assertTrue(urls(sources).stream().allMatch(u -> u.endsWith("/mms/api-docs")), urls(sources).toString());
    }

    /** Several deployables in one tree: each gets its own document. */
    @Test
    void findsOneComponentPerDeployable(@TempDir Path repo) throws IOException {
        write(repo.resolve("orders/src/main/resources/application.yml"), """
                server:
                  port: 8081
                  servlet:
                    context-path: /orders
                """);
        write(repo.resolve("billing/src/main/resources/application.yml"), """
                server:
                  port: 8082
                  servlet:
                    context-path: /billing
                """);

        List<OpenApiSource> sources = locator.locate(repo, "http://gateway:80");

        assertEquals(2, sources.stream().map(OpenApiSource::component).distinct().count(), sources.toString());
        assertTrue(urls(sources).contains("http://gateway:80/orders/v3/api-docs"), urls(sources).toString());
        assertTrue(urls(sources).contains("http://gateway:80/billing/v3/api-docs"), urls(sources).toString());
    }

    @Test
    void readsPropertiesFilesToo(@TempDir Path repo) throws IOException {
        write(repo.resolve("src/main/resources/application.properties"), """
                server.servlet.context-path=/svc
                springdoc.api-docs.path=/docs/openapi
                """);

        List<String> urls = urls(locator.locate(repo, "http://host:8080/svc"));

        assertEquals("http://host:8080/svc/docs/openapi", urls.get(0), urls.toString());
    }

    /** A service that turns the document off has none to read. */
    @Test
    void skipsAComponentThatDisablesApiDocs(@TempDir Path repo) throws IOException {
        write(repo.resolve("src/main/resources/application.yml"), """
                springdoc:
                  api-docs:
                    enabled: false
                """);

        assertTrue(urls(locator.locate(repo, "http://host:8080")).isEmpty());
    }

    @Test
    void findsASpecCommittedToTheRepo(@TempDir Path repo) throws IOException {
        write(repo.resolve("docs/openapi.yaml"), "openapi: 3.0.1\npaths: {}\n");

        List<OpenApiSource> sources = locator.locate(repo, "http://host:8080");

        assertTrue(sources.stream().anyMatch(s -> "docs/openapi.yaml".equals(s.filePath())), sources.toString());
    }

    /** target/ holds a copy of every resource; scanning it doubles everything. */
    @Test
    void ignoresBuildOutput(@TempDir Path repo) throws IOException {
        write(repo.resolve("src/main/resources/application.yml"), "server:\n  port: 8080\n");
        write(repo.resolve("target/classes/application.yml"), "server:\n  port: 9090\n");

        List<OpenApiSource> sources = locator.locate(repo, "http://host:8080");

        assertFalse(sources.stream().anyMatch(s -> s.why().contains("target")), sources.toString());
    }

    /** "${PORT:8080}" is a placeholder; the default is the part that is real. */
    @Test
    void takesTheDefaultOutOfAPlaceholder(@TempDir Path repo) throws IOException {
        write(repo.resolve("src/main/resources/application.yml"), """
                server:
                  port: ${SERVER_PORT:8090}
                  servlet:
                    context-path: ${CONTEXT:/api}
                """);

        List<String> urls = urls(locator.locate(repo, "http://host:8090/api"));

        assertTrue(urls.stream().anyMatch(u -> u.equals("http://host:8090/api/v3/api-docs")), urls.toString());
    }
}
