package com.company.aiqa.git;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The README is fed to the generators as architectural/business context. These
 * pin the choices that matter: the root README over a nested one, a cap so a
 * huge doc can't blow the prompt budget, and a quiet empty when there is none.
 */
class ProjectDocReaderTest {

    /** A tiny stand-in for SourceAtCommit backed by an in-memory path->content map. */
    private static GitDiffService.SourceAtCommit source(Map<String, String> files) {
        return new GitDiffService.SourceAtCommit() {
            @Override public List<String> paths() { return List.copyOf(files.keySet()); }
            @Override public String read(String path) { return files.get(path); }
            @Override public void close() { }
        };
    }

    @Test
    void readsARootReadme() {
        String out = ProjectDocReader.readReadme(source(Map.of(
                "README.md", "# TailorBook\nManages tailoring shops and orders.")), 16_000);
        assertTrue(out.contains("Manages tailoring shops"), out);
    }

    @Test
    void prefersTheRootReadmeOverANestedOne() {
        String out = ProjectDocReader.readReadme(source(new java.util.LinkedHashMap<>(Map.of(
                "docs/module/README.md", "nested module readme",
                "README.md", "root project readme"))), 16_000);
        assertEquals("root project readme", out);
    }

    @Test
    void matchesReadmeCaseInsensitivelyAndOtherExtensions() {
        String out = ProjectDocReader.readReadme(source(Map.of(
                "ReadMe.rst", "restructured text overview")), 16_000);
        assertTrue(out.contains("restructured text overview"), out);
    }

    @Test
    void truncatesALongReadmeAtTheCap() {
        String big = "x".repeat(50_000);
        String out = ProjectDocReader.readReadme(source(Map.of("README.md", big)), 1_000);
        assertTrue(out.length() < 1_200, "expected truncation, was " + out.length());
        assertTrue(out.contains("[README truncated at 1000 chars]"), out);
    }

    @Test
    void noReadmeYieldsEmpty() {
        assertEquals("", ProjectDocReader.readReadme(source(Map.of(
                "src/App.java", "class App {}")), 16_000));
    }

    @Test
    void nullSourceYieldsEmpty() {
        assertEquals("", ProjectDocReader.readReadme(null, 16_000));
    }

    @Test
    void doesNotMistakeAReadmeSuffixedFileForTheReadme() {
        // "READMENOTES.md" is not a README - the name must match exactly.
        assertEquals("", ProjectDocReader.readReadme(source(Map.of(
                "READMENOTES.md", "not the readme")), 16_000));
    }
}
