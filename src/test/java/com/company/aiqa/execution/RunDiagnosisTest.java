package com.company.aiqa.execution;

import com.company.aiqa.model.HttpExchange;
import com.company.aiqa.model.TestExecutionResult;
import com.company.aiqa.model.TestExecutionSummary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fixtures here are the shape of a real mms run: 31 tests, every request a
 * 404 against a base URI that was not the service, plus a cleanup call that
 * sent a null id.
 */
class RunDiagnosisTest {

    private static HttpExchange call(String method, String uri, int status) {
        return new HttpExchange("Gen", "t", method, uri, Map.of(), null, false,
                status, "HTTP/1.1 " + status + " ", Map.of(), "", false, 5);
    }

    private static HttpExchange posted(String uri, String body, int status) {
        return new HttpExchange("Gen", "t", "POST", uri, Map.of(), body, false,
                status, "HTTP/1.1 " + status + " ", Map.of(), "", false, 5);
    }

    private static TestExecutionResult failed(String name, HttpExchange... calls) {
        return new TestExecutionResult("Gen", name, TestExecutionResult.Status.FAILED, 10,
                "expected 201 but was 404", "AssertionError", List.of(calls));
    }

    private static TestExecutionSummary summary(List<TestExecutionResult> results) {
        return new TestExecutionSummary("http://localhost:8080", 1, 1, List.of(),
                results.size(), 0, results.size(), 0, 0, "report", results);
    }

    /** The mms run: configured for the remote service, every request sent to localhost. */
    @Test
    void namesTheHostTheRequestsActuallyWentTo() {
        List<TestExecutionResult> results = List.of(
                failed("createFee", call("POST", "http://localhost:8080/api/v1/fees", 404)),
                failed("createMerchant", call("POST", "http://localhost:8080/api/v1/merchants", 404)));
        TestExecutionSummary s = new TestExecutionSummary("http://169.58.37.242:8007/mms", 1, 1, List.of(),
                2, 0, 2, 0, 0, "report", results);

        List<String> findings = RunDiagnosis.of(s);

        assertTrue(findings.get(0).contains("localhost:8080") && findings.get(0).contains("169.58.37.242"),
                findings.toString());
    }

    @Test
    void saysNothingAboutTheTargetWhenTheRequestsWentThere() {
        List<TestExecutionResult> results = List.of(
                new TestExecutionResult("Gen", "createsFee", TestExecutionResult.Status.PASSED, 10, null, null,
                        List.of(call("POST", "http://169.58.37.242:8007/mms/api/v1/fees", 201))));
        TestExecutionSummary s = new TestExecutionSummary("http://169.58.37.242:8007/mms", 1, 1, List.of(),
                1, 1, 0, 0, 0, "report", results);

        assertTrue(RunDiagnosis.of(s).stream().noneMatch(f -> f.contains("never contacted")), RunDiagnosis.of(s).toString());
    }

    @Test
    void namesAnApiTheBaseUriDoesNotServe() {
        List<TestExecutionResult> results = new ArrayList<>();
        results.add(failed("createFee", call("POST", "http://localhost:8080/api/v1/fees", 404)));
        results.add(failed("createMerchant", call("POST", "http://localhost:8080/api/v1/merchants", 404)));
        results.add(failed("createCapability", call("POST", "http://localhost:8080/api/v1/capabilities", 404)));
        results.add(failed("createQr", call("POST", "http://localhost:8080/api/v1/qr-codes", 404)));

        List<String> findings = RunDiagnosis.of(summary(results));

        assertTrue(findings.stream().anyMatch(f -> f.contains("returned 404") && f.contains("http://localhost:8080")),
                findings.toString());
    }

    @Test
    void separatesSetupFailuresFromRealVerdicts() {
        List<TestExecutionResult> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            results.add(failed("feeCase" + i, call("POST", "http://host/api/v1/merchants", 500)));
        }
        List<String> findings = RunDiagnosis.of(summary(results));

        assertTrue(findings.stream().anyMatch(f -> f.contains("never established their preconditions")),
                findings.toString());
    }

    /**
     * A test that built its fixture and then failed the call it exists to
     * verify is a real verdict - it must not be explained away as setup.
     */
    @Test
    void leavesGenuineAssertionFailuresAlone() {
        List<TestExecutionResult> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            results.add(failed("rejectsBadPrice" + i,
                    call("POST", "http://host/api/v1/merchants", 201),
                    call("POST", "http://host/api/v1/fees", 200)));
        }
        List<String> findings = RunDiagnosis.of(summary(results));

        assertTrue(findings.stream().noneMatch(f -> f.contains("never established their preconditions")),
                findings.toString());
    }

    /**
     * Deliberately absent ids are how a "not found" case is written, so a GET
     * or DELETE that 404s is the test working, not a broken precondition.
     */
    @Test
    void doesNotCallADeliberate404ASetupFailure() {
        List<TestExecutionResult> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            results.add(failed("rejectsUnknownId" + i, call("GET", "http://host/api/v1/fees/999999", 404)));
        }
        List<String> findings = RunDiagnosis.of(summary(results));

        assertTrue(findings.stream().noneMatch(f -> f.contains("never established their preconditions")),
                findings.toString());
    }

    @Test
    void reportsAnIdThatReachedTheWireAsNull() {
        List<TestExecutionResult> results = List.of(
                failed("cleanup", call("DELETE", "http://host/api/v1/fees/null-fee", 404)),
                failed("update", call("PUT", "http://host/api/v1/merchants/null", 404)));

        List<String> findings = RunDiagnosis.of(summary(results));

        assertTrue(findings.stream().anyMatch(f -> f.contains("null id in the path")), findings.toString());
    }

    @Test
    void saysNothingAboutAHealthyRun() {
        List<TestExecutionResult> results = List.of(
                new TestExecutionResult("Gen", "createsFee", TestExecutionResult.Status.PASSED, 10, null, null,
                        List.of(call("POST", "http://host/api/v1/merchants", 201),
                                call("POST", "http://host/api/v1/fees", 201))));

        assertEquals(List.of(), RunDiagnosis.of(new TestExecutionSummary("http://host", 1, 1, List.of(),
                1, 1, 0, 0, 0, "report", results)));
    }

    /**
     * The finding this class most needed. Ten mms tests posted
     * {"merchantId": "null"} - an unset id formatted into a text block - got a
     * 500 from a service correctly refusing a merchant that cannot exist, and
     * the 500s were written up as a defect in mms.
     */
    @Test
    void namesTheFieldThatCarriedTheTextNull() {
        String body = """
                {
                  "merchantId" : "null",
                  "capabilityCode" : "ONLINE_PAYMENT"
                }
                """;
        List<TestExecutionResult> results = List.of(
                failed("createsFee", posted("http://169.58.37.242:8007/mms/api/v1/fees", body, 500)));
        TestExecutionSummary s = new TestExecutionSummary("http://169.58.37.242:8007/mms", 1, 1, List.of(),
                1, 0, 1, 0, 0, "report", results);

        List<String> findings = RunDiagnosis.of(s);

        assertTrue(findings.stream().anyMatch(f -> f.contains("merchantId") && f.contains("createsFee")),
                findings.toString());
        assertTrue(findings.stream().noneMatch(f -> f.contains("capabilityCode")), findings.toString());
    }

    /**
     * A bare JSON null is the whole point of a "required field missing" case.
     * Flagging it would turn the suite's correct negative tests into warnings.
     */
    @Test
    void saysNothingAboutAJsonNullTheCaseMeantToSend() {
        String body = """
                {
                  "qrType" : null,
                  "merchantId" : null
                }
                """;
        List<TestExecutionResult> results = List.of(
                failed("rejectsMissingQrType", posted("http://169.58.37.242:8007/mms/api/v1/qr-codes", body, 400)));
        TestExecutionSummary s = new TestExecutionSummary("http://169.58.37.242:8007/mms", 1, 1, List.of(),
                1, 0, 1, 0, 0, "report", results);

        assertTrue(RunDiagnosis.of(s).stream().noneMatch(f -> f.contains("the text \"null\"")),
                RunDiagnosis.of(s).toString());
    }
}
