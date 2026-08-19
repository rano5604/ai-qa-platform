package com.company.aiqa.execution;

import com.company.aiqa.model.HttpExchange;
import com.company.aiqa.model.TestExecutionResult;
import com.company.aiqa.model.TestExecutionSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 2026-08-19 12:31 mms run: a {@code @BeforeClass} created a merchant (201)
 * then tried to approve it (422, "Cannot transition merchant from PENDING to
 * APPROVED"), so TestNG skipped all 34 tests.
 *
 * <p>The report said those 34 "failed before reaching the network - usually a
 * setup step, a wrong base URI, or the target service not running". Two of
 * those three causes were contradicted by exchanges printed in the same report,
 * and the reader's first question was why a setup step could fail when they
 * could see requests reaching the server. These tests exist so that question
 * cannot be asked again.
 */
class SetupFailureReportTest {

    private static HttpExchange call(String method, String uri, int status) {
        return new HttpExchange("Gen", "setup", method, uri, Map.of(), null, false,
                status, "HTTP/1.1 " + status + " ", Map.of(), "", false, 500);
    }

    /** The setup got through a create and died on the approve. */
    private static TestExecutionResult deadSetup() {
        return new TestExecutionResult("Gen", "setup", TestExecutionResult.Status.ERROR, 2297,
                TestExecutionResult.setupFailureMessage("setup",
                        "1 expectation failed.\nExpected status code (is <200> or is <201>) but was <422>."),
                "java.lang.AssertionError",
                List.of(call("POST", "http://target/mms/api/v1/merchants", 201),
                        call("POST", "http://target/mms/api/v1/merchants/97e3/approve", 422)));
    }

    private static TestExecutionSummary mmsRun(int skippedCount) {
        List<TestExecutionResult> results = new ArrayList<>();
        results.add(deadSetup());
        for (int i = 0; i < skippedCount; i++) {
            results.add(new TestExecutionResult("Gen", "someTest" + i,
                    TestExecutionResult.Status.SKIPPED, 0,
                    "1 expectation failed.\nExpected status code (is <200> or is <201>) but was <422>.",
                    "java.lang.AssertionError", List.of()));
        }
        return new TestExecutionSummary("http://target/mms", 1, 1, List.of(),
                results.size(), 0, 0, 1, skippedCount, "report", results);
    }

    @Test
    void namesTheSetupTheSkipCountAndTheCallItDiedOn() {
        List<String> findings = RunDiagnosis.of(mmsRun(34));

        String first = findings.get(0);
        assertTrue(first.contains("class setup failed"), first);
        assertTrue(first.contains("34 test(s)"), first);
        assertTrue(first.contains("/mms/api/v1/merchants/97e3/approve"), first);
        assertTrue(first.contains("422"), first);
        assertTrue(first.contains("1 earlier call(s) that succeeded"), first);
    }

    /** It has to be read before anything else - the rest of the report is moot. */
    @Test
    void theSetupFindingComesFirst() {
        assertTrue(RunDiagnosis.of(mmsRun(34)).get(0).contains("class setup failed"));
    }

    /**
     * A setup failure is not a failing test. Counted as one it produced
     * "1 of 1 failing test(s) stopped on their very first call" out of a run
     * whose only event was the setup.
     */
    @Test
    void theSetupIsNotCountedAsAFailingTest() {
        List<String> findings = RunDiagnosis.of(mmsRun(34));

        assertTrue(findings.stream().noneMatch(f -> f.contains("stopped on their very first call")),
                findings.toString());
    }

    @Test
    void saysNothingAboutSetupOnARunThatHadNone() {
        TestExecutionSummary clean = new TestExecutionSummary("http://target/mms", 1, 1, List.of(),
                1, 1, 0, 0, 0, "report",
                List.of(new TestExecutionResult("Gen", "createsFee", TestExecutionResult.Status.PASSED,
                        10, null, null, List.of(call("POST", "http://target/mms/api/v1/fees", 201)))));

        assertTrue(RunDiagnosis.of(clean).stream().noneMatch(f -> f.contains("class setup failed")),
                RunDiagnosis.of(clean).toString());
    }

    /** The banner must not offer causes the report's own exchanges disprove. */
    @Test
    void theBannerBlamesTheSetupRatherThanTheTarget(@TempDir Path dir) throws Exception {
        Path written = new ExecutionReportWriter().write(mmsRun(34), dir, "mms", "3836dee");
        assertNotNull(written);
        String html = Files.readString(written);

        assertTrue(html.contains("because the class setup failed before them"), "banner not updated");
        assertFalse(html.contains("or the target service not running"),
                "the report still offers a cause its own exchanges disprove");
    }

    /**
     * TestNG copies the configuration exception onto every skipped test, so the
     * old report rendered 34 identical assertion blocks and each test read as
     * though it had asserted something itself.
     */
    @Test
    void aSkippedTestDoesNotWearTheSetupsAssertion(@TempDir Path dir) throws Exception {
        String html = Files.readString(new ExecutionReportWriter().write(mmsRun(3), dir, "mms", "3836dee"));

        assertTrue(html.contains("Never ran - the class setup failed"), html.substring(0, 200));
        // Once for the setup's own failure block, and not once per skipped test.
        assertTrue(count(html, "Expected status code (is &lt;200&gt; or is &lt;201&gt;) but was &lt;422&gt;.") == 1,
                "the setup's assertion is repeated on the skipped tests");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
