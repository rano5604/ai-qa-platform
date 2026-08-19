package com.company.aiqa.testcase;

import com.company.aiqa.model.ManualTestCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step: "Generate Test Cases" (manual/business variant).
 *
 * Parses the LLM's business-test-case JSON response (per
 * PromptBuilder.businessTestCaseSystemPrompt's contract) into ManualTestCase
 * records, and merges them into a master catalog (CSV) that persists across
 * every merge this platform has ever processed - so a later merge that
 * changes behavior an existing test case already covers UPDATES that row in
 * place (see upsertMasterCsv) instead of piling up a duplicate, near-identical
 * case every time the same feature is touched again.
 */
@Service
public class ManualTestCaseGenerator {

    private static final Logger log = LoggerFactory.getLogger(ManualTestCaseGenerator.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String MASTER_CSV_HEADER =
            "Title,Section,Type,Priority,Preconditions,Steps,Expected Result,References,Source Ref";

    /**
     * @param llmJsonResponse raw LLM response for one category's prompt
     * @param category        "Positive" | "Negative" | "Boundary" | "Security" -
     *                        set programmatically rather than trusting the
     *                        model to self-label consistently, since this
     *                        call was already scoped to one category via
     *                        PromptBuilder.businessTestCaseSystemPrompt
     * @param startId         first Test Case ID number to assign to NEW cases
     *                        (so calling this once per category and merging
     *                        the results doesn't produce duplicate TC-001s).
     *                        Cases the model marks as UPDATE keep whatever ID
     *                        it echoed back rather than getting a fresh one.
     */
    public List<ManualTestCase> parse(String llmJsonResponse, String category, int startId) {
        return parse(llmJsonResponse, category, startId, List.of());
    }

    /**
     * Multi-category variant, used when aiqa.pipeline.categories-per-call &gt; 1
     * asks for several categories in one call. Each case is filed under the
     * category the model tagged it with (its "category" field), restricted to
     * {@code allowedCategories} - anything missing or unrecognised falls back
     * to {@code category}, so a sloppy label degrades to a sensible default
     * rather than losing the case or inventing a category that was never
     * requested.
     *
     * @param allowedCategories the categories this call actually asked for;
     *                          empty means single-category mode (the label is
     *                          ignored entirely and {@code category} is used).
     */
    public List<ManualTestCase> parse(String llmJsonResponse, String category, int startId,
                                       List<String> allowedCategories) {
        List<ManualTestCase> results = new ArrayList<>();
        JsonNode array = parseJsonArray(llmJsonResponse);

        int idCounter = startId;
        for (JsonNode node : array) {
            String feature = node.path("feature").asText("");
            String scenario = node.path("scenario").asText("");
            if (feature.isBlank() && scenario.isBlank()) {
                continue;
            }

            String action = node.path("action").asText("NEW").trim().toUpperCase();
            String existingTestCaseId = node.path("existingTestCaseId").asText("").trim();

            // A model claiming UPDATE without actually naming which existing
            // case it's updating isn't a usable update - treat it as NEW
            // rather than silently dropping the traceability link.
            if ("UPDATE".equals(action) && existingTestCaseId.isBlank()) {
                log.warn("LLM marked a {} case as UPDATE with no existingTestCaseId - treating as NEW instead. Scenario: {}",
                        category, scenario);
                action = "NEW";
            }
            if (!"UPDATE".equals(action)) {
                action = "NEW";
                existingTestCaseId = "";
            }

            String testCaseId = "UPDATE".equals(action)
                    ? existingTestCaseId
                    : "TC-%03d".formatted(idCounter++);

            results.add(new ManualTestCase(
                    testCaseId,
                    feature,
                    scenario,
                    node.path("preconditions").asText(""),
                    node.path("steps").asText(""),
                    node.path("testData").asText(""),
                    node.path("expectedResult").asText(""),
                    node.path("priority").asText("Medium"),
                    resolveType(node, category, allowedCategories, scenario),
                    "", // relatedFile - business test cases intentionally don't reference source files
                    action,
                    existingTestCaseId
            ));
        }

        long updateCount = results.stream().filter(tc -> "UPDATE".equals(tc.action())).count();
        log.info("Parsed {} {} test case(s) from LLM response ({} new, {} update(s))",
                results.size(), category, results.size() - updateCount, updateCount);
        return results;
    }

    /**
     * Picks the Type column value for one case. Single-category mode ignores
     * whatever the model said and uses the caller's category (it was already
     * scoped to one). Multi-category mode honours the model's own "category"
     * label, but only if it matches one actually requested.
     */
    private String resolveType(JsonNode node, String fallbackCategory, List<String> allowedCategories, String scenario) {
        if (allowedCategories == null || allowedCategories.isEmpty()) {
            return fallbackCategory;
        }
        String labelled = node.path("category").asText("").trim();
        if (labelled.isBlank()) {
            log.warn("Multi-category response omitted \"category\" - filing under '{}'. Scenario: {}",
                    fallbackCategory, scenario);
            return fallbackCategory;
        }
        for (String allowed : allowedCategories) {
            if (allowed.equalsIgnoreCase(labelled)) {
                return capitalize(allowed);
            }
        }
        log.warn("Response labelled a case '{}', which wasn't one of the requested categories {} - filing under '{}'. Scenario: {}",
                labelled, allowedCategories, fallbackCategory, scenario);
        return fallbackCategory;
    }

    /** POSITIVE -> Positive, matching the Type values written for single-category runs. */
    private String capitalize(String value) {
        if (value == null || value.isBlank()) return value;
        String lower = value.toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private JsonNode parseJsonArray(String llmJsonResponse) {
        return LlmJsonArray.parse(objectMapper, llmJsonResponse, "business test cases");
    }

    /**
     * Writes the business test case list to a CSV file under outputDir, using
     * TestRail's standard case-field column names (Title, Section, Type,
     * Priority, Preconditions, Steps, Expected Result, References) so it
     * auto-maps in TestRail's CSV import wizard without manual remapping.
     * Returns the file's absolute path.
     */
    public String writeCsv(List<ManualTestCase> testCases, String outputDir, String fileName) {
        try {
            Path dir = Path.of(outputDir);
            Files.createDirectories(dir);
            Path filePath = dir.resolve(fileName);

            try (Writer writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
                writer.write("Title,Section,Type,Priority,Preconditions,Steps,Expected Result,References\n");
                for (ManualTestCase tc : testCases) {
                    // TestRail has no native "Test Data" field - fold it into Steps
                    // (as freeform text) rather than dropping it.
                    String steps = tc.testData() != null && !tc.testData().isBlank()
                            ? tc.steps() + "\n\nTest Data: " + tc.testData()
                            : tc.steps();

                    writer.write(String.join(",",
                            csvEscape(tc.scenario()),        // Title
                            csvEscape(tc.feature()),         // Section
                            csvEscape(tc.type()),            // Type
                            csvEscape(tc.priority()),        // Priority
                            csvEscape(tc.preconditions()),   // Preconditions
                            csvEscape(steps),                // Steps
                            csvEscape(tc.expectedResult()),  // Expected Result
                            csvEscape(tc.testCaseId())       // References (internal traceability ID)
                    ));
                    writer.write("\n");
                }
            }

            log.info("Wrote {} business test case row(s) to {} (TestRail-importable format)", testCases.size(), filePath);
            return filePath.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write business test case CSV: " + e.getMessage(), e);
        }
    }

    /**
     * Appends the business test case list to a master CSV under outputDir,
     * unconditionally as new rows - kept for backward compatibility /
     * simple append-only use cases. Prefer upsertMasterCsv for the normal
     * pipeline path, since it actually honors action=="UPDATE" instead of
     * piling up a duplicate row every time an existing case is touched again.
     */
    public String appendToMasterCsv(List<ManualTestCase> testCases, String outputDir, String fileName, String sourceRef) {
        try {
            Path dir = Path.of(outputDir);
            Files.createDirectories(dir);
            Path filePath = dir.resolve(fileName);
            boolean isNewFile = !Files.exists(filePath);

            String shortRef = sourceRef != null && sourceRef.length() > 7 ? sourceRef.substring(0, 7) : sourceRef;

            try (Writer writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
                if (isNewFile) {
                    writer.write(MASTER_CSV_HEADER + "\n");
                }
                for (ManualTestCase tc : testCases) {
                    String steps = tc.testData() != null && !tc.testData().isBlank()
                            ? tc.steps() + "\n\nTest Data: " + tc.testData()
                            : tc.steps();
                    String globalId = shortRef != null && !shortRef.isBlank()
                            ? shortRef + "-" + tc.testCaseId()
                            : tc.testCaseId();

                    writer.write(String.join(",",
                            csvEscape(tc.scenario()),
                            csvEscape(tc.feature()),
                            csvEscape(tc.type()),
                            csvEscape(tc.priority()),
                            csvEscape(tc.preconditions()),
                            csvEscape(steps),
                            csvEscape(tc.expectedResult()),
                            csvEscape(globalId),
                            csvEscape(sourceRef == null ? "" : sourceRef)
                    ));
                    writer.write("\n");
                }
            }

            log.info("Appended {} business test case row(s) to master CSV {}", testCases.size(), filePath);
            return filePath.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to append to master test case CSV: " + e.getMessage(), e);
        }
    }

    /**
     * Loads the current master catalog (see upsertMasterCsv) as ManualTestCase
     * records, one per existing row, action always "EXISTING" and
     * testCaseId set to that row's References column value - the same ID
     * PromptBuilder shows the LLM and the same ID it must echo back in
     * existingTestCaseId to mark a case as UPDATE. Returns an empty list if
     * the master CSV doesn't exist yet (e.g. the very first run for a repo).
     */
    /**
     * Loads a single run's CSV (the 8-column file writeCsv produces under
     * generated-tests/&lt;project&gt;/&lt;headRef&gt;/), as opposed to the
     * 9-column master catalog loadMasterCsv reads. Used by the automation
     * endpoint to pick up exactly the cases generated for one commit.
     *
     * <p>Test data was folded into the Steps column on write (TestRail has no
     * Test Data field), so it comes back as part of steps rather than being
     * split out again - which is fine for automation, since the generator
     * needs the values, not the column they came from.
     *
     * @return the cases, or an empty list when the file doesn't exist.
     */
    public List<ManualTestCase> loadRunCsv(String runOutputDir, String fileName) {
        Path filePath = Path.of(runOutputDir, fileName);
        if (!Files.exists(filePath)) {
            return List.of();
        }
        try {
            List<String[]> rows = parseCsv(Files.readString(filePath, StandardCharsets.UTF_8));
            List<ManualTestCase> cases = new ArrayList<>();
            for (int i = 1; i < rows.size(); i++) {   // row 0 is the header
                String[] row = rows.get(i);
                if (row.length < 8 || (row.length == 1 && row[0].isBlank())) {
                    continue;   // blank trailing line or malformed row - skip rather than fail the load
                }
                cases.add(new ManualTestCase(
                        row[7],   // References -> testCaseId
                        row[1],   // Section -> feature
                        row[0],   // Title -> scenario
                        row[4],   // Preconditions
                        row[5],   // Steps (includes the folded-in test data)
                        "",       // testData - already inside steps
                        row[6],   // Expected Result
                        row[3],   // Priority
                        row[2],   // Type
                        "",       // relatedFile
                        "EXISTING",
                        ""
                ));
            }
            log.info("Loaded {} manual test case(s) from {}", cases.size(), filePath);
            return cases;
        } catch (IOException e) {
            log.warn("Could not read run CSV {}: {}", filePath, e.getMessage());
            return List.of();
        }
    }

    public List<ManualTestCase> loadMasterCsv(String outputDir, String fileName) {
        Path filePath = Path.of(outputDir, fileName);
        if (!Files.exists(filePath)) {
            return List.of();
        }
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            List<String[]> rows = parseCsv(content);
            if (rows.isEmpty()) {
                return List.of();
            }

            List<ManualTestCase> existing = new ArrayList<>();
            // rows.get(0) is the header - skip it.
            for (int i = 1; i < rows.size(); i++) {
                String[] row = rows.get(i);
                if (row.length < 8 || (row.length == 1 && row[0].isBlank())) {
                    continue; // blank trailing line, or a malformed row - skip rather than fail the whole load
                }
                existing.add(new ManualTestCase(
                        row[7],           // References -> testCaseId
                        row[1],           // Section -> feature
                        row[0],           // Title -> scenario
                        row[4],           // Preconditions
                        row[5],           // Steps
                        "",               // testData folded into Steps already; not split back out
                        row[6],           // Expected Result
                        row[3],           // Priority
                        row[2],           // Type
                        "",               // relatedFile
                        "EXISTING",
                        ""
                ));
            }

            log.info("Loaded {} existing test case(s) from master catalog {}", existing.size(), filePath);
            return existing;
        } catch (IOException e) {
            log.warn("Could not read master test case catalog {} - proceeding as if it were empty: {}",
                    filePath, e.getMessage());
            return List.of();
        }
    }

    /**
     * Merges newTestCases into the master catalog at outputDir/fileName:
     *   - action=="NEW"    -> appended as a new row with a fresh, globally
     *                         unique ID (sourceRef-TC-00N, same scheme as
     *                         appendToMasterCsv).
     *   - action=="UPDATE" -> replaces the existing row whose References
     *                         column equals existingTestCaseId, in place
     *                         (same position, same ID) - just with this
     *                         merge's corrected content and an updated
     *                         Source Ref showing which merge last touched it.
     *                         If existingTestCaseId doesn't match any row
     *                         currently in the catalog (stale reference,
     *                         or the catalog was reset), falls back to
     *                         appending it as NEW instead of silently
     *                         dropping it.
     *
     * Rewrites the whole file (unlike appendToMasterCsv), since an UPDATE
     * has to land in the middle of the file, not just the end.
     */
    public String upsertMasterCsv(List<ManualTestCase> newTestCases, String outputDir, String fileName, String sourceRef) {
        Path dir = Path.of(outputDir);
        Path filePath = dir.resolve(fileName);
        String shortRef = sourceRef != null && sourceRef.length() > 7 ? sourceRef.substring(0, 7) : sourceRef;

        // id -> full raw row (Title,Section,Type,Priority,Preconditions,Steps,Expected Result,References,Source Ref)
        Map<String, String[]> rowsById = new LinkedHashMap<>();
        try {
            if (Files.exists(filePath)) {
                List<String[]> rows = parseCsv(Files.readString(filePath, StandardCharsets.UTF_8));
                for (int i = 1; i < rows.size(); i++) {
                    String[] row = rows.get(i);
                    if (row.length >= 8 && !row[7].isBlank()) {
                        rowsById.put(row[7], row);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Could not read existing master catalog {} before merging - starting fresh: {}",
                    filePath, e.getMessage());
        }

        int updated = 0;
        int appended = 0;
        int fellBackToNew = 0;

        for (ManualTestCase tc : newTestCases) {
            String steps = tc.testData() != null && !tc.testData().isBlank()
                    ? tc.steps() + "\n\nTest Data: " + tc.testData()
                    : tc.steps();

            boolean isUpdate = "UPDATE".equals(tc.action())
                    && tc.existingTestCaseId() != null
                    && !tc.existingTestCaseId().isBlank()
                    && rowsById.containsKey(tc.existingTestCaseId());

            if ("UPDATE".equals(tc.action()) && !isUpdate) {
                log.warn("Test case references existingTestCaseId '{}' which isn't in the master catalog - "
                        + "adding as a new case instead. Scenario: {}", tc.existingTestCaseId(), tc.scenario());
                fellBackToNew++;
            }

            if (isUpdate) {
                String[] row = {
                        tc.scenario(), tc.feature(), tc.type(), tc.priority(),
                        tc.preconditions(), steps, tc.expectedResult(),
                        tc.existingTestCaseId(),
                        sourceRef == null ? "" : sourceRef
                };
                rowsById.put(tc.existingTestCaseId(), row);
                updated++;
            } else {
                String globalId = shortRef != null && !shortRef.isBlank()
                        ? shortRef + "-" + tc.testCaseId()
                        : tc.testCaseId();
                String[] row = {
                        tc.scenario(), tc.feature(), tc.type(), tc.priority(),
                        tc.preconditions(), steps, tc.expectedResult(),
                        globalId,
                        sourceRef == null ? "" : sourceRef
                };
                rowsById.put(globalId, row);
                appended++;
            }
        }

        try {
            Files.createDirectories(dir);
            try (Writer writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
                writer.write(MASTER_CSV_HEADER + "\n");
                for (String[] row : rowsById.values()) {
                    StringBuilder line = new StringBuilder();
                    for (int i = 0; i < row.length; i++) {
                        if (i > 0) line.append(',');
                        line.append(csvEscape(row[i]));
                    }
                    writer.write(line.toString());
                    writer.write("\n");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write merged master test case catalog: " + e.getMessage(), e);
        }

        log.info("Merged into master catalog {}: {} new, {} updated in place{}",
                filePath, appended, updated,
                fellBackToNew > 0 ? " (%d UPDATE(s) fell back to NEW - stale existingTestCaseId)".formatted(fellBackToNew) : "");

        return filePath.toAbsolutePath().toString();
    }

    /**
     * Minimal RFC4180-style CSV parser matching what csvEscape/writeCsv
     * produce: comma-delimited, double-quote quoting, "" as an escaped quote
     * inside a quoted field, and quoted fields allowed to contain embedded
     * newlines (Steps routinely does, since it's multi-line numbered steps).
     * Deliberately hand-rolled rather than pulling in a CSV library, to stay
     * consistent with how this class already writes CSV by hand.
     */
    private List<String[]> parseCsv(String content) {
        List<String[]> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int len = content.length();

        while (i < len) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                    } else {
                        inQuotes = false;
                        i++;
                    }
                } else {
                    field.append(c);
                    i++;
                }
            } else if (c == '"') {
                inQuotes = true;
                i++;
            } else if (c == ',') {
                currentRow.add(field.toString());
                field.setLength(0);
                i++;
            } else if (c == '\r') {
                i++; // swallow, \n (or end of quoted field) handles the actual line break
            } else if (c == '\n') {
                currentRow.add(field.toString());
                field.setLength(0);
                rows.add(currentRow.toArray(new String[0]));
                currentRow = new ArrayList<>();
                i++;
            } else {
                field.append(c);
                i++;
            }
        }
        if (field.length() > 0 || !currentRow.isEmpty()) {
            currentRow.add(field.toString());
            rows.add(currentRow.toArray(new String[0]));
        }
        return rows;
    }

    private String csvEscape(String value) {
        if (value == null) return "";
        boolean needsQuoting = value.contains(",") || value.contains("\"") || value.contains("\n");
        String escaped = value.replace("\"", "\"\"");
        return needsQuoting ? "\"" + escaped + "\"" : escaped;
    }
}
