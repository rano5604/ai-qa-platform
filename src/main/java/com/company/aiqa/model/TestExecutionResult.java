package com.company.aiqa.model;

import java.util.List;

/**
 * One test METHOD's outcome from running compiled automation - parsed from
 * TestNG's {@code testng-results.xml}.
 *
 * <p>{@link #exchanges} carries every HTTP request and response that method
 * made, which is what turns a bare pass/fail into something a QA engineer can
 * triage without re-running anything by hand.
 */
public record TestExecutionResult(
        String testClassName,
        String testMethodName,
        Status status,
        long durationMillis,

        /** Assertion/exception message; null when PASSED. */
        String failureMessage,

        /** Exception type behind a FAILED/ERROR result; null when PASSED. */
        String failureType,

        /**
         * Every HTTP call this test made, in order. Empty when the test made
         * none - which for an API test is itself a finding, usually meaning it
         * failed in setup before reaching the wire.
         */
        List<HttpExchange> exchanges
) {
    /**
     * <p>TestNG itself reports only PASS/FAIL/SKIP. The split between FAILED
     * and ERROR is this platform's: a failed ASSERTION means the API answered
     * and the answer was wrong (a real test failure, and a likely defect),
     * whereas any other exception - connection refused, a malformed URI, a
     * setup method blowing up - means the test never got to judge anything.
     * Conflating them sends people hunting for a bug when the target simply
     * wasn't running.
     */
    public enum Status { PASSED, FAILED, ERROR, SKIPPED }
}
