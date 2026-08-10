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

            String writtenPath = writeToDisk(outputDir, testFileName, testCode);
            results.add(new TestCaseResult(targetClassName, testFileName, testCode, writtenPath));
        }

        log.info("Generated {} test file(s) under {}", results.size(), outputDir);
        return results;
    }

    private JsonNode parseJsonArray(String llmJsonResponse) {
        String cleaned = stripMarkdownFences(llmJsonResponse);
        try {
            JsonNode node = objectMapper.readTree(cleaned);
            if (!node.isArray()) {
                throw new IllegalStateException("Expected a JSON array from the LLM but got: " + node.getNodeType());
            }
            return node;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not parse LLM response as JSON test-case array: " + e.getMessage()
                            + "\nRaw response:\n" + llmJsonResponse, e);
        }
    }

    /** Defensive: some models wrap JSON in ```json ... ``` fences despite instructions. */
    private String stripMarkdownFences(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(json)?", "").trim();
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
            }
        }
        return trimmed;
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
