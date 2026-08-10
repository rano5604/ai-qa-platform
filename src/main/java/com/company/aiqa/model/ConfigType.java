package com.company.aiqa.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Classifies a changed file as a CONFIGURATION file (as opposed to a source
 * file handled by {@link SourceLanguage}).
 *
 * The pipeline's git diff step normally keeps only files whose extension maps
 * to a supported programming language, so a merge that only touches
 * application.yml, a .properties file, a Dockerfile, or pom.xml used to slip
 * through with "no changed source files found" and produce zero test cases -
 * even though a config change (a flipped feature flag, a changed timeout, a
 * new required environment variable, a bumped dependency version) is exactly
 * the kind of change QA most wants a regression checklist for.
 *
 * This enum is the "detect the config type" half of that: it recognizes the
 * common config file shapes by extension or well-known filename and gives each
 * a short, human-readable "what to check" hint that the config test-case prompt
 * uses to steer the LLM toward the risks specific to that format.
 *
 * Matching is filename/extension based on purpose, mirroring SourceLanguage -
 * it never opens the file. A path is treated as config only if it is NOT
 * already a supported source language, so e.g. a build script written in a
 * real language is left to the source path (see isConfigFile).
 */
public enum ConfigType {

    /** Spring/app YAML: application.yml, application-*.yml, any *.yml / *.yaml. */
    YAML("Application/service YAML configuration",
            "Check that the app still starts and binds every property; verify changed "
                    + "values, profile overrides (application-<profile>.yml), and that no "
                    + "required key was removed or renamed without a matching code change."),

    /** Java-style .properties files (Spring, log4j, i18n bundles, etc.). */
    PROPERTIES("Key/value .properties configuration",
            "Verify each changed key resolves, placeholder/${} references still resolve, "
                    + "and that defaults behave as before when a key is absent."),

    /** Environment files (.env, .env.*) and dotenv-style secrets templates. */
    ENV("Environment variable file",
            "Verify every newly required variable is documented and present in each "
                    + "environment; confirm the app fails clearly (not silently) when one is "
                    + "missing, and that no real secret was committed."),

    /** JSON config (package.json, tsconfig.json, appsettings.json, generic *.json). */
    JSON("JSON configuration",
            "Validate the JSON parses, required fields are present and well-typed, and any "
                    + "changed value (version, endpoint, feature toggle) takes effect as intended."),

    /** XML config (Spring XML, web.xml, logback.xml, generic *.xml). */
    XML("XML configuration",
            "Verify the document is well-formed and schema-valid, referenced classes/beans/"
                    + "resources still exist, and changed elements/attributes have the intended effect."),

    /** TOML config (pyproject.toml, Cargo.toml, generic *.toml). */
    TOML("TOML configuration",
            "Verify the file parses, tables/keys resolve, and changed values (versions, "
                    + "dependencies, settings) behave as intended."),

    /** INI/CFG/CONF style config. */
    INI("INI/CFG configuration",
            "Verify each section/key is read as expected and changed values take effect."),

    /** Maven build/dependency config (pom.xml). */
    MAVEN_POM("Maven build/dependency config (pom.xml)",
            "Verify the project still resolves and builds; for a dependency/version bump check "
                    + "for breaking API/behavior changes, transitive conflicts, and that plugin/build "
                    + "changes don't alter packaging or test execution."),

    /** Gradle build config (build.gradle, settings.gradle, *.gradle, *.gradle.kts). */
    GRADLE("Gradle build/dependency config",
            "Verify the build still resolves and runs; for a dependency/version bump check for "
                    + "breaking changes and transitive conflicts, and that task/plugin changes don't "
                    + "alter packaging or test execution."),

    /** Container build/compose config (Dockerfile, docker-compose.yml is caught by YAML). */
    DOCKER("Container build configuration (Dockerfile)",
            "Verify the image still builds; check base-image/version bumps, changed EXPOSE/ENV/"
                    + "ENTRYPOINT/CMD, and that runtime behavior (ports, users, paths) is unchanged "
                    + "unless intended."),

    /** Kubernetes/Helm/CI YAML is generally caught by YAML; this covers explicit .tf infra. */
    TERRAFORM("Infrastructure-as-code (Terraform)",
            "Verify the plan is valid and the diff changes only what's intended; watch for changes "
                    + "that force resource replacement, alter networking/security groups, or change scaling."),

    /** SQL migration/schema scripts. */
    SQL("SQL schema/migration script",
            "Verify the migration applies cleanly forward (and rolls back if applicable), is "
                    + "backward-compatible with the running app during deploy, and doesn't lock or "
                    + "drop data unexpectedly."),

    /** Anything config-like we recognize as non-source but don't have a specific bucket for. */
    OTHER("Configuration file",
            "Review the changed values for correctness and verify the application behaves as "
                    + "intended with the new configuration.");

    private final String label;
    private final String checklistHint;

    ConfigType(String label, String checklistHint) {
        this.label = label;
        this.checklistHint = checklistHint;
    }

    /** Short human-readable description of this config kind (used in prompts/CSV). */
    public String label() {
        return label;
    }

    /** Format-specific "what a QA tester should verify" guidance fed to the LLM. */
    public String checklistHint() {
        return checklistHint;
    }

    /**
     * True when this path is a configuration file this platform recognizes AND
     * it is not already a supported source language. The source-language check
     * comes first deliberately: a file that is a real programming language
     * (e.g. a Kotlin Gradle script *.gradle.kts, which is also valid Kotlin)
     * should be handled by the normal source path, not double-counted here.
     */
    public static boolean isConfigFile(String path) {
        if (path == null) {
            return false;
        }
        if (SourceLanguage.isSupported(path)) {
            return false;
        }
        return classifyRaw(path).isPresent();
    }

    /**
     * Classifies a config path, or empty if it is not a recognized config file
     * (or is a source file that should go through the SourceLanguage path).
     */
    public static Optional<ConfigType> fromPath(String path) {
        if (path == null || SourceLanguage.isSupported(path)) {
            return Optional.empty();
        }
        return classifyRaw(path);
    }

    /** Extension/filename matching, independent of the source-language guard. */
    private static Optional<ConfigType> classifyRaw(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        String name = fileName(lower);

        // Well-known filenames first (some have no telling extension).
        if (name.equals("pom.xml")) return Optional.of(MAVEN_POM);
        if (name.equals("dockerfile") || name.startsWith("dockerfile.") || name.endsWith(".dockerfile")) {
            return Optional.of(DOCKER);
        }
        if (name.equals(".env") || name.startsWith(".env.") || name.endsWith(".env")) {
            return Optional.of(ENV);
        }
        if (name.endsWith(".gradle") || name.endsWith(".gradle.kts")) return Optional.of(GRADLE);

        // Then by extension.
        if (endsWithAny(lower, ".yml", ".yaml")) return Optional.of(YAML);
        if (lower.endsWith(".properties")) return Optional.of(PROPERTIES);
        if (lower.endsWith(".json")) return Optional.of(JSON);
        if (lower.endsWith(".xml")) return Optional.of(XML);
        if (lower.endsWith(".toml")) return Optional.of(TOML);
        if (endsWithAny(lower, ".ini", ".cfg", ".conf")) return Optional.of(INI);
        if (lower.endsWith(".tf") || lower.endsWith(".tfvars")) return Optional.of(TERRAFORM);
        if (lower.endsWith(".sql")) return Optional.of(SQL);

        return Optional.empty();
    }

    private static String fileName(String lowerPath) {
        int slash = Math.max(lowerPath.lastIndexOf('/'), lowerPath.lastIndexOf('\\'));
        return slash >= 0 ? lowerPath.substring(slash + 1) : lowerPath;
    }

    private static boolean endsWithAny(String s, String... suffixes) {
        return Arrays.stream(suffixes).anyMatch(s::endsWith);
    }
}
