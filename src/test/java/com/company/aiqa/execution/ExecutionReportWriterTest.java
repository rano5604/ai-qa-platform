package com.company.aiqa.execution;

import com.company.aiqa.model.HttpExchange;
import com.company.aiqa.model.TestExecutionResult;
import com.company.aiqa.model.TestExecutionSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The report carries every captured request back into the page as JSON, so the
 * reader can re-send it - edited or as captured. That makes the payload of a
 * service under test into page content, and a payload is arbitrary text.
 */
class ExecutionReportWriterTest {

    private static final Pattern SEND_REQ =
            Pattern.compile("class=\"send-req\">(.*?)</script>", Pattern.DOTALL);

    private final ObjectMapper mapper = new ObjectMapper();

    private static String report(Path dir, String requestBody) {
        HttpExchange exchange = new HttpExchange("Gen", "createsFee", "POST",
                "http://target/api/v1/fees",
                Map.of("Content-Type", "application/json"), requestBody, false,
                500, "HTTP/1.1 500 ", Map.of(), "{}", false, 12);
        TestExecutionResult failed = new TestExecutionResult("Gen", "createsFee",
                TestExecutionResult.Status.FAILED, 30, "expected 201 but was 500", "AssertionError",
                List.of(exchange));
        TestExecutionSummary summary = new TestExecutionSummary("http://target", 1, 1, List.of(),
                1, 0, 1, 0, 0, "report", List.of(failed));

        Path written = new ExecutionReportWriter("http://localhost:8080/api/v1/replay", "tok")
                .write(summary, dir, "mms", "abc123");
        assertNotNull(written, "the report was not written");
        try {
            return Files.readString(written);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private String sendReqBlock(String html) {
        Matcher m = SEND_REQ.matcher(html);
        assertTrue(m.find(), "no send-req block in the report");
        return m.group(1);
    }

    /**
     * The bug this exists for: the escape was written {@code replace("<", "<")}
     * with a single backslash, which the compiler's unicode preprocessing turns
     * into {@code replace("<", "<")} before the string literal exists. It
     * compiled, it read exactly like the fix, and it did nothing - so a payload
     * containing {@code </script>} closed the block early.
     */
    @Test
    void aBodyContainingAClosingScriptTagCannotCloseTheJsonBlock(@TempDir Path dir) {
        String html = report(dir, "{\"note\":\"</script><img src=x onerror=alert(1)>\"}");

        String block = sendReqBlock(html);
        assertFalse(block.contains("<"), "an unescaped '<' ends the block early: " + block);
        assertTrue(block.contains("\\u003c"), "expected the escaped form in: " + block);
        assertFalse(html.contains("<img src=x onerror=alert(1)>"),
                "the payload reached the page as markup");
    }

    /** Escaped for the HTML parser, but still the original bytes once parsed. */
    @Test
    void theEscapedJsonStillParsesBackToTheCapturedRequest(@TempDir Path dir) throws Exception {
        String body = "{\"note\":\"</script> it's <b>bold</b> & \\\"quoted\\\"\"}";
        JsonNode parsed = mapper.readTree(sendReqBlock(report(dir, body)));

        assertEquals(body, parsed.path("body").asText());
        assertEquals("POST", parsed.path("method").asText());
        assertEquals("http://target/api/v1/fees", parsed.path("uri").asText());
    }

    /**
     * Every selector the page script queries has to exist, or the panel binds
     * nothing and the buttons are decoration.
     */
    @Test
    void theEditorCarriesEveryControlTheScriptLooksFor(@TempDir Path dir) {
        String html = report(dir, "{}");

        for (String cls : List.of("send-btn", "edit-btn", "reset-btn", "send-edit", "send-out",
                "send-req", "edit-method", "edit-uri", "edit-headers", "edit-body")) {
            assertTrue(html.contains("class=\"" + cls + "\"")
                            || html.contains("class=\"" + cls + "\" "),
                    "the script queries ." + cls + " but the markup has no such element");
        }
        assertTrue(html.contains("<div class=\"send-edit\" hidden>"),
                "the editor should start collapsed");
    }

    /**
     * A redacted credential must not travel to the page at all. It would be sent
     * literally as the text "&lt;redacted&gt;", which is worse than sending
     * nothing: the call fails on authentication and reads as a defect.
     */
    @Test
    void aRedactedHeaderIsNotHandedToThePage(@TempDir Path dir) throws Exception {
        HttpExchange exchange = new HttpExchange("Gen", "createsFee", "POST", "http://target/api/v1/fees",
                Map.of("Authorization", "<redacted>", "Content-Type", "application/json"),
                "{}", false, 401, "HTTP/1.1 401 ", Map.of(), "{}", false, 5);
        TestExecutionResult failed = new TestExecutionResult("Gen", "createsFee",
                TestExecutionResult.Status.FAILED, 30, "401", "AssertionError", List.of(exchange));
        TestExecutionSummary summary = new TestExecutionSummary("http://target", 1, 1, List.of(),
                1, 0, 1, 0, 0, "report", List.of(failed));

        Path written = new ExecutionReportWriter("http://localhost:8080/api/v1/replay", "tok")
                .write(summary, dir, "mms", "abc123");
        JsonNode headers = mapper.readTree(sendReqBlock(Files.readString(written))).path("headers");

        assertFalse(headers.has("Authorization"), "the redacted header was handed to the page");
        assertTrue(headers.has("Content-Type"));
    }
}
