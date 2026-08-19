package com.company.aiqa.testcase;

import com.company.aiqa.model.TestCaseResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Step: "Generate Test Cases".
 *
 * Parses the LLM's JSON response (per PromptBuilder's contract) into
 * TestCaseResult records and writes each one to disk under the configured
 * output directory.
 */
@Service
public class TestCaseGenerator {

    private static final Logger log = LoggerFactory.getLogger(TestCaseGenerator.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Same repetition-loop signature GeminiService guards against. If a single
     *  field of the parsed JSON contains a long run of one repeated character,
     *  the upstream LLM call degenerated - even though this array happened to
     *  parse as valid JSON, the content itself is garbage and shouldn't be
     *  written out as a test case. */
    private static final Pattern DEGENERATE_REPETITION = Pattern.compile("(.)\\1{39,}", Pattern.DOTALL);

    public List<TestCaseResult> generateAndWrite(String llmJsonResponse, String outputDir) {
        return generateAndWrite(llmJsonResponse, outputDir, new java.util.HashSet<>());
    }

    /**
     * Same, but refuses to reuse a file name already in {@code takenFileNames}
     * (which it adds to as it goes).
     *
     * <p>Needed once generation is batched: independent batches routinely pick
     * the same obvious class name - three batches of slot tests all called
     * themselves ShopSlotManagementTest - and each write silently clobbered
     * the last, so only the final batch survived. Renaming has to rewrite the
     * public class declaration inside the source too, since a Java public
     * class name must match its file name or it won't compile.
     */
    public List<TestCaseResult> generateAndWrite(String llmJsonResponse, String outputDir,
                                                  java.util.Set<String> takenFileNames) {
        List<TestCaseResult> results = new ArrayList<>();
        JsonNode array = parseJsonArray(llmJsonResponse);

        for (JsonNode node : array) {
            String targetClassName = node.path("targetClassName").asText("Unknown");
            // Falls back to .java only if the model omits testFileName entirely (rare,
            // since the prompt requires it) - at that point we have no reliable way to
            // infer the target language's correct extension anyway.
            String testFileName = node.path("testFileName").asText(targetClassName + "Test.java");
            String testCode = node.path("testCode").asText("");

            if (testCode.isBlank()) {
                log.warn("Skipping empty test code for {}", targetClassName);
                continue;
            }
            if (DEGENERATE_REPETITION.matcher(testCode).find()) {
                log.warn("Skipping degenerate (repeated-character) test code for {} - {} char(s), likely a "
                        + "generation failure rather than real test code", targetClassName, testCode.length());
                continue;
            }

            if (takenFileNames.contains(testFileName)) {
                String unique = uniqueName(testFileName, takenFileNames);
                testCode = renameClassIn(testCode, stripExtension(testFileName), stripExtension(unique));
                log.info("'{}' was already generated in this run - writing as '{}' instead so the earlier "
                        + "file isn't overwritten.", testFileName, unique);
                testFileName = unique;
            }
            takenFileNames.add(testFileName);

            String writtenPath = writeToDisk(outputDir, testFileName, testCode);
            results.add(new TestCaseResult(targetClassName, testFileName, testCode, writtenPath));
        }

        log.info("Generated {} test file(s) under {}", results.size(), outputDir);
        return results;
    }

    /** Foo.java -> Foo2.java, Foo3.java, ... until unused. */
    private String uniqueName(String fileName, java.util.Set<String> taken) {
        String base = stripExtension(fileName);
        String ext = fileName.substring(base.length());
        for (int i = 2; i < 1000; i++) {
            String candidate = base + i + ext;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        return base + System.nanoTime() + ext;
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * Renames the public class so it matches the new file name. Deliberately
     * narrow - only the declaration and constructor-shaped occurrences - to
     * avoid mangling a string literal that happens to contain the same word.
     */
    private String renameClassIn(String code, String oldName, String newName) {
        return code.replaceAll("(\\bclass\\s+)" + Pattern.quote(oldName) + "\\b", "$1" + newName);
    }

    private JsonNode parseJsonArray(String llmJsonResponse) {
        return LlmJsonArray.parse(objectMapper, llmJsonResponse, "automated test files");
    }

    private String writeToDisk(String outputDir, String fileName, String content) {
        try {
            Path dir = Path.of(outputDir);
            Files.createDirectories(dir);
            Path filePath = dir.resolve(fileName);
            Files.writeString(filePath, content, StandardCharsets.UTF_8);
            return filePath.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write generated test " + fileName + ": " + e.getMessage(), e);
        }
    }
}
