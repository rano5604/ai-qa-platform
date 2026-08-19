package com.company.aiqa.testcase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The behaviour these cover is the difference between a run reporting 100%
 * coverage and the same run reporting PARTIAL and retrying a category forever,
 * so each case here is one shape a real provider response arrived in.
 */
class LlmJsonArrayTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode parse(String response) {
        return LlmJsonArray.parse(mapper, response, "business test cases");
    }

    @Test
    void parsesAPlainArray() {
        JsonNode node = parse("[{\"scenario\":\"Add two positive numbers\"}]");
        assertTrue(node.isArray());
        assertEquals(1, node.size());
    }

    @Test
    void parsesAFencedArray() {
        JsonNode node = parse("```json\n[{\"scenario\":\"x\"}]\n```");
        assertEquals(1, node.size());
    }

    @Test
    void emptyArrayIsEmptyNotAFailure() {
        assertEquals(0, parse("[]").size());
    }

    @Test
    void proseSayingNothingAppliesIsEmptyNotAFailure() {
        assertEquals(0, parse("No security test cases apply to this change.").size());
    }

    @Test
    void blankResponseIsEmptyNotAFailure() {
        assertEquals(0, parse("   \n ").size());
        assertEquals(0, parse(null).size());
    }

    @Test
    void arrayWrappedInProseIsSalvaged() {
        JsonNode node = parse("Here are the test cases:\n[{\"scenario\":\"x\"},{\"scenario\":\"y\"}]\nLet me know if you need more.");
        assertEquals(2, node.size());
    }

    @Test
    void truncatedArrayStillFails() {
        // Real content the model did produce - swallowing this as "empty" would
        // drop test cases and record the category as covered.
        assertThrows(IllegalStateException.class,
                () -> parse("[{\"scenario\":\"Add two positive numbe"));
    }

    @Test
    void singleObjectStillFails() {
        assertThrows(IllegalStateException.class, () -> parse("{\"scenario\":\"x\"}"));
    }

    @Test
    void longProseWithNoJsonStillFails() {
        assertThrows(IllegalStateException.class, () -> parse("The test cases are described below. ".repeat(60)));
    }
}
