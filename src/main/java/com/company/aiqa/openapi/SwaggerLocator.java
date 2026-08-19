package com.company.aiqa.openapi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Works out where a project's OpenAPI documents live, by reading the project
 * rather than guessing.
 *
 * <p>Guessing is the obvious alternative and it does not work. {@code /v3/api-docs}
 * is springdoc's default, but mms overrides it to {@code /api-docs} and serves
 * everything under a {@code /mms} context path, so the default 404s. The two
 * facts that fix it are both written down in {@code application.yml}; this class
 * reads them.
 *
 * <p>A component here is a <b>deployable</b>, not a Maven module: the unit that
 * has an {@code application.yml} and therefore a port and a context path. mms
 * has nine modules and one deployable. A repo with four Spring Boot apps has
 * four, each with its own document, which is why this returns a list.
 */
@Service
public class SwaggerLocator {

    private static final Logger log = LoggerFactory.getLogger(SwaggerLocator.class);

    /** springdoc's default when nothing overrides it. */
    private static final String SPRINGDOC_DEFAULT = "/v3/api-docs";

    /** springfox's, for the Spring Boot 2 era projects still on it. */
    private static final String SPRINGFOX_DEFAULT = "/v2/api-docs";

    /** Deep trees exist; a Spring Boot app's config is never 12 levels down. */
    private static final int MAX_SCAN_DEPTH = 8;

    /** A tree with hundreds of resource folders is a monorepo we should not walk forever. */
    private static final int MAX_COMPONENTS = 12;

    private static final Set<String> IGNORED_DIRS = Set.of(
            "target", "build", "node_modules", ".git", ".idea", "out", "dist", "bin", "test", "generated");

    private static final List<String> SPEC_FILE_NAMES = List.of(
            "openapi.json", "openapi.yaml", "openapi.yml",
            "swagger.json", "swagger.yaml", "swagger.yml",
            "api-docs.json", "api-docs.yaml");

    /**
     * Every document this repo plausibly serves, most likely first.
     *
     * @param repoRoot checked-out source to read the configuration from
     * @param baseUri  the run's target, e.g. {@code http://host:8007/mms}. It
     *                 supplies the host that is actually reachable - the port in
     *                 {@code application.yml} is the one the service binds
     *                 locally, which behind any proxy or container mapping is
     *                 not the one you can call.
     */
    public List<OpenApiSource> locate(Path repoRoot, String baseUri) {
        if (repoRoot == null || !Files.isDirectory(repoRoot)) {
            return List.of();
        }
        List<OpenApiSource> found = new ArrayList<>();
        List<Component> components = findComponents(repoRoot);

        if (components.isEmpty()) {
            log.info("No application.yml/properties under {} - no Spring Boot component to read a docs path from.",
                    repoRoot.getFileName());
        }
        for (Component component : components) {
            for (String url : candidateUrls(component, baseUri, components.size())) {
                found.add(OpenApiSource.url(component.name, url, component.why));
            }
        }
        found.addAll(checkedInSpecs(repoRoot));

        List<OpenApiSource> deduped = dedupe(found);
        if (!deduped.isEmpty()) {
            log.info("OpenAPI candidate(s) for {}: {}", repoRoot.getFileName(),
                    deduped.stream().map(OpenApiSource::describe).toList());
        }
        return deduped;
    }

    /** A deployable and the docs path its own configuration asks for. */
    private record Component(String name, String docsPath, String contextPath, String port, String why) {
    }

    /**
     * One component per module, not per file. A module carries
     * {@code application.yml} plus {@code application-dev.yml} and
     * {@code application-prod.yml}; treating each as its own deployable
     * invented two more mms-apps, each with a docs path the real one overrides.
     * Files are merged shortest-name-first, so the unprofiled defaults win and
     * a profile only fills in what they leave unsaid.
     */
    private List<Component> findComponents(Path repoRoot) {
        Map<Path, Map<String, String>> byModule = new LinkedHashMap<>();
        for (Path config : configFiles(repoRoot)) {
            Map<String, String> settings = readSettings(config);
            Path moduleDir = moduleDirOf(repoRoot, config);
            byModule.computeIfAbsent(moduleDir, m -> new LinkedHashMap<>())
                    .putIfAbsent("__origin", repoRoot.relativize(config).toString().replace('\\', '/'));
            settings.forEach(byModule.get(moduleDir)::putIfAbsent);
        }

        List<Component> components = new ArrayList<>();
        for (Map.Entry<Path, Map<String, String>> entry : byModule.entrySet()) {
            Path moduleDir = entry.getKey();
            Map<String, String> settings = entry.getValue();
            String origin = settings.get("__origin");
            String name = moduleDir.equals(repoRoot)
                    ? String.valueOf(repoRoot.getFileName())
                    : String.valueOf(moduleDir.getFileName());

            String declared = settings.get("springdoc.api-docs.path");
            String docsPath = declared != null ? declared
                    : usesSpringfox(moduleDir, repoRoot) ? SPRINGFOX_DEFAULT : SPRINGDOC_DEFAULT;
            String why = declared != null
                    ? "springdoc.api-docs.path in " + origin
                    : "default docs path for " + origin;

            if ("false".equalsIgnoreCase(settings.get("springdoc.api-docs.enabled"))) {
                log.info("Component '{}' sets springdoc.api-docs.enabled=false - skipping it.", name);
                continue;
            }
            components.add(new Component(name, normalisePath(docsPath),
                    normalisePath(settings.get("server.servlet.context-path")),
                    settings.get("server.port"), why));
            if (components.size() >= MAX_COMPONENTS) {
                break;
            }
        }
        return components;
    }

    /**
     * Candidate URLs for one component, most likely first.
     *
     * <p>With a single component the run's {@code baseUri} already points at it,
     * context path included, so appending the docs path is exactly right and is
     * tried first. With several, the base URI can only be one of them, so each
     * component's own context path is applied to the base URI's host, and its
     * declared port tried as well - the shape a multi-service compose file takes.
     */
    private List<String> candidateUrls(Component component, String baseUri, int componentCount) {
        List<String> urls = new ArrayList<>();
        String base = trimSlash(baseUri);
        if (base.isEmpty()) {
            return urls;
        }
        if (componentCount == 1 || base.endsWith(component.contextPath) && !component.contextPath.isEmpty()) {
            urls.add(base + component.docsPath);
        }
        String origin = origin(base);
        if (!origin.isEmpty()) {
            urls.add(origin + component.contextPath + component.docsPath);
            if (component.port != null && !component.port.isBlank() && component.port.chars().allMatch(Character::isDigit)) {
                urls.add(hostOnly(origin) + ":" + component.port + component.contextPath + component.docsPath);
            }
        }
        return urls;
    }

    /**
     * Specs committed to the repo. Worth having: a service that is not running,
     * or not reachable from here, still ships its contract in the tree often
     * enough to be the only source available.
     */
    private List<OpenApiSource> checkedInSpecs(Path repoRoot) {
        List<OpenApiSource> specs = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(repoRoot, MAX_SCAN_DEPTH)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !isIgnored(repoRoot, p))
                    .filter(p -> SPEC_FILE_NAMES.contains(String.valueOf(p.getFileName()).toLowerCase()))
                    .limit(MAX_COMPONENTS)
                    .forEach(p -> {
                        String relative = repoRoot.relativize(p).toString().replace('\\', '/');
                        specs.add(OpenApiSource.file(componentOf(repoRoot, p), relative, "spec committed to the repo"));
                    });
        } catch (IOException e) {
            log.debug("Could not scan {} for committed specs: {}", repoRoot, e.toString());
        }
        return specs;
    }

    private List<Path> configFiles(Path repoRoot) {
        List<Path> configs = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(repoRoot, MAX_SCAN_DEPTH)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !isIgnored(repoRoot, p))
                    .filter(SwaggerLocator::isSpringBootConfig)
                    // application.yml before application-dev.yml: the profile
                    // files override a deployment we are not running.
                    .sorted((a, b) -> String.valueOf(a.getFileName()).length()
                            - String.valueOf(b.getFileName()).length())
                    .forEach(configs::add);
        } catch (IOException e) {
            log.debug("Could not scan {} for configuration: {}", repoRoot, e.toString());
        }
        return configs;
    }

    private static boolean isSpringBootConfig(Path p) {
        String name = String.valueOf(p.getFileName());
        return (name.startsWith("application") || name.startsWith("bootstrap"))
                && (name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties"));
    }

    private boolean isIgnored(Path root, Path p) {
        for (Path part : root.relativize(p)) {
            if (IGNORED_DIRS.contains(String.valueOf(part).toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** "mms-app/src/main/resources/application.yml" -> the "mms-app" directory. */
    private Path moduleDirOf(Path repoRoot, Path config) {
        Path dir = config.getParent();
        while (dir != null && !dir.equals(repoRoot)) {
            Path name = dir.getFileName();
            if (name != null && "src".equals(String.valueOf(name))) {
                Path parent = dir.getParent();
                return parent == null ? repoRoot : parent;
            }
            dir = dir.getParent();
        }
        return repoRoot;
    }

    private String componentOf(Path repoRoot, Path file) {
        Path relative = repoRoot.relativize(file);
        return relative.getNameCount() > 1
                ? String.valueOf(relative.getName(0))
                : String.valueOf(repoRoot.getFileName());
    }

    /**
     * The handful of settings that decide the URL, from YAML or properties.
     *
     * <p>Deliberately not a YAML parse. Only four dotted keys matter and both
     * formats express them simply; a full parse would pull the whole document -
     * datasource passwords included - into a service that has no use for it.
     */
    private Map<String, String> readSettings(Path config) {
        Map<String, String> settings = new LinkedHashMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(config);
        } catch (IOException e) {
            log.debug("Could not read {}: {}", config, e.toString());
            return settings;
        }
        boolean properties = String.valueOf(config.getFileName()).endsWith(".properties");
        if (properties) {
            for (String line : lines) {
                String trimmed = line.trim();
                int eq = trimmed.indexOf('=');
                if (trimmed.startsWith("#") || eq <= 0) {
                    continue;
                }
                put(settings, trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1));
            }
            return settings;
        }
        readYamlPaths(lines, settings);
        return settings;
    }

    /**
     * Dotted keys out of indented YAML, tracking indentation to build the path.
     * Stops at the first document separator so a multi-document file's profile
     * overrides do not masquerade as the default.
     */
    private void readYamlPaths(List<String> lines, Map<String, String> settings) {
        List<String> stack = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        for (String raw : lines) {
            if (raw.trim().startsWith("---")) {
                return;
            }
            if (raw.isBlank() || raw.trim().startsWith("#")) {
                continue;
            }
            int indent = raw.length() - raw.stripLeading().length();
            String line = raw.trim();
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();

            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                stack.remove(stack.size() - 1);
            }
            stack.add(key);
            indents.add(indent);
            if (!value.isEmpty()) {
                put(settings, String.join(".", stack), value);
                stack.remove(stack.size() - 1);
                indents.remove(indents.size() - 1);
            }
        }
    }

    private void put(Map<String, String> settings, String key, String value) {
        String clean = value.trim();
        if (clean.startsWith("\"") && clean.endsWith("\"") && clean.length() > 1) {
            clean = clean.substring(1, clean.length() - 1);
        }
        if (clean.startsWith("'") && clean.endsWith("'") && clean.length() > 1) {
            clean = clean.substring(1, clean.length() - 1);
        }
        // "${PORT:8080}" - take the default, which is what a local run uses.
        if (clean.startsWith("${") && clean.endsWith("}")) {
            String inner = clean.substring(2, clean.length() - 1);
            int colon = inner.indexOf(':');
            clean = colon >= 0 ? inner.substring(colon + 1) : "";
        }
        settings.putIfAbsent(key, clean);
    }

    /** True when this module's build file pulls springfox rather than springdoc. */
    private boolean usesSpringfox(Path moduleDir, Path repoRoot) {
        for (Path build : List.of(moduleDir.resolve("pom.xml"), moduleDir.resolve("build.gradle"),
                moduleDir.resolve("build.gradle.kts"), repoRoot.resolve("pom.xml"))) {
            try {
                if (Files.isRegularFile(build) && Files.readString(build).contains("springfox")) {
                    return true;
                }
            } catch (IOException ignored) {
                // An unreadable build file just means we keep the default.
            }
        }
        return false;
    }

    private String normalisePath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "";
        }
        String p = path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        return trimSlash(p);
    }

    private String trimSlash(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    /** "http://host:8007/mms" -> "http://host:8007". */
    private String origin(String uri) {
        int schemeEnd = uri.indexOf("://");
        if (schemeEnd < 0) {
            return "";
        }
        int pathStart = uri.indexOf('/', schemeEnd + 3);
        return pathStart < 0 ? uri : uri.substring(0, pathStart);
    }

    /** "http://host:8007" -> "http://host", so another port can be appended. */
    private String hostOnly(String origin) {
        int schemeEnd = origin.indexOf("://");
        int colon = origin.indexOf(':', schemeEnd + 3);
        return colon < 0 ? origin : origin.substring(0, colon);
    }

    private List<OpenApiSource> dedupe(List<OpenApiSource> sources) {
        Set<String> seen = new LinkedHashSet<>();
        List<OpenApiSource> unique = new ArrayList<>();
        for (OpenApiSource source : sources) {
            if (seen.add(source.isUrl() ? source.url() : source.filePath())) {
                unique.add(source);
            }
        }
        return unique;
    }
}
