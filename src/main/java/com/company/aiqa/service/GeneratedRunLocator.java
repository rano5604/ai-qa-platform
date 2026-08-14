package com.company.aiqa.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Works out which folder under {@code generated-tests/} holds a given commit's
 * output.
 *
 * <p>Shared by the generation and execution passes on purpose. They have to
 * agree on the answer exactly - generation writes the scripts, execution reads
 * them back - and two private copies of this arithmetic drifting apart would
 * show up as "no automation script found" for a commit whose script is sitting
 * right there under a slightly different name.
 */
@Service
public class GeneratedRunLocator {

    private static final Logger log = LoggerFactory.getLogger(GeneratedRunLocator.class);

    /**
     * The project folder name under generated-tests/. Taken from the request
     * when given, otherwise derived from repoUrl's last path segment - the same
     * rule GitDiffService.resolveProjectName applies to a remote URL, but
     * without needing a clone to read it from.
     */
    public String resolveProjectName(String projectName, String repoUrl) {
        if (projectName != null && !projectName.isBlank()) {
            return projectName.trim();
        }
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "Supply either \"projectName\" or \"repoUrl\" so the generated-tests folder can be located.");
        }
        String trimmed = repoUrl.trim();
        while (trimmed.endsWith("/") || trimmed.endsWith("\\")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith(".git")) {
            trimmed = trimmed.substring(0, trimmed.length() - 4);
        }
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        String name = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        return name.replaceAll("[^a-zA-Z0-9_.-]+", "_");
    }

    /** Mirrors QaPipelineService's folder naming so the same commit maps to the same directory. */
    public String sanitizeForPath(String ref) {
        if (ref == null || ref.isBlank()) {
            return "run";
        }
        String safe = ref.replaceAll("[^a-zA-Z0-9]+", "_");
        return safe.length() > 60 ? safe.substring(0, 60) : safe;
    }

    /**
     * Where one commit's run output lives.
     *
     * <p>Backfill names folders {@code <seq>.<commitHash>} so the listing reads
     * in merge order, while single runs and everything generated before that
     * change use the bare hash. Automation is handed only a commit hash, so
     * this accepts BOTH shapes - anything else would make a commit's automation
     * unfindable the moment it was produced by a backfill.
     *
     * <p>Falls back to the bare-hash path when nothing exists yet, so callers
     * reporting "no folder for this commit" still name a sensible location.
     */
    public String runOutputDir(String baseOutputDir, String projectName, String commitHash) {
        String hash = sanitizeForPath(commitHash);
        Path projectDir = Path.of(baseOutputDir, projectName);
        Path exact = projectDir.resolve(hash);

        if (Files.isDirectory(exact)) {
            return exact.toString();
        }

        // "12.<hash>" - a sequence prefix added by the backfill.
        Pattern sequenced = Pattern.compile("^\\d+\\." + Pattern.quote(hash) + "$");
        if (Files.isDirectory(projectDir)) {
            try (Stream<Path> entries = Files.list(projectDir)) {
                Optional<Path> match = entries
                        .filter(Files::isDirectory)
                        .filter(p -> sequenced.matcher(p.getFileName().toString()).matches())
                        .findFirst();
                if (match.isPresent()) {
                    return match.get().toString();
                }
            } catch (IOException e) {
                log.warn("Could not scan {} for commit {}: {}", projectDir, hash, e.getMessage());
            }
        }
        return exact.toString();
    }
}
