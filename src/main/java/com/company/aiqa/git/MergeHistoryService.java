package com.company.aiqa.git;

import com.company.aiqa.config.GitProperties;
import com.company.aiqa.model.MergeHistoryEntry;
import com.company.aiqa.model.MergeStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Tracks which merge commits have already been run through the pipeline,
 * per (repoUrl, branch), so that:
 *
 *   - a backfill can be run repeatedly and safely - already-processed
 *     merges are skipped rather than reprocessed;
 *   - once a branch has been fully backfilled, day-to-day triggers
 *     (webhooks, or repeated calls to /generate-tests-from-branch) only
 *     do work for merges that are actually new.
 *
 * Persistence is a plain JSON file per repo+branch under
 * "<aiqa.git.workspace-dir>/<aiqa.git.history-dir>/", written alongside the
 * repo clones themselves. No database required; this is deliberately the
 * simplest thing that gives every deployment durable history without extra
 * infrastructure. Swap this out for a real datastore later if needed - it's
 * the only class that would have to change, since everything else talks to
 * merge history through this service.
 */
@Service
public class MergeHistoryService {

    private static final Logger log = LoggerFactory.getLogger(MergeHistoryService.class);

    private final GitProperties gitProperties;
    private final ObjectMapper objectMapper = new ObjectMapper().enable(
            com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

    public MergeHistoryService(GitProperties gitProperties) {
        this.gitProperties = gitProperties;
    }

    /** All merges recorded as processed for this repo+branch, oldest first. */
    public synchronized List<MergeHistoryEntry> load(String repoUrl, String branch) {
        Path file = historyFile(repoUrl, branch);
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            MergeHistoryEntry[] entries = objectMapper.readValue(file.toFile(), MergeHistoryEntry[].class);
            return new ArrayList<>(Arrays.asList(entries));
        } catch (IOException e) {
            log.warn("Could not read merge history file {} - treating as empty: {}", file, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * True only when this merge COMPLETED - i.e. every expected test-case
     * category was generated. A merge recorded as PARTIAL or FAILED returns
     * false, which is what makes backfill automatically pick it back up and
     * finish it instead of skipping it forever.
     *
     * <p>Note this is a deliberate semantic change from the original
     * "is it in the file at all" check. Entries written before status
     * tracking existed default to SUCCESS (see MergeHistoryEntry), so
     * upgrading doesn't re-run history that was already considered done.
     */
    public synchronized boolean isProcessed(String repoUrl, String branch, String mergeSha) {
        return findEntry(repoUrl, branch, mergeSha)
                .filter(e -> !e.isIncomplete())
                .isPresent();
    }

    /** The recorded entry for a merge, whatever its status. */
    public synchronized Optional<MergeHistoryEntry> findEntry(String repoUrl, String branch, String mergeSha) {
        return load(repoUrl, branch).stream()
                .filter(e -> e.mergeSha().equals(mergeSha))
                .findFirst();
    }

    /**
     * Every merge with outstanding work (PARTIAL or FAILED), oldest first -
     * the queue a resume/backfill run works through.
     */
    public synchronized List<MergeHistoryEntry> findIncomplete(String repoUrl, String branch) {
        return load(repoUrl, branch).stream()
                .filter(MergeHistoryEntry::isIncomplete)
                .toList();
    }

    /**
     * Records the outcome of an attempt, replacing any existing row for this
     * merge (rather than the old "first write wins, later ones are a no-op"
     * behavior, which is precisely what prevented a partial run from ever
     * being upgraded to complete).
     *
     * <p>Category lists are accumulated across attempts: a resume that
     * generates the two categories a previous attempt missed leaves the entry
     * showing all of them as generated, so coverage reflects total progress
     * rather than just the last attempt.
     */
    public synchronized void recordAttempt(String repoUrl, String branch, String mergeSha, String preMergeSha,
                                            String summary, MergeStatus status, String failureReason,
                                            List<String> expectedCategories, List<String> generatedThisAttempt,
                                            int testCaseCount) {
        List<MergeHistoryEntry> entries = load(repoUrl, branch);
        Optional<MergeHistoryEntry> existing = entries.stream()
                .filter(e -> e.mergeSha().equals(mergeSha))
                .findFirst();

        String now = Instant.now().toString();
        List<String> expected = expectedCategories == null ? List.of() : List.copyOf(expectedCategories);

        // Union of what previous attempts produced and what this one did.
        LinkedHashSet<String> generated = new LinkedHashSet<>();
        existing.ifPresent(e -> generated.addAll(e.generatedCategories()));
        if (generatedThisAttempt != null) {
            generated.addAll(generatedThisAttempt);
        }

        List<String> missing = expected.stream().filter(c -> !generated.contains(c)).toList();
        // Recompute the effective status from accumulated progress: a resume
        // that closes the last gap flips a PARTIAL entry to SUCCESS.
        MergeStatus effectiveStatus = status;
        if (status != MergeStatus.FAILED) {
            effectiveStatus = missing.isEmpty() ? MergeStatus.SUCCESS : MergeStatus.PARTIAL;
        }

        int coverage = expected.isEmpty() ? (effectiveStatus == MergeStatus.SUCCESS ? 100 : 0)
                : (int) Math.round(100.0 * (expected.size() - missing.size()) / expected.size());

        int attempts = existing.map(e -> e.attemptCount() + 1).orElse(1);
        String firstSeen = existing.map(MergeHistoryEntry::processedAt).orElse(now);
        int totalCases = existing.map(e -> e.testCaseCount() + testCaseCount).orElse(testCaseCount);

        MergeHistoryEntry updated = new MergeHistoryEntry(
                mergeSha, preMergeSha, firstSeen, summary,
                effectiveStatus, attempts, now,
                effectiveStatus == MergeStatus.SUCCESS ? null : failureReason,
                expected, List.copyOf(generated), missing,
                totalCases, coverage);

        existing.ifPresent(entries::remove);
        entries.add(updated);
        write(repoUrl, branch, entries);

        log.info("Merge {} on '{}' recorded as {} (attempt {}, coverage {}%{})",
                mergeSha, branch, effectiveStatus, attempts, coverage,
                missing.isEmpty() ? "" : ", missing: " + missing);
    }

    private void write(String repoUrl, String branch, List<MergeHistoryEntry> entries) {
        Path file = historyFile(repoUrl, branch);
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writeValue(file.toFile(), entries);
        } catch (IOException e) {
            // A failed write here means the next run may reprocess this merge -
            // safe (pipeline runs are idempotent in effect, just wasteful) but
            // worth surfacing loudly rather than silently losing history.
            log.error("Could not persist merge history to {}: {}", file, e.getMessage(), e);
        }
    }

    private Path historyFile(String repoUrl, String branch) {
        return Path.of(gitProperties.getWorkspaceDir(), gitProperties.getHistoryDir(), keyFor(repoUrl, branch) + ".json");
    }

    private String keyFor(String repoUrl, String branch) {
        return (repoUrl + "__" + branch)
                .replaceAll("^https?://", "")
                .replaceAll("\\.git$", "")
                .replaceAll("[^a-zA-Z0-9]+", "_");
    }
}
