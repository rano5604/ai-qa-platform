package com.company.aiqa.testcase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Parses the JSON array every generation prompt asks the LLM for - and, the
 * reason this is its own class, tells "the model answered, there is simply
 * nothing to generate" apart from "the call failed".
 *
 * <p>That distinction is not cosmetic. The pipeline marks a category as
 * covered only when its call completed without throwing, so a parse failure
 * costs the whole category: it lands in missingCategories, the merge is
 * recorded PARTIAL, and every later resume runs it again. Once the prompts
 * started telling the model to return nothing when a category doesn't apply
 * (SECURITY on a change with no auth surface is the ordinary case, not the
 * exception), a correct answer started being recorded as a failed one - a
 * clean run reported 75% coverage and retried the same category forever.
 *
 * <p>The two shapes a "nothing to generate" answer arrives in:
 * <ul>
 *   <li>an empty response, or</li>
 *   <li>a short line of prose - "No security test cases apply to this change."
 *       - instead of the {@code []} the prompt asked for.</li>
 * </ul>
 *
 * <p>Anything containing a {@code [} or a {@code {} is treated as content: a
 * truncated or malformed array is a real failure and still throws, because
 * swallowing it would silently drop test cases the model did generate. Long
 * prose throws for the same reason - a model that described its cases in
 * paragraphs produced work, and calling that "empty" would lose it quietly.
 */
final class LlmJsonArray {

    private static final Logger log = LoggerFactory.getLogger(LlmJsonArray.class);

    /**
     * Longest prose response still read as "nothing to generate". A refusal or
     * a one-line "none apply" is far shorter than this; anything longer had
     * something to say and is worth failing loudly over.
     */
    private static final int MAX_NOTHING_TO_GENERATE_CHARS = 1000;

    private LlmJsonArray() {
    }

    /**
     * @param what short plural noun for the log/exception text, e.g.
     *             "business test cases"
     * @return the parsed array, or an empty array when the model answered that
     *         it has nothing to generate
     * @throws IllegalStateException if the response held content that could not
     *         be parsed as a JSON array
     */
    static JsonNode parse(ObjectMapper mapper, String llmResponse, String what) {
        String cleaned = stripMarkdownFences(llmResponse == null ? "" : llmResponse);

        if (isNothingToGenerate(cleaned)) {
            log.info("LLM returned no {} for this call - treating as zero cases, not as a failure. Response: \"{}\"",
                    what, abbreviate(cleaned));
            return mapper.createArrayNode();
        }

        try {
            JsonNode node = mapper.readTree(cleaned);
            if (!node.isArray()) {
                throw new IllegalStateException(
                        "Expected a JSON array of " + what + " from the LLM but got: " + node.getNodeType());
            }
            return node;
        } catch (IOException e) {
            // Some models introduce the array ("Here are the test cases: [...]")
            // despite the prompt. The array itself is fine, so take it rather
            // than losing the whole category over the sentence around it.
            JsonNode embedded = parseEmbeddedArray(mapper, cleaned);
            if (embedded != null) {
                log.warn("LLM wrapped its {} array in extra prose - parsed the array and ignored the rest.", what);
                return embedded;
            }
            throw new IllegalStateException(
                    "Could not parse LLM response as a JSON array of " + what + ": " + e.getMessage()
                            + "\nRaw response:\n" + llmResponse, e);
        }
    }

    private static boolean isNothingToGenerate(String cleaned) {
        if (cleaned.isBlank()) {
            return true;
        }
        if (cleaned.indexOf('[') >= 0 || cleaned.indexOf('{') >= 0) {
            return false;
        }
        return cleaned.length() <= MAX_NOTHING_TO_GENERATE_CHARS;
    }

    private static JsonNode parseEmbeddedArray(ObjectMapper mapper, String cleaned) {
        int start = cleaned.indexOf('[');
        int end = cleaned.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(cleaned.substring(start, end + 1));
            return node.isArray() ? node : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    /** Defensive: some models wrap JSON in ```json ... ``` fences despite instructions. */
    static String stripMarkdownFences(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(json)?", "").trim();
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
            }
        }
        return trimmed;
    }

    private static String abbreviate(String text) {
        String oneLine = String.join(" ", text.lines().map(String::trim).filter(s -> !s.isEmpty()).toList());
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160) + "...";
    }
}
