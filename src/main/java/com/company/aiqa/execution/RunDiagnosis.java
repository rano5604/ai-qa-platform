package com.company.aiqa.execution;

import com.company.aiqa.model.HttpExchange;
import com.company.aiqa.model.TestExecutionResult;
import com.company.aiqa.model.TestExecutionSummary;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.TreeMap;

/**
 * Reads a finished run and says what it actually proves - which is sometimes
 * nothing.
 *
 * <p>A report of thirty red tests looks like thirty defects. Often it is one
 * fact repeated thirty times: the base URI wasn't the service, or every test
 * died on the call that was supposed to create its fixture. Those two are
 * indistinguishable from a genuine failure when each test is rendered on its
 * own - the failing exchange is a 404 either way - and a reviewer who cannot
 * tell them apart cannot use the report at all.
 *
 * <p>Every finding here is derived from the captured exchanges, never guessed:
 * how far each test got, what came back, and whether an id reached the wire as
 * the literal "null".
 */
public final class RunDiagnosis {

    /** {@code "merchantId" : "null"} - the field name, and the quoted word null as its value. */
    private static final Pattern NULL_STRING_VALUE =
            Pattern.compile("\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*:\\s*\"null\"");

    /** Below this many failing tests, a pattern isn't a pattern. */
    private static final int MIN_TESTS_FOR_PATTERN = 3;

    /** Share of failing tests that must share a symptom before it is reported as systemic. */
    private static final double PATTERN_SHARE = 0.6;

    private RunDiagnosis() {
    }

    /**
     * Findings worth putting in front of a reader, most significant first.
     * Empty when the run reads normally - a clean run must not be decorated
     * with warnings.
     */
    public static List<String> of(TestExecutionSummary summary) {
        List<String> findings = new ArrayList<>();
        List<TestExecutionResult> failing = summary.results().stream()
                .filter(r -> r.status() == TestExecutionResult.Status.FAILED
                        || r.status() == TestExecutionResult.Status.ERROR)
                // A setup failure is not a failing test, and counting it as one
                // makes "1 of 1 failing tests died on its first call" out of a
                // run whose only real event was the setup.
                .filter(r -> !r.isSetupFailure())
                .toList();

        addSetupFailure(findings, summary);
        addWrongTarget(findings, summary);
        addUnservedApi(findings, summary);
        addAuthWall(findings, summary);
        addRoleOrOwnershipDenied(findings, failing);
        addFailedDuringSetup(findings, failing);
        addNullIdInPath(findings, summary);
        addNullIdInBody(findings, summary);
        addPassedWithoutHttpCall(findings, summary);
        return findings;
    }

    /**
     * A {@code @BeforeClass} failed, so TestNG skipped every test in the class.
     *
     * <p>This is the most consequential thing that can happen to a run and the
     * report had no words for it. What a reader saw instead was "34 tests made
     * no HTTP calls - usually a setup step, a wrong base URI, or the target
     * service not running", which points at three causes, two of which were
     * flatly untrue: the setup had reached the target and been answered. The
     * one call that actually needs fixing was two screens up, indistinguishable
     * from the noise.
     *
     * <p>So: say that the setup failed, say which call it died on, and say that
     * the tests below did not run. A skipped test is not a finding, and 34 of
     * them are not 34 findings.
     */
    private static void addSetupFailure(List<String> findings, TestExecutionSummary summary) {
        Optional<TestExecutionResult> setup = summary.results().stream()
                .filter(TestExecutionResult::isSetupFailure)
                .findFirst();
        if (setup.isEmpty()) {
            return;
        }
        long skipped = summary.results().stream()
                .filter(r -> r.status() == TestExecutionResult.Status.SKIPPED)
                .count();

        StringBuilder finding = new StringBuilder("The class setup failed, so TestNG skipped ");
        finding.append(skipped == 0 ? "the tests that depend on it" : skipped + " test(s)")
                .append(" - they never ran, made no calls, and say nothing about the rules in their names. ")
                .append("Fix the setup and the suite runs; until then there is exactly one failure here, not ")
                .append(skipped == 0 ? "several" : skipped + 1 + "").append(".");

        // The call it died on is the actionable part. It is the LAST one the
        // setup made: everything before it succeeded.
        List<HttpExchange> calls = setup.get().exchanges();
        if (!calls.isEmpty()) {
            HttpExchange last = calls.get(calls.size() - 1);
            finding.append(" The setup got as far as ").append(last.requestMethod()).append(" ")
                    .append(path(last.requestUri())).append(" -> ").append(last.responseStatusCode())
                    .append(calls.size() > 1
                            ? ", after " + (calls.size() - 1) + " earlier call(s) that succeeded."
                            : ", its first call.");
        } else {
            finding.append(" It made no HTTP calls at all, so it broke before reaching the network.");
        }
        findings.add(finding.toString());
    }

    /**
     * The run was pointed at one host and the requests went to another. That
     * happens when the script sets its own base URI - or sets none, in which
     * case REST Assured silently uses http://localhost:8080 and the target is
     * never contacted at all. Every verdict below it is then meaningless, so
     * this is reported before anything else.
     */
    private static void addWrongTarget(List<String> findings, TestExecutionSummary summary) {
        String configured = host(summary.baseUri());
        if (configured.isEmpty()) {
            return;
        }
        Set<String> actual = new LinkedHashSet<>();
        summary.results().forEach(r -> r.exchanges().forEach(x -> actual.add(host(x.requestUri()))));
        actual.remove("");
        if (actual.isEmpty() || actual.contains(configured)) {
            return;
        }
        findings.add(("Every request went to %s, but this run was configured for %s - the target was never "
                + "contacted. The script is setting its own base URI, or setting none at all, in which case "
                + "REST Assured falls back to http://localhost:8080. Nothing below reflects the service you "
                + "meant to test.")
                .formatted(String.join(", ", actual), summary.baseUri()));
    }

    private static String host(String uri) {
        if (uri == null) {
            return "";
        }
        try {
            URI parsed = URI.create(uri);
            return parsed.getHost() == null ? "" : parsed.getHost() + (parsed.getPort() < 0 ? "" : ":" + parsed.getPort());
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /**
     * The whole API answering 404 is an environment fact, not a test result:
     * the base URI points at something that does not serve these paths - a
     * different service, a missing context path, a stopped container.
     */
    private static void addUnservedApi(List<String> findings, TestExecutionSummary summary) {
        List<HttpExchange> all = summary.results().stream().flatMap(r -> r.exchanges().stream()).toList();
        if (all.size() < MIN_TESTS_FOR_PATTERN) {
            return;
        }
        long notFound = all.stream().filter(x -> x.responseStatusCode() == 404).count();
        Set<String> paths = new LinkedHashSet<>();
        all.forEach(x -> paths.add(path(x.requestUri())));
        if (notFound == all.size() && paths.size() >= MIN_TESTS_FOR_PATTERN) {
            findings.add(("Every one of the %d request(s) in this run returned 404, across %d different path(s). "
                    + "That is the base URI (%s) not serving this API at all - a wrong host or port, a missing "
                    + "context path, or the service not running. Nothing in this report reflects the application "
                    + "under test.")
                    .formatted(all.size(), paths.size(), summary.baseUri()));
        }
    }

    /**
     * Most of the run's traffic coming back 401/403 is an environment fact, not
     * a run of failing tests: the target requires credentials these requests
     * don't carry. The generator is deliberately barred from inventing auth it
     * can't see in the diff, so against an auth-gated service every test - and
     * every precondition create the other tests depend on - answers 401, and the
     * report reads as hundreds of defects that are all one missing token.
     *
     * <p>Unlike the 404 wall this does NOT require every request to be affected:
     * some endpoints may be public and answer 2xx while the rest 401, and the
     * finding is still the same actionable fact. It fires on a dominant share, and
     * names the fix - supply {@code authHeaders} on the execute request - because
     * the reader otherwise has no way to know the platform can inject them.
     */
    private static void addAuthWall(List<String> findings, TestExecutionSummary summary) {
        List<HttpExchange> all = summary.results().stream().flatMap(r -> r.exchanges().stream()).toList();
        if (all.size() < MIN_TESTS_FOR_PATTERN) {
            return;
        }
        // A missing-credentials wall is a 401, or a 403 on a request that carried
        // NO token at all. A 403 while carrying a token is a different problem -
        // authenticated but wrong role/ownership - reported separately by
        // addRoleOrOwnershipDenied, so the two aren't conflated into one message
        // that sends the reader to supply a token they already supplied.
        long denied = all.stream()
                .filter(x -> x.responseStatusCode() == 401
                        || (x.responseStatusCode() == 403 && !carriedAuth(x)))
                .count();
        if (denied < MIN_TESTS_FOR_PATTERN || denied < all.size() * PATTERN_SHARE) {
            return;
        }
        findings.add(("%d of %d request(s) in this run were refused with 401/403 and carried no token - the "
                + "target requires authentication these tests do not carry, and the generator does not invent "
                + "credentials it cannot see in the diff. Supply them at execution via authHeaders (or a login "
                + "block) on the request (e.g. {\"Authorization\": \"Bearer <token>\"}), which is put on every "
                + "request that doesn't set its own; every precondition create is refused the same way, so the "
                + "tests depending on those ids fail in turn. Until a token is supplied, these verdicts reflect "
                + "the auth wall, not the endpoints.")
                .formatted(denied, all.size()));
    }

    /**
     * A request answered 403 Forbidden while CARRYING a token is not a missing-auth
     * problem - the caller is authenticated but lacks the ROLE or resource
     * OWNERSHIP the endpoint requires. This is an authorization precondition the
     * test didn't establish, not a defect in the target.
     *
     * <p>The canonical shape (TailorBookApp, but the pattern is general): only an
     * admin user is seeded, admin can create areas and shops, but writing an
     * order requires a SHOP_OWNER token whose shop owns the resource. A run that
     * authenticates as admin and reuses that one token everywhere is correctly
     * refused on every owner-only endpoint - the fix is for the test to act as
     * the required role (provision/log in as that actor first), not to reuse the
     * admin token.
     *
     * <p>Scoped to FAILING tests only, and to 403s that carried a token: a
     * negative test that deliberately asserts a wrong-role/wrong-shop token
     * yields 403 PASSES, and must never be flagged as a problem - that assertion
     * is the point of the test.
     */
    private static void addRoleOrOwnershipDenied(List<String> findings, List<TestExecutionResult> failing) {
        java.util.LinkedHashSet<String> endpoints = new java.util.LinkedHashSet<>();
        int count = 0;
        for (TestExecutionResult result : failing) {
            for (HttpExchange x : result.exchanges()) {
                if (x.responseStatusCode() == 403 && carriedAuth(x)) {
                    endpoints.add(x.requestMethod() + " " + path(x.requestUri()));
                    count++;
                }
            }
        }
        if (count == 0) {
            return;
        }
        String sample = endpoints.stream().limit(6).reduce((a, b) -> a + ", " + b).orElse("");
        String more = endpoints.size() > 6 ? " (+" + (endpoints.size() - 6) + " more)" : "";
        findings.add(("%d request(s) in failing test(s) were refused 403 Forbidden WHILE carrying a token - the "
                + "caller is authenticated but lacks the role or resource-ownership the endpoint requires, an "
                + "authorization precondition the test did not establish (not a target defect). Affected: %s%s. "
                + "Reusing one identity - typically the seeded admin - for endpoints that require a different "
                + "role (e.g. a SHOP_OWNER whose shop owns the resource) is refused by design. To exercise these, "
                + "the test must act AS the required role: create/provision that actor first and log in as it, "
                + "then send the request with that actor's token, rather than the admin token.")
                .formatted(count, sample, more));
    }

    /**
     * Whether the request carried an Authorization header - the header KEY
     * survives report redaction even though its value is masked, so token
     * PRESENCE is always detectable. Distinguishes "no credentials" (401/403 with
     * no token) from "wrong role" (403 with a token).
     */
    private static boolean carriedAuth(HttpExchange x) {
        if (x.requestHeaders() == null) {
            return false;
        }
        return x.requestHeaders().entrySet().stream()
                .anyMatch(e -> "authorization".equalsIgnoreCase(e.getKey())
                        && e.getValue() != null && !e.getValue().isBlank());
    }

    /**
     * A test reported PASSED that made no HTTP call at all asserted nothing
     * against the target - it is not evidence any endpoint works. This happens
     * when a test's only assertions are local, or its body was reduced until no
     * request survived: a green verdict with an empty exchange list reads as
     * coverage the run does not actually have. Reported so a reader doesn't count
     * it as a passing endpoint. A skipped or failed test making no call is
     * covered by the setup findings above; this is specifically the misleading
     * GREEN one.
     */
    private static void addPassedWithoutHttpCall(List<String> findings, TestExecutionSummary summary) {
        List<String> offenders = summary.results().stream()
                .filter(r -> r.status() == TestExecutionResult.Status.PASSED)
                .filter(r -> r.exchanges().isEmpty())
                .map(TestExecutionResult::testMethodName)
                .toList();
        if (offenders.isEmpty()) {
            return;
        }
        findings.add(("%d test(s) passed without making a single HTTP call, so they prove nothing about the "
                + "target - a green verdict here is not a working endpoint, only a test that never exercised "
                + "one: %s")
                .formatted(offenders.size(), String.join("; ", offenders)));
    }

    /**
     * A test that fails on its first call never reached the behaviour it is
     * named after - it failed building its precondition. Reported as a group
     * because one missing prerequisite takes down every test that needs it,
     * and thirty such lines read like thirty unrelated defects.
     */
    private static void addFailedDuringSetup(List<String> findings, List<TestExecutionResult> failing) {
        if (failing.size() < MIN_TESTS_FOR_PATTERN) {
            return;
        }
        List<TestExecutionResult> diedFirstCall = failing.stream()
                .filter(r -> !r.exchanges().isEmpty())
                .filter(r -> r.exchanges().size() == 1)
                .filter(r -> r.exchanges().get(0).responseStatusCode() >= 400)
                .filter(r -> isWrite(r.exchanges().get(0)))
                .toList();

        if (diedFirstCall.size() < MIN_TESTS_FOR_PATTERN
                || diedFirstCall.size() < failing.size() * PATTERN_SHARE) {
            return;
        }

        Map<String, Integer> byPath = new TreeMap<>();
        for (TestExecutionResult r : diedFirstCall) {
            HttpExchange x = r.exchanges().get(0);
            byPath.merge(x.requestMethod() + " " + path(x.requestUri()) + " -> " + x.responseStatusCode(), 1, Integer::sum);
        }
        String breakdown = byPath.entrySet().stream()
                .map(e -> e.getKey() + " (x" + e.getValue() + ")")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

        findings.add(("%d of %d failing test(s) stopped on their very first call, which was a create/update, "
                + "not the request they exist to verify. Those tests never established their preconditions, "
                + "so they say nothing about the rules in their names - fix the setup before reading them as "
                + "defects: %s")
                .formatted(diedFirstCall.size(), failing.size(), breakdown));
    }

    /**
     * "/fees/null-fee" or "/merchants/null" reached the wire because an id
     * variable was null when the path was built - the create that should have
     * produced it never ran, or never returned what the test read. The server's
     * 404 is correct and tells the reader nothing.
     */
    private static void addNullIdInPath(List<String> findings, TestExecutionSummary summary) {
        Set<String> offenders = new LinkedHashSet<>();
        for (TestExecutionResult r : summary.results()) {
            for (HttpExchange x : r.exchanges()) {
                if (hasNullSegment(path(x.requestUri()))) {
                    offenders.add(r.testMethodName() + ": " + x.requestMethod() + " " + path(x.requestUri()));
                }
            }
        }
        if (offenders.isEmpty()) {
            return;
        }
        findings.add(("%d request(s) were sent with a null id in the path, so the value a precondition was "
                + "supposed to produce never existed: %s")
                .formatted(offenders.size(), String.join("; ", offenders)));
    }

    /**
     * A request body carrying the four characters {@code "null"} as a STRING
     * value: {@code {"merchantId": "null"}}. Nothing writes that on purpose. It
     * is {@code String.valueOf(null)} formatted into a text block because the id
     * the payload needed was never produced, and the target - given a merchant
     * id that cannot exist - answers 500 or 400 exactly as it should.
     *
     * <p>This is the finding this class most needed and did not have. A run
     * posted it ten times, collected ten 500s, and those were written up as a
     * defect in the service. They were a defect in the fixture.
     *
     * <p>Only the QUOTED form counts. A bare JSON {@code null} is a legitimate
     * payload for a "required field missing" case and must never be flagged.
     */
    private static void addNullIdInBody(List<String> findings, TestExecutionSummary summary) {
        Set<String> offenders = new LinkedHashSet<>();
        Set<String> fields = new LinkedHashSet<>();
        for (TestExecutionResult r : summary.results()) {
            for (HttpExchange x : r.exchanges()) {
                for (String field : nullStringFields(x.requestBody())) {
                    fields.add(field);
                    offenders.add(r.testMethodName());
                }
            }
        }
        if (offenders.isEmpty()) {
            return;
        }
        findings.add(("%d test(s) sent the text \"null\" as the value of %s, which is what an unset id looks "
                + "like once it is formatted into a payload. The target is answering a request for something "
                + "that cannot exist, so whatever status it returned is not evidence of a defect: %s")
                .formatted(offenders.size(), String.join(", ", fields), String.join("; ", offenders)));
    }

    /** Field names whose value is the string "null" - see {@link #addNullIdInBody}. */
    private static Set<String> nullStringFields(String body) {
        if (body == null || !body.contains("\"null\"")) {
            return Set.of();
        }
        Set<String> fields = new LinkedHashSet<>();
        Matcher m = NULL_STRING_VALUE.matcher(body);
        while (m.find()) {
            fields.add(m.group(1));
        }
        return fields;
    }

    /** A call that creates or changes state - the shape a fixture step takes. */
    private static boolean isWrite(HttpExchange x) {
        String method = x.requestMethod() == null ? "" : x.requestMethod().toUpperCase();
        return method.equals("POST") || method.equals("PUT") || method.equals("PATCH");
    }

    /** Matches "null" as a whole segment and inside one ("null-fee"), which is how a formatted id arrives. */
    private static boolean hasNullSegment(String path) {
        for (String segment : path.split("/")) {
            if (segment.equals("null") || segment.startsWith("null-") || segment.endsWith("-null")) {
                return true;
            }
        }
        return false;
    }

    private static String path(String uri) {
        if (uri == null) {
            return "";
        }
        try {
            String p = URI.create(uri).getPath();
            return p == null ? uri : p;
        } catch (IllegalArgumentException e) {
            return uri;
        }
    }
}
