package com.company.aiqa.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * Every language the pipeline knows how to handle. Adding a new language is
 * just adding an enum entry here plus (optionally) a language-specific
 * parser in the `parser` package - GenericSourceParser's regex-based
 * fallback already covers most C-family / brace languages reasonably well
 * without one.
 */
public enum SourceLanguage {

    JAVA(".java", "JUnit 5 (with Mockito for collaborators)"),
    KOTLIN(".kt", "JUnit 5 + Kotest/MockK"),
    DART(".dart", "Dart's built-in `test` package (or `flutter_test` + `mocktail` for widgets/services)"),
    JAVASCRIPT(".js,.jsx", "Jest"),
    TYPESCRIPT(".ts,.tsx", "Jest with ts-jest (or Vitest if the repo already uses it)"),
    PYTHON(".py", "pytest"),
    GO(".go", "Go's built-in \"testing\" package (table-driven tests)"),
    CSHARP(".cs", "xUnit"),
    SWIFT(".swift", "XCTest"),
    RUBY(".rb", "RSpec");

    private final String[] extensions;
    private final String testFrameworkHint;

    SourceLanguage(String extensionsCsv, String testFrameworkHint) {
        this.extensions = extensionsCsv.split(",");
        this.testFrameworkHint = testFrameworkHint;
    }

    public String[] extensions() {
        return extensions;
    }

    public String testFrameworkHint() {
        return testFrameworkHint;
    }

    /** True if this path's extension is one this pipeline knows how to handle. */
    public static boolean isSupported(String path) {
        return fromPath(path).isPresent();
    }

    public static Optional<SourceLanguage> fromPath(String path) {
        if (path == null) return Optional.empty();
        String lower = path.toLowerCase();
        return Arrays.stream(values())
                .filter(lang -> Arrays.stream(lang.extensions).anyMatch(lower::endsWith))
                .findFirst();
    }
}
