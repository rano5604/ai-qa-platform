package com.company.aiqa.testcase;

import com.company.aiqa.model.ManualTestCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mms initial commit: an earlier run produced 34 cases across several
 * categories and left NEGATIVE and BOUNDARY incomplete. The resume regenerated
 * only those two, and {@code writeCsv} put its five cases over the top of the
 * file - 34 became 5, and {@code /generate-automation}, which reads exactly
 * this file, would have seen five.
 */
class ResumeCsvMergeTest {

    private final ManualTestCaseGenerator generator = new ManualTestCaseGenerator();

    private static ManualTestCase caseOf(String id, String type, String scenario) {
        return new ManualTestCase(id, "Fee Configuration", scenario, "none",
                "1. Call it.", "", "HTTP 400", "High", type, "", "NEW", "");
    }

    /** An earlier run's output: four categories, ids TC-001..TC-008. */
    private void seedEarlierRun(Path dir) {
        List<ManualTestCase> earlier = new ArrayList<>();
        earlier.add(caseOf("TC-001", "Negative", "old negative one"));
        earlier.add(caseOf("TC-002", "Negative", "old negative two"));
        earlier.add(caseOf("TC-003", "Boundary", "old boundary one"));
        earlier.add(caseOf("TC-004", "Positive", "positive one"));
        earlier.add(caseOf("TC-005", "Positive", "positive two"));
        earlier.add(caseOf("TC-006", "Security", "security one"));
        earlier.add(caseOf("TC-007", "Configuration", "config one"));
        earlier.add(caseOf("TC-008", "Configuration", "config two"));
        generator.writeCsv(earlier, dir.toString(), "manual_test_cases.csv");
    }

    private List<ManualTestCase> onDisk(Path dir) {
        return generator.loadRunCsv(dir.toString(), "manual_test_cases.csv");
    }

    @Test
    void keepsTheCategoriesTheResumeDidNotRegenerate(@TempDir Path dir) {
        seedEarlierRun(dir);

        generator.writeCsvPreservingOtherCategories(
                List.of(caseOf("TC-001", "Negative", "fresh negative")),
                dir.toString(), "manual_test_cases.csv", List.of("NEGATIVE", "BOUNDARY"));

        List<ManualTestCase> after = onDisk(dir);
        Set<String> scenarios = after.stream().map(ManualTestCase::scenario).collect(Collectors.toSet());

        // Untouched categories survive.
        assertTrue(scenarios.contains("positive one"), scenarios.toString());
        assertTrue(scenarios.contains("security one"), scenarios.toString());
        assertTrue(scenarios.contains("config two"), scenarios.toString());
        // The regenerated ones are replaced, not duplicated.
        assertFalse(scenarios.contains("old negative one"), scenarios.toString());
        assertFalse(scenarios.contains("old boundary one"), scenarios.toString());
        assertTrue(scenarios.contains("fresh negative"), scenarios.toString());
        assertEquals(6, after.size(), scenarios.toString());
    }

    /**
     * idCursor restarts at TC-001 every run, so the resume's TC-001 is a
     * different case from the existing TC-001. Keying the merge on id would
     * overwrite unrelated rows; keying on category and reassigning ids is what
     * keeps both correct.
     */
    @Test
    void reassignsIdsSoNoTwoCasesShareOne(@TempDir Path dir) {
        seedEarlierRun(dir);

        generator.writeCsvPreservingOtherCategories(
                List.of(caseOf("TC-001", "Negative", "fresh negative one"),
                        caseOf("TC-002", "Negative", "fresh negative two")),
                dir.toString(), "manual_test_cases.csv", List.of("NEGATIVE", "BOUNDARY"));

        List<ManualTestCase> after = onDisk(dir);
        List<String> ids = after.stream().map(ManualTestCase::testCaseId).toList();

        assertEquals(ids.size(), Set.copyOf(ids).size(), "duplicate ids: " + ids);
        // The survivors keep the ids anyone may already have referenced.
        assertTrue(ids.contains("TC-004"), ids.toString());
        assertTrue(ids.contains("TC-008"), ids.toString());
        // The fresh ones continue past the highest survivor rather than colliding.
        assertTrue(ids.contains("TC-009") && ids.contains("TC-010"), ids.toString());
    }

    @Test
    void reportsWhatItKept(@TempDir Path dir) {
        seedEarlierRun(dir);

        ManualTestCaseGenerator.RunCsvMerge merge = generator.writeCsvPreservingOtherCategories(
                List.of(caseOf("TC-001", "Negative", "fresh negative")),
                dir.toString(), "manual_test_cases.csv", List.of("NEGATIVE", "BOUNDARY"));

        assertEquals(5, merge.kept(), "3 negative/boundary cases should have been replaced");
        assertEquals(6, merge.merged().size());
    }

    /** Category names are compared without regard to case: CSV holds "Negative", the request says "NEGATIVE". */
    @Test
    void matchesCategoryNamesRegardlessOfCase(@TempDir Path dir) {
        seedEarlierRun(dir);

        generator.writeCsvPreservingOtherCategories(
                List.of(caseOf("TC-001", "Negative", "fresh negative")),
                dir.toString(), "manual_test_cases.csv", List.of("negative", "Boundary"));

        assertFalse(onDisk(dir).stream().anyMatch(c -> c.scenario().equals("old negative one")));
    }

    /** A first run has nothing to preserve and must behave exactly like writeCsv. */
    @Test
    void writesNormallyWhenThereIsNoEarlierFile(@TempDir Path dir) {
        ManualTestCaseGenerator.RunCsvMerge merge = generator.writeCsvPreservingOtherCategories(
                List.of(caseOf("TC-001", "Negative", "only case")),
                dir.toString(), "manual_test_cases.csv", List.of("NEGATIVE"));

        assertEquals(0, merge.kept());
        assertEquals(1, onDisk(dir).size());
        assertEquals("TC-001", onDisk(dir).get(0).testCaseId());
    }

    /** A full run regenerates everything, so nothing is preserved and the file is replaced. */
    @Test
    void replacesEverythingWhenEveryCategoryWasRegenerated(@TempDir Path dir) {
        seedEarlierRun(dir);

        generator.writeCsvPreservingOtherCategories(
                List.of(caseOf("TC-001", "Negative", "fresh negative")),
                dir.toString(), "manual_test_cases.csv",
                List.of("NEGATIVE", "BOUNDARY", "POSITIVE", "SECURITY", "CONFIGURATION"));

        assertEquals(1, onDisk(dir).size());
    }
}
