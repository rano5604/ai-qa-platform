package com.company.aiqa.ai;

import com.company.aiqa.model.ApiEndpointInfo;
import com.company.aiqa.model.ChangedFile;
import com.company.aiqa.model.ClassInfo;
import com.company.aiqa.model.ConfigType;
import com.company.aiqa.model.ImpactResult;
import com.company.aiqa.model.ManualTestCase;
import com.company.aiqa.model.MethodInfo;
import com.company.aiqa.model.SourceLanguage;
import com.company.aiqa.model.TypeSchema;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Step: "Send Context to LLM".
 *
 * Builds two different kinds of prompts from the same structured pipeline
 * output (diffs, parsed source units, impact radius):
 *   - business/functional test cases for a QA tester to execute by hand
 *     (what a feature should do), independent of any code
 *   - automated test source files (JUnit/Jest/pytest/etc.), matched to
 *     each file's actual language
 * Both are constrained to JSON so the corresponding generator can parse
 * the response deterministically.
 */
@Component
public class PromptBuilder {

    // ---------------------------------------------------------------
    // Business / functional test cases (for QA to execute manually)
    // ---------------------------------------------------------------

    /**
     * One dedicated prompt per test category, called separately and merged
     * by the caller (see QaPipelineService). A single "cover positive,
     * negative, and boundary cases" prompt reliably front-loads a couple of
     * happy-path cases and tapers off - it's never forced to commit to full
     * coverage of any one dimension. Calling once per category with
     * category-specific guidance is what actually guarantees representation
     * instead of hoping the model balances it on its own.
     *
     * @param apiOnly true when project.ProjectShapeAnalyzer determined this
     *                project exposes REST endpoints and has no UI - steps
     *                then describe calling the API directly (HTTP method +
     *                endpoint path + example JSON payload, from the "##
     *                API endpoints" context section) rather than the default
     *                UI-click-through wording, since there's no screen for a
     *                manual tester to walk through.
     */
    public String businessTestCaseSystemPrompt(String category, boolean apiOnly) {
        return businessTestCaseSystemPrompt(List.of(category), apiOnly);
    }

    /**
     * Multi-category variant used when aiqa.pipeline.categories-per-call &gt; 1:
     * asks for several categories in ONE call, halving (or better) the number
     * of LLM round-trips, since each call otherwise re-sends the identical
     * diff/impact/existing-case context.
     *
     * <p>The tradeoff is real and deliberate: a single call splits the model's
     * response budget across the requested categories, so each typically comes
     * back with fewer cases than it would alone. Category COVERAGE is still
     * complete - every requested category is explicitly demanded below, and
     * each returned case must name which one it belongs to - but depth per
     * category thins. Keep this at 1 unless token budget is the constraint.
     */
    public String businessTestCaseSystemPrompt(List<String> categories, boolean apiOnly) {
        if (categories.size() == 1) {
            return singleCategorySystemPrompt(categories.get(0), apiOnly);
        }

        StringBuilder combined = new StringBuilder();
        combined.append("""
                You must produce test cases for EVERY ONE of the categories below,
                in a single JSON array. Treat each category's FOCUS section as a
                separate assignment - do not let one category crowd out another,
                and do not merge them into generic cases. Aim for a comparable
                number of cases per category rather than spending the whole
                response on the first one.

                Tag every case with the category it belongs to via the "category"
                field, using EXACTLY one of these values: """);
        combined.append(categories.stream().map(String::toUpperCase).toList()).append("\n\n");

        for (String category : categories) {
            combined.append("=== CATEGORY: ").append(category.toUpperCase()).append(" ===\n");
            combined.append(categoryGuidanceFor(category)).append("\n");
        }

        return renderBusinessSystemPrompt(combined.toString(), apiOnly, true);
    }

    private String singleCategorySystemPrompt(String category, boolean apiOnly) {
        return renderBusinessSystemPrompt(categoryGuidanceFor(category), apiOnly, false);
    }

    private String categoryGuidanceFor(String category) {
        return switch (category.toUpperCase()) {
            case "NEGATIVE" -> """
                    FOCUS: NEGATIVE test cases only.

                    Write test cases where the input, sequence, or caller is INVALID and
                    the system should reject it, error out, or refuse the action. For every
                    field/parameter visible in the diff, ask "what happens if this is
                    wrong, missing, malformed, or the caller isn't allowed to do this?":
                    - Required fields missing or null
                    - Wrong data type or malformed format (e.g. text where a number/date/ID is expected)
                    - Input the code used to accept but no longer does. When the diff
                      TIGHTENS what a parameter takes, this is the most important case
                      in the whole suite: it is the only one that fails against the old
                      behavior and passes against the new. For a parameter narrowed to
                      whole numbers, cover a decimal (2.5), a whole-looking decimal
                      (4.0), a value beyond the new type's range, and a numeric string -
                      each rejected, with the error the user actually sees.
                    - Values that violate an explicit business rule visible in the code
                      (e.g. a status transition that shouldn't be allowed, a duplicate
                      that should be rejected, an operation on an already-finalized record)
                    - Calling an operation without the required preconditions met
                    - Unauthorized/unauthenticated attempts, but ONLY if authentication or
                      access control is actually visible in the diff or the provided code.
                    Every case's expectedResult must describe the rejection/error behavior,
                    not a successful outcome.

                    Every case must be reachable in the code you were given. Do not
                    assume surrounding machinery that was not shown to you - if there
                    is no login, session, role or permission check in the provided
                    code, then "user is not logged in" is not a valid precondition
                    and that scenario does not exist. The same goes for databases,
                    networks, files, queues or external services that are not there.
                    A rejection the code cannot actually perform is a false test that
                    a tester will waste time trying to run.

                    If the change genuinely has no invalid-input surface, return an
                    empty array rather than inventing one.
                    """;
            case "BOUNDARY" -> """
                    FOCUS: BOUNDARY and edge-value test cases only.

                    For every numeric, string, date, or collection value touched by the
                    diff, test at and just past its limits:
                    - Zero, empty string, empty list/collection
                    - Minimum and maximum allowed values (and one below min / one above max)
                    - Exactly-at-the-limit values (off-by-one is the whole point of this category)
                    - Very large inputs (long strings, large numbers, huge collections) if the
                      code doesn't visibly guard against them
                    - Null vs. empty vs. missing, where the code's behavior for each could differ
                    - Duplicate entries, if the code processes a collection
                    - Date/time edges if any are involved (e.g. expiry exactly now, exactly expired,
                      far future, far past, timezone boundaries)
                    Every case must target a specific boundary value in testData, not a
                    generic "large" or "invalid" description - use concrete numbers/strings.

                    SKIP BOUNDARIES THAT ARE ALREADY UNREACHABLE. A boundary is only
                    worth testing for input the code actually accepts far enough to
                    evaluate it. When a type, format or validation GATE rejects a whole
                    class of input up front, every value in that class is rejected for
                    the same reason regardless of its magnitude - so its boundaries are
                    the same single case tested over and over.

                    Think of it as a decision table: once "input is the accepted type"
                    is false, the outcome is "reject" and the magnitude condition is
                    a DON'T-CARE. Collapse those rows into the one type-rejection case
                    (which belongs to NEGATIVE, not here) and do not enumerate them.

                    Worked example - a parameter that accepts only integers:
                    - The type gate rejecting 3.7 is ONE negative case. Do not then add
                      "very large float", "float at max int", "negative float", "0.0" -
                      they all fail identically at the same gate and prove nothing new.
                    - Boundaries WORTH testing are the ones inside the accepted domain:
                      the smallest and largest valid integer, zero, and one step past
                      each end of any range the code actually enforces.
                    The reverse holds when the parameter accepts only floats: the
                    integer-rejection case is one negative case, not a family of them.

                    DO NOT RE-TEST ONE BOUNDARY ACROSS EVERY OPERATION OR VARIANT.
                    When the same edge value is fed through several operations, keep
                    only the ones where the operation actually CHANGES the outcome:
                    - Keep it when that pairing has its own distinct behavior - e.g.
                      max integer + 1 overflows, and divide by zero is its own rule.
                    - Drop it when the operation is incidental to the edge - e.g. once
                      zero is shown to behave for one operation, repeating zero for
                      every other operation proves nothing further.
                    The operation and the boundary are independent dimensions; cover
                    each meaningful pairing once, not the whole grid of combinations.

                    Finally, stay inside your category: a case whose point is that
                    input was REJECTED is a negative case, not a boundary case. If
                    the only thing a case proves is that the wrong type was refused,
                    it does not belong here at all.

                    Prefer few cases that each prove a distinct rule over many that
                    re-prove the same one.
                    """;
            case "SECURITY" -> """
                    FOCUS: SECURITY test cases only.

                    Read the diff for its actual attack surface and write test cases
                    against what's really there - do not pad with generic security
                    cases that don't apply to this code. Consider, where relevant to
                    what you actually see in the diff:
                    - Injection: does any input flow into a query, command, file path,
                      or template without visible sanitization?
                    - AuthN/AuthZ: does this expose or modify data - can it be called
                      without proper authentication, or by a caller who shouldn't have
                      access to this specific record (insecure direct object reference -
                      e.g. accessing another user's/account's data by guessing an ID)?
                    - Sensitive data exposure: does a response, log statement, or error
                      message leak a token, credential, key, PII, or internal detail that
                      shouldn't be visible to the caller?
                    - Token/credential handling: if this touches tokens, keys, or secrets -
                      test expiry enforcement, revocation, reuse/replay of an old token,
                      and that raw secrets are never returned or logged.
                    - Input validation gaps: oversized payloads, unexpected encodings,
                      special characters, null bytes, path traversal sequences - anything
                      the visible validation doesn't obviously catch.
                    - Rate limiting / abuse: repeated rapid calls to a sensitive operation,
                      if no visible throttling exists.
                    ORDINARY INPUT VALIDATION IS NOT SECURITY. A type or format check
                    rejecting a decimal, a letter or an oversized number is correctness,
                    and NEGATIVE already covers it. It becomes a security case only when
                    getting past it would enable an actual attack - injection, reading
                    another user's data, leaking a secret, crashing the service. Do not
                    re-file "reject 3.5 where a whole number is required" as security by
                    rewording it.

                    Most small local changes - arithmetic, formatting, a type narrowing
                    on an internal method - have NO security surface at all. Return an
                    empty array for those. An empty array is the correct, expected
                    answer here far more often than not, and is always better than a
                    reworded copy of another category's case.
                    """;
            default -> """
                    FOCUS: POSITIVE (happy-path) test cases only.

                    Write test cases for the normal, expected, correctly-used behavior
                    of the feature - valid input, typical values, the standard sequence
                    of steps a real user would follow. Keep this set small (2-5 cases):
                    positive coverage is the easy part: focus your limited case count on
                    the distinct legitimate scenarios actually implied by the diff, not
                    trivial restatements of the same scenario with different numbers.

                    EVERY case here uses VALID input and ends in SUCCESS. If a case's
                    expectedResult is an error, a rejection, a warning or a refusal, it
                    is not a positive case - drop it, someone else is writing it. Never
                    put a value the code refuses in "testData".

                    When the change TIGHTENED what is accepted, your job is only to
                    show the still-valid input keeps working correctly - e.g. whole
                    numbers still add, subtract, multiply and divide as before. Proving
                    the newly-refused input is refused is the NEGATIVE category's job,
                    not yours.
                    """;
        };
    }

    /** Assembles the full business-test-case system prompt around whatever category guidance it's given. */
    private String renderBusinessSystemPrompt(String categoryGuidance, boolean apiOnly, boolean multiCategory) {
        String executionModeGuidance = apiOnly ? """
                EXECUTION MODE: this project is a backend/API service with no UI
                (see the "## API endpoints" section below) - there is no screen for
                a tester to click through, so every step must instead describe
                calling the REST API directly:
                - Use the EXACT HTTP method and path from "## API endpoints" for the
                  endpoint(s) this scenario exercises - never invent a path that
                  isn't listed there.
                - Include a concrete, realistic example JSON request payload in
                  "testData", using EXACTLY the field names/types listed under
                  that endpoint's "Request body" (if any) - never invent a field
                  that isn't listed - and reference it from "steps", e.g.
                  "1. Send a POST request to /api/v1/generate-tests with the
                  payload in testData. 2. ..."
                - "expectedResult" must describe the HTTP response: status code and
                  the relevant response body fields/values, not a UI outcome.
                - Still avoid mentioning class/method names or internal code
                  structure - "steps" should read like API documentation a tester
                  can follow with curl/Postman, not a description of the
                  implementation.
                """ : """
                EXECUTION MODE: write steps as a human QA tester would follow
                through the application's UI - concrete screens/actions, not code
                or API calls.
                """;

        return """
                You are a senior QA analyst. Given a set of code changes (diffs),
                the methods/functions they touch, what else in the codebase is
                impacted, AND the existing test cases already in the catalog for
                this category, write functional/business test cases that a human
                QA tester can execute WITHOUT reading or running any code.

                Think in terms of what the FEATURE does from a user/business
                perspective - not test syntax, not classes, not code structure.

                PROVE THE CHANGE, DON'T JUST DESCRIBE THE FEATURE.
                A diff has a BEFORE and an AFTER. Your job is to prove the code
                now behaves as the AFTER and no longer as the BEFORE. Apply one
                simple test to every case you write:

                    Would this case have passed BEFORE the change too?

                If yes, it does not prove anything about this commit. At least one
                case must FAIL against the old behavior and PASS against the new.
                Read the diff for what was REMOVED, NARROWED or TIGHTENED, not just
                what is present now - the removed half is usually where the proof is.

                PROVE IT FROM YOUR OWN CATEGORY, NEVER ANOTHER'S.
                Every category proves the change from its own angle, and the FOCUS
                section below is the only angle you are allowed to take. When input
                is narrowed or tightened, the rejection cases belong to NEGATIVE and
                to NEGATIVE ONLY - a POSITIVE, BOUNDARY or SECURITY case whose
                expectedResult is "the input is refused" is a negative case filed
                under the wrong heading, and the caller generates the categories
                separately, so it lands as a duplicate rather than as coverage.
                If your category has no honest angle on this change, return an empty
                array. That is a valid answer and far better than restating another
                category's case in your own words.

                When the change is a BUG FIX, prove the defect is gone.
                - Identify the exact condition that triggered the bug and put the
                  concrete data that reproduces it in "testData" - the specific
                  values, not "invalid input". A fix case with vague data cannot be
                  re-run to confirm the fix held.
                - "expectedResult" states the CORRECTED behavior, and the scenario
                  should make clear this is the case that previously failed.
                - Add focused regression cases for the behavior immediately around
                  the fix - the neighbouring paths a fix like this most easily
                  breaks - not a re-test of the whole feature.

                %s

                %s

                For every case you write, decide NEW vs UPDATE:
                - "UPDATE": the diff changes behavior that one of the EXISTING
                  test cases listed below already covers - e.g. a validation
                  rule that case's expectedResult describes has changed, a step
                  no longer applies, or a limit/value changed. Set
                  "existingTestCaseId" to that case's ID EXACTLY as given below
                  (copy it verbatim), and put the corrected scenario/steps/
                  expectedResult in this entry - it replaces that case, it
                  doesn't sit alongside it.
                - "NEW": a scenario not already covered by anything in the
                  existing list. Leave "existingTestCaseId" as an empty string.
                Do not invent an ID that isn't in the existing list below - if
                you're not looking at an exact match from that list, use NEW.
                When in doubt between NEW and UPDATE, prefer NEW - a spurious
                UPDATE silently overwrites a case that may still be valid,
                which is worse than a harmless near-duplicate.

                Respond with ONLY a JSON array, no prose, no markdown fences, matching:
                [
                  {
                    "feature": "string - the business feature/module this validates, e.g. 'Transaction Totals Calculation'",
                    "scenario": "string - one-line description of what's being verified",
                    "preconditions": "string - required state before the test, e.g. 'User has at least one bank transaction recorded'",
                    "steps": "string - numbered steps a human would follow, e.g. '1. Add an income transaction of 1000 from Bank. 2. Add an expense of 100 from Bank. 3. Open the totals summary.'",
                    "testData": "string - concrete example input values to use",
                    "expectedResult": "string - the specific observable outcome, in business terms, not implementation details",
                    "priority": "High | Medium | Low",
                    "action": "NEW | UPDATE",
                    "existingTestCaseId": "string - required and exact when action is UPDATE, empty string otherwise"%s
                  }
                ]

                Rules:
                - Never mention classes, methods, functions, code syntax, or test
                  frameworks anywhere in the output - a non-technical tester must
                  be able to follow every step.
                - Base every test case on logic actually present in the provided
                  diffs/methods - do not invent features that weren't shown.
                - GROUNDING: every precondition, step and expected result must be
                  something the provided code can actually do. Do not introduce
                  authentication, login, sessions, users, roles, permissions,
                  databases, networks, files or external services unless they
                  appear in the code you were given. A calculator that takes two
                  numbers has no "user is not logged in" state. When you catch
                  yourself writing a precondition the code has no notion of, drop
                  the case - it is not a test, it is a guess about a system that
                  may not exist.
                - NO REDUNDANT CASES: each case must prove a rule no other case
                  already proves. If two cases fail at the same validation gate
                  for the same reason, they are one case - keep the clearest and
                  drop the rest. Coverage means every distinct rule and outcome is
                  exercised once, not that every input value appears somewhere.
                  A short suite where each case earns its place beats a long one
                  that re-proves the same gate with different numbers.
                %s
                """.formatted(categoryGuidance, executionModeGuidance,
                        multiCategory
                                ? ",\n                    \"category\": \"string - which FOCUS category above this case belongs to\""
                                : "",
                        multiCategory
                                ? "- Cover EVERY category listed above; label each case with its \"category\".\n"
                                  + "                  Do not let one category dominate the response."
                                : "- Stay strictly within the FOCUS category above - do not drift into\n"
                                  + "                  other categories; the caller is generating those separately.");
    }

    /**
     * Builds the user prompt for one business test-case category, including
     * an "## Existing test cases" section listing the current catalog
     * entries for this same category (already filtered/capped by the caller -
     * see QaPipelineService), each with its ID, so the model can decide NEW
     * vs UPDATE and reference an ID that actually exists. Pass an empty list
     * for a repo/category with no prior history yet (e.g. the very first
     * run) - everything will come back NEW.
     */
    public String buildBusinessTestCaseUserPrompt(List<ChangedFile> changedFiles, List<ClassInfo> classes,
                                                   ImpactResult impact, List<ManualTestCase> existingTestCases) {
        StringBuilder sb = new StringBuilder();
        appendContext(sb, changedFiles, classes, impact);

        if (existingTestCases.isEmpty()) {
            sb.append("## Existing test cases\n\nNone yet for this category - every case you write should be NEW.\n\n");
        } else {
            sb.append("## Existing test cases (this category only - reference these IDs exactly if updating one)\n\n");
            for (ManualTestCase tc : existingTestCases) {
                sb.append("- ID=").append(tc.testCaseId())
                        .append(" | ").append(tc.feature())
                        .append(": ").append(tc.scenario())
                        .append(" -> ").append(truncate(tc.expectedResult(), 200))
                        .append("\n");
            }
            sb.append("\n");
        }

        sb.append("Generate the JSON array of business/functional test cases now.");
        return sb.toString();
    }

    // ---------------------------------------------------------------
    // API automation: manual test cases -> runnable REST Assured scripts
    // ---------------------------------------------------------------

    /**
     * System prompt for POST /api/v1/generate-automation: converts manual test
     * cases that were ALREADY reviewed/generated for a commit into runnable
     * REST Assured code.
     *
     * <p>Scoped deliberately to API testing only - the caller has already
     * filtered out UI and configuration cases, and this prompt reinforces that
     * a case which can't be exercised over HTTP should be skipped rather than
     * turned into a script that can't actually run.
     *
     * @param defaultBaseUri baked in as the fallback for
     *                       {@code System.getProperty("baseUri", ...)}, so the
     *                       target stays overridable at run time.
     */
    public String apiAutomationSystemPrompt(String defaultBaseUri) {
        return """
                You are a senior SDET. You are given manual API test cases that a QA
                team has already agreed on, plus the exact REST endpoints available.
                Convert them into runnable REST Assured (io.rest-assured) automation.

                Automate ONLY what can be exercised over HTTP. If a given test case
                describes a UI interaction, a configuration/deployment check, or
                anything with no HTTP surface, OMIT it entirely rather than inventing
                an endpoint for it - a script that can't run is worse than no script.

                Follow this contract exactly:
                - "package com.company.aiqa.generated;" as the first line.
                - The public class name must exactly match testFileName (minus ".java").
                - TestNG: "@Test" from "org.testng.annotations.Test". NOT JUnit - nothing
                  from org.junit is on the classpath, so importing it fails the compile.
                - Every test method must be "public void" taking no arguments. TestNG
                  ignores non-public methods, so a package-private one silently never runs.
                - In a "@BeforeClass public void" method (from
                  "org.testng.annotations.BeforeClass" - not @BeforeAll, and NOT static) set:
                  RestAssured.baseURI = System.getProperty("baseUri", "%s");
                  exactly that way - never hardcode a different host.
                - One @Test method per manual test case you automate. Name it after the
                  case's scenario, and put its Test Case ID in a comment above the
                  method so a human can trace script back to case.
                - For assertions beyond the response body use org.testng.Assert.
                - Use ONLY the HTTP methods and paths listed under "## API endpoints" -
                  never invent or guess a path.
                - Build request payloads from the case's own test data where given;
                  where it isn't, derive realistic values from the endpoint's request
                  body fields. Never invent a field that isn't listed.
                - JSON request bodies MUST use a Java text block (triple quote), never
                  string concatenation and never backslash-escaped quotes. Escaped JSON
                  inside a Java string inside a JSON response is three levels of
                  escaping and reliably comes back corrupted - a text block has none.
                  Write exactly this shape:

                      String requestBody = \"""
                          {
                            "fieldOne": "value",
                            "fieldTwo": 10
                          }
                          \""";

                  Do NOT write: "{" + "\\"slotType\\": ..." + "}"
                - A text block does NOT interpolate. Writing
                  "name": "Shop_" + System.currentTimeMillis()
                  INSIDE the triple quotes sends that text literally, verbatim, as the
                  name - it does not concatenate anything, and the same trap applies to
                  "id": "" + shopId + "". This compiles, so nothing warns you; the
                  request is simply wrong. To put a runtime value into a payload, use a
                  %%s / %%d placeholder and .formatted(...) on the text block:

                      String body = \"""
                          {
                            "name": "Shop_%%d",
                            "areaId": %%d
                          }
                          \""".formatted(System.currentTimeMillis(), areaId);

                  Note the quoting: a string value keeps its quotes around the
                  placeholder, a numeric one has none.
                - Translate the case's "Expected Result" into assertions: status code
                  first, then response body fields via Hamcrest matchers.
                - MATCH THE STATUS TO THE EXPECTED RESULT, never assume 200/201.
                  Read what the case says should happen and assert THAT:
                    * expects success        -> 200, or 201 for a create
                    * expects rejection of bad/invalid/malformed/missing input -> 400
                    * expects "not found" / unknown id                         -> 404
                    * expects a duplicate or conflicting state to be refused   -> 409
                      (or 400 if the API uses that - never 2xx)
                    * expects unauthenticated/forbidden, where auth exists     -> 401 / 403
                  Asserting 2xx on a case that expects a rejection INVERTS the test: it
                  fails when the server correctly refuses the bad input, and passes when
                  the server wrongly accepts it. That is worse than having no test at
                  all - it produces a red report for behavior that is actually correct,
                  and hides the defect it was written to catch. If a case's scenario or
                  name says fail/reject/invalid/duplicate/missing/unauthorized, its
                  final assertion MUST be a 4xx.
                - Keep PRECONDITION calls separate from the CASE'S OWN assertion. The
                  setup calls that build the fixture assert 2xx because they must
                  succeed; the one call the test case is actually about asserts whatever
                  that case expects. A negative test still has 2xx on its setup - do not
                  let that pull the final assertion to 2xx as well.
                - Only assert an exact status the case actually pins down. Where an API
                  could reasonably answer either of two codes, use
                  anyOf(is(400), is(422)) rather than guessing one and getting a
                  spurious failure - but never widen that to include a 2xx on a
                  rejection case.
                - Use ONLY matchers that actually exist in org.hamcrest.Matchers:
                  equalTo, containsString, startsWith, endsWith, notNullValue,
                  nullValue, hasKey, hasItem, hasItems, hasSize, empty, greaterThan,
                  lessThan, anyOf, allOf, not, is, instanceOf. Do NOT invent one by
                  fusing two together - there is no emptyOr(...), noneOf(...) or
                  eitherOf(...). To express "A or B" use anyOf(A, B), e.g.
                  anyOf(nullValue(), hasSize(0)) for "empty or absent". An invented
                  matcher fails the compile and loses the whole file.
                - Group related cases into one class per endpoint/feature rather than
                  one class per case.
                - Each file must be self-contained: only JDK, REST Assured, Hamcrest
                  and TestNG imports - nothing from the project's own source.
                - Do NOT add any logging filter, RestAssured.filters(...) call, or
                  log().all(). Every request and response is already captured by the
                  runner, and a script that also resets or replaces the global filters
                  destroys that capture.

                PRECONDITIONS - the single biggest cause of useless generated tests.

                The target database is NOT seeded. There is no record with id 1. A test
                that does GET/PUT/DELETE on a hardcoded id returns 404 "not found",
                fails, and tells you nothing about the code - it only proves you guessed
                an id that doesn't exist. NEVER hardcode the id of a resource you did
                not create in this same run.

                Every test must CREATE what it needs first, over the API, using the
                POST endpoints in the list, and use the id that comes back:

                    @Test
                    public void updateExistingCategory() {
                        String createBody = \"""
                            {
                              "name": "Oil Change",
                              "description": "Standard service"
                            }
                            \""";
                        // Precondition: the category under test must exist.
                        Integer categoryId = given()
                                .contentType(ContentType.JSON).body(createBody)
                                .when().post("/api/categories")
                                .then().statusCode(anyOf(is(200), is(201)))
                                .extract().path("data.id");

                        // The actual assertion of this test case.
                        given().contentType(ContentType.JSON).body(updateBody)
                                .when().put("/api/categories/{id}", categoryId)
                                .then().statusCode(200);
                    }

                Rules for this:
                - Read each case's "Preconditions" field and satisfy it with real calls
                  before the assertion. That field is the specification for the setup,
                  not a comment to copy into the code.
                - THIS APPLIES TO READS AND SEARCHES TOO, not just to updates and
                  deletes. "Search for a customer by id" needs that customer POSTed
                  first in the same test, then searched by the id that came back.
                  Looking up an id you did not create tests nothing: it returns 404 or
                  an empty list, and it fails for a reason that has nothing to do with
                  the search logic you meant to exercise. The same holds for list or
                  filter endpoints - create a record that MATCHES your filter first,
                  otherwise an empty result is meaningless and the assertion is
                  vacuous. Whenever a case reads, lists, searches or filters, ask what
                  must exist for the result to be meaningful, and create exactly that.
                - Extract the id with .extract().path(...). Match the path to the
                  response the create endpoint actually returns - if responses are
                  wrapped in an envelope like {"status":..,"data":{..}} the path is
                  "data.id", not "id".
                - A test that MUTATES or DELETES its subject must create its own, never
                  share one with another test. Tests must pass in any order and pass
                  twice in a row.
                - Put only READ-ONLY shared fixtures in @BeforeClass. Anything a test
                  changes belongs inside that test.
                - Clean up what you created in @AfterClass where a DELETE endpoint
                  exists, so repeated runs don't pile up rows. Ignore cleanup failures.
                - Use unique values for anything that must not collide - a fixed name or
                  email fails on the second run. Do this with a %%d placeholder and
                  .formatted(System.currentTimeMillis()), NEVER by writing
                  + System.currentTimeMillis() inside the text block.
                - Build every payload from the "Request payload schemas" section below,
                  using those exact field names and, for an enum field, one of its
                  listed values. A field the schema doesn't list will be rejected or
                  ignored; a required one you omit fails the create outright.
                - NEGATIVE cases handle their setup differently by KIND:
                    * "operate on a resource that does not exist" - create NOTHING. A
                      deliberately absent id like 999999 is CORRECT here, and asserting
                      404 is the entire point.
                    * "invalid/malformed/missing input" - build any fixture the call
                      genuinely needs (2xx on that setup), then send the ONE bad field
                      and assert 4xx. Keep everything else in the payload valid, so the
                      failure can only be caused by the field under test.
                    * "duplicate/conflict" - the first create is a PRECONDITION and
                      asserts 2xx; the second, colliding call is the assertion and must
                      assert 409 or 400. A duplicate test that asserts 2xx on the second
                      call is asserting the bug rather than the rule.
                - Chains matter: to pay for an appointment you must first create the
                  shop, then the appointment, then pay. Follow the chain with real calls
                  as far as the endpoint list allows.
                - If a precondition CANNOT be built from the listed endpoints, OMIT that
                  test case entirely. A silently 404-ing test is worse than no test - it
                  looks like a defect in the application.

                Respond with ONLY a JSON array, no prose, no markdown fences, matching:
                [
                  {
                    "targetClassName": "string - the feature/endpoint under test",
                    "testFileName": "string - e.g. GreetingApiTest.java",
                    "testCode": "string - the complete, compilable Java source"
                  }
                ]
                """.formatted(defaultBaseUri);
    }

    /**
     * User prompt for API automation: the endpoints available, then the manual
     * cases to convert. Steps/expected results are passed through largely
     * verbatim - they're the agreed specification, and the model's job here is
     * translation into code, not reinterpretation.
     */
    public String buildApiAutomationUserPrompt(List<ManualTestCase> cases, List<ClassInfo> classes,
                                               List<TypeSchema> payloadSchemas) {
        StringBuilder sb = new StringBuilder();

        appendApiEndpoints(sb, classes);
        if (sb.isEmpty()) {
            sb.append("## API endpoints\n\nNone were detected in this commit.\n\n");
        }
        appendPayloadSchemas(sb, payloadSchemas);

        sb.append("## Manual test cases to automate\n\n");
        for (ManualTestCase tc : cases) {
            sb.append("### ").append(tc.testCaseId()).append(" - ").append(tc.scenario()).append("\n");
            sb.append("- Feature: ").append(tc.feature()).append("\n");
            sb.append("- Type: ").append(tc.type()).append("  Priority: ").append(tc.priority()).append("\n");
            if (tc.preconditions() != null && !tc.preconditions().isBlank()) {
                sb.append("- Preconditions: ").append(truncate(tc.preconditions(), 500)).append("\n");
            }
            sb.append("- Steps: ").append(truncate(tc.steps(), 1500)).append("\n");
            if (tc.testData() != null && !tc.testData().isBlank()) {
                sb.append("- Test data: ").append(truncate(tc.testData(), 800)).append("\n");
            }
            sb.append("- Expected result: ").append(truncate(tc.expectedResult(), 800)).append("\n\n");
        }

        sb.append("Generate the JSON array of REST Assured test files now.");
        return sb.toString();
    }

    // ---------------------------------------------------------------
    // Configuration-change test cases (application.yml, .properties,
    // Dockerfile, pom.xml, etc. - see ConfigType)
    // ---------------------------------------------------------------

    /**
     * System prompt for config-file changes detected by
     * GitDiffService.computeChangedConfigFiles / ConfigType. A config change
     * (a flipped feature flag, a changed timeout, a new required env var, a
     * bumped dependency version, an altered Docker base image) has no methods
     * or classes to parse, but it absolutely has QA-relevant behavior to
     * verify - this produces business/functional test cases the same shape as
     * businessTestCaseSystemPrompt, but focused on config-specific risk
     * instead of a source-code diff.
     */
    public String configTestCaseSystemPrompt() {
        return """
                You are a senior QA analyst reviewing CONFIGURATION file changes
                (not application source code) - things like YAML/properties/JSON/XML
                settings, environment files, Dockerfiles, build files (pom.xml,
                build.gradle), Terraform, or SQL migrations.

                For each changed configuration file below, its detected TYPE comes
                with a "what to check" hint - use that hint to focus your test
                cases on the risk specific to that file's format, grounded in the
                actual values changed in its diff. Do not write generic "the app
                should still work" cases; be specific about what changed and what
                could break because of it (e.g. a timeout that dropped from 60s to
                5s, a feature flag flipped on, a dependency major-version bump, a
                new required environment variable, a changed database URL/port).

                For every case you write, decide NEW vs UPDATE exactly as for
                business test cases: "UPDATE" only when an EXISTING case listed
                below already covers behavior this config change alters (copy its
                ID exactly into existingTestCaseId); otherwise "NEW" with an empty
                existingTestCaseId. When in doubt, prefer NEW.

                Respond with ONLY a JSON array, no prose, no markdown fences, matching:
                [
                  {
                    "feature": "string - the config area affected, e.g. 'LLM Provider Timeout Configuration'",
                    "scenario": "string - one-line description of what's being verified",
                    "preconditions": "string - required state before the test",
                    "steps": "string - numbered steps a human would follow to verify this, e.g. '1. Deploy with the updated config. 2. Trigger the affected operation. 3. Observe behavior/logs.'",
                    "testData": "string - concrete example values (old vs new) relevant to this check",
                    "expectedResult": "string - the specific observable outcome, in business/operational terms",
                    "priority": "High | Medium | Low",
                    "action": "NEW | UPDATE",
                    "existingTestCaseId": "string - required and exact when action is UPDATE, empty string otherwise"
                  }
                ]

                Rules:
                - Base every case on a value actually changed in the diff shown - never
                  invent a setting that isn't there.
                - If a change is purely cosmetic (e.g. reordered keys, comments, whitespace)
                  with no behavioral effect, return an empty array rather than padding
                  with irrelevant cases.
                - A non-technical tester must be able to follow every step - describe
                  observable/operational behavior, not code internals.
                """;
    }

    public String buildConfigTestCaseUserPrompt(List<ChangedFile> configFiles, List<ManualTestCase> existingTestCases) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Changed configuration files\n\n");
        for (ChangedFile file : configFiles) {
            ConfigType type = ConfigType.fromPath(file.path()).orElse(ConfigType.OTHER);
            sb.append("### ").append(file.path())
                    .append(" (").append(file.changeType()).append(", ").append(type.label()).append(")\n");
            sb.append("What to check: ").append(type.checklistHint()).append("\n");
            sb.append("```diff\n").append(truncate(file.diffText(), 4000)).append("\n```\n\n");
        }

        if (existingTestCases.isEmpty()) {
            sb.append("## Existing test cases\n\nNone yet for this category - every case you write should be NEW.\n\n");
        } else {
            sb.append("## Existing test cases (this category only - reference these IDs exactly if updating one)\n\n");
            for (ManualTestCase tc : existingTestCases) {
                sb.append("- ID=").append(tc.testCaseId())
                        .append(" | ").append(tc.feature())
                        .append(": ").append(tc.scenario())
                        .append(" -> ").append(truncate(tc.expectedResult(), 200))
                        .append("\n");
            }
            sb.append("\n");
        }

        sb.append("Generate the JSON array of configuration test cases now.");
        return sb.toString();
    }

    // ---------------------------------------------------------------
    // Automated test code (JUnit/Jest/pytest/etc.)
    // ---------------------------------------------------------------

    public String systemPrompt(List<ClassInfo> classes) {
        String frameworkGuidance = buildFrameworkGuidance(classes);

        return """
                You are a senior test engineer fluent in multiple languages. Given a set
                of changed source units, their method/function signatures, and the units
                impacted by those changes, write high-value automated test cases using
                each file's own language and its idiomatic test framework.

                %s

                Respond with ONLY a JSON array, no prose, no markdown fences, matching:
                [
                  {
                    "targetClassName": "string - simple class/file name under test",
                    "testFileName": "string - correct filename/extension for that language, e.g. FooServiceTest.java or foo_service_test.dart",
                    "testCode": "string - complete, idiomatic, standalone test source for that language"
                  }
                ]

                Rules:
                - Match each test's language and framework to the file it targets - never
                  emit a Java test for a Dart file or vice versa.
                - Cover changed public methods/functions first, then edge cases and error paths.
                - Cover impacted units only where the change plausibly affects their behavior.
                - Each testCode value must be a full, standalone, idiomatic test file for its
                  language, including the correct imports/package/library declarations.
                - Do not invent methods, fields, or functions that were not shown to you.
                """.formatted(frameworkGuidance);
    }

    public String buildUserPrompt(List<ChangedFile> changedFiles, List<ClassInfo> classes, ImpactResult impact) {
        StringBuilder sb = new StringBuilder();
        appendContext(sb, changedFiles, classes, impact);
        sb.append("Generate the JSON array of test cases now.");
        return sb.toString();
    }

    // ---------------------------------------------------------------
    // Shared context section
    // ---------------------------------------------------------------

    private void appendContext(StringBuilder sb, List<ChangedFile> changedFiles, List<ClassInfo> classes,
                                ImpactResult impact) {
        sb.append("## Changed files\n\n");
        for (ChangedFile file : changedFiles) {
            String lang = SourceLanguage.fromPath(file.path())
                    .map(Enum::name)
                    .orElse("UNKNOWN");
            sb.append("### ").append(file.path())
                    .append(" (").append(file.changeType()).append(", ").append(lang).append(")\n");
            sb.append("```diff\n").append(truncate(file.diffText(), 4000)).append("\n```\n\n");
        }

        // Bounded: this section grows with the size of the change, and used to
        // be unbounded - on a large merge it could dwarf the diffs themselves
        // and push the prompt past the model's context window.
        sb.append("## Parsed source units\n\n");
        int unitsBudget = PARSED_UNITS_CHAR_BUDGET;
        int unitsShown = 0;
        for (ClassInfo classInfo : classes) {
            StringBuilder unit = new StringBuilder();
            unit.append("### ").append(classInfo.qualifiedName())
                    .append(" [").append(classInfo.language()).append("]\n");
            for (MethodInfo method : classInfo.methods()) {
                unit.append("- ").append(method.signature());
                if (!method.calledMethodNames().isEmpty()) {
                    unit.append("  [calls: ").append(String.join(", ", method.calledMethodNames())).append("]");
                }
                unit.append("\n");
            }
            unit.append("\n");

            if (unitsBudget - unit.length() < 0 && unitsShown > 0) {
                sb.append("...[").append(classes.size() - unitsShown)
                        .append(" further source unit(s) omitted to stay within the prompt budget]\n\n");
                break;
            }
            sb.append(unit);
            unitsBudget -= unit.length();
            unitsShown++;
        }

        appendApiEndpoints(sb, classes);

        sb.append("## Impact analysis\n\n");
        sb.append("Directly changed units: ").append(capped(impact.directlyChangedClasses())).append("\n");
        sb.append("Transitively impacted units (depth=").append(impact.depthUsed()).append("): ")
                .append(capped(impact.impactedClasses())).append("\n\n");

        // Method-level tracing is Java-only (see DependencyService.buildMethodLevelEdges) -
        // omitted rather than printed empty when there's nothing to show.
        if (!impact.impactedMethods().isEmpty()) {
            sb.append("Directly changed methods: ").append(capped(impact.directlyChangedMethods())).append("\n");
            sb.append("Transitively impacted methods (depth=").append(impact.depthUsed()).append("): ")
                    .append(capped(impact.impactedMethods())).append("\n");
            sb.append("Use these to write test cases for the SPECIFIC calling method(s) affected, "
                    + "not just the class in general, wherever method-level detail is available.\n\n");
        }
    }

    /** Total characters the "Parsed source units" section may occupy. */
    private static final int PARSED_UNITS_CHAR_BUDGET = 20_000;

    /** How many entries of an impact set to print before summarising the rest. */
    private static final int IMPACT_SET_LIMIT = 150;

    /**
     * Renders an impact set without letting it grow unbounded - on a wide
     * change the impacted-class set can run to thousands of names, which is
     * both useless to the model and expensive in tokens.
     */
    private String capped(java.util.Set<String> values) {
        if (values.size() <= IMPACT_SET_LIMIT) {
            return values.toString();
        }
        List<String> shown = values.stream().limit(IMPACT_SET_LIMIT).toList();
        return shown + " ...and " + (values.size() - IMPACT_SET_LIMIT) + " more";
    }

    /**
     * Lists every REST endpoint found among the changed/impacted classes (see
     * ApiEndpointInfo, set by JavaParserService for Spring MVC controllers and
     * by GenericSourceParser's best-effort regex for Express/Flask/FastAPI-style
     * routes) - the concrete facts an API-only project's test-case steps
     * should reference instead of inventing a plausible-looking path. Omitted
     * entirely when the diff has no detected endpoints (a UI-only or
     * non-controller change), rather than printing an empty, noisy section.
     */
    private void appendApiEndpoints(StringBuilder sb, List<ClassInfo> classes) {
        List<MethodInfo> handlers = classes.stream()
                .flatMap(c -> c.methods().stream())
                .filter(m -> m.apiEndpoint() != null)
                .toList();

        if (handlers.isEmpty()) {
            return;
        }

        sb.append("## API endpoints\n\n");
        sb.append("Use the POST endpoints here to build preconditions - this is the ")
                .append("complete set of ways to create test data.\n\n");
        for (MethodInfo handler : handlers) {
            ApiEndpointInfo endpoint = handler.apiEndpoint();
            sb.append("- ").append(endpoint.httpMethod()).append(" ").append(endpoint.path());
            if (endpoint.requestBodyType() != null && !endpoint.requestBodyType().isBlank()) {
                sb.append("  [request body: ").append(endpoint.requestBodyType()).append("]");
            }
            if (!endpoint.pathVariables().isEmpty()) {
                sb.append("  [path variables: ").append(String.join(", ", endpoint.pathVariables())).append("]");
            }
            if (!endpoint.queryParams().isEmpty()) {
                sb.append("  [query params: ").append(String.join(", ", endpoint.queryParams())).append("]");
            }
            // The handler's return type is how the model knows whether the
            // response is enveloped - an id lives at "data.id" behind an
            // ApiResponse<T> wrapper but at plain "id" without one, and
            // guessing wrong makes every precondition extraction come back null.
            if (handler.returnType() != null && !handler.returnType().isBlank()) {
                sb.append("  [returns: ").append(handler.returnType()).append("]");
            }
            sb.append("\n");
        }
        sb.append("\n");
    }

    /**
     * The real field shape of every request payload type, read from the
     * project's source. This is what stops the model inventing field names -
     * an invented field means a rejected create, a precondition that never got
     * built, and a 404 later that looks like an application defect.
     */
    private void appendPayloadSchemas(StringBuilder sb, List<TypeSchema> schemas) {
        if (schemas == null || schemas.isEmpty()) {
            return;
        }
        sb.append("## Request payload schemas (read from the project's source)\n\n");
        sb.append("These are the ACTUAL fields. Use these names exactly - do not invent, ")
                .append("rename or guess a field, and do not omit one marked required.\n\n");

        for (TypeSchema schema : schemas) {
            if ("enum".equals(schema.kind())) {
                sb.append("- enum ").append(schema.simpleName()).append(": ")
                        .append(String.join(" | ", schema.enumValues()))
                        .append("   (use one of these values verbatim)\n");
                continue;
            }
            sb.append("- ").append(schema.kind()).append(" ").append(schema.simpleName()).append("\n");
            for (TypeSchema.FieldSchema field : schema.fields()) {
                sb.append("    ").append(field.name()).append(": ").append(field.type());
                if (field.required()) {
                    sb.append("  (required)");
                }
                sb.append("\n");
            }
        }
        sb.append("\n");
    }

    /** Builds the "use X framework for language Y" guidance for exactly the languages present in this batch. */
    private String buildFrameworkGuidance(List<ClassInfo> classes) {
        Set<SourceLanguage> languagesPresent = new LinkedHashSet<>();
        for (ClassInfo classInfo : classes) {
            if (classInfo.language() != null) {
                languagesPresent.add(classInfo.language());
            }
        }
        if (languagesPresent.isEmpty()) {
            return "No specific language detected; use each file's existing conventions.";
        }

        StringBuilder sb = new StringBuilder("Use these frameworks per language present in this batch:\n");
        for (SourceLanguage lang : languagesPresent) {
            sb.append("- ").append(lang.name()).append(": ").append(lang.testFrameworkHint()).append("\n");
        }
        return sb.toString();
    }

    private String truncate(String text, int maxChars) {
        if (text == null) return "";
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "\n...[truncated]";
    }
}
