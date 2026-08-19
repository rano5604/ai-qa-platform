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

                    A NARROWED TYPE IS NOT A NEW VALIDATION RULE. If a field changed
                    from a decimal to a whole number, do NOT assume decimals are now
                    refused - most frameworks quietly CONVERT instead, storing 19.99 as
                    19 and accepting "50" as 50, with a perfectly successful response.
                    Rejection happens only where something in the code actually performs
                    it: a validation annotation, an explicit check that throws. If you
                    cannot see that, the expected result is "the value is accepted and
                    silently converted" - and THAT is the case worth writing, because
                    quietly turning a 19.99 price into 19 loses money without any error.
                    Say plainly what is stored, so the tester can spot the loss. Only a
                    value that cannot be represented at all - too large for the type -
                    is genuinely refused.

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

                    TAKE THE LIMIT FROM THE CODE, NEVER INVENT ONE. Only write a boundary
                    case for a limit you can actually SEE in the diff or the provided
                    source - a length check, a range test, a max constant. If the code
                    shows no limit for a field, you have no boundary to test there; do
                    not assume 255, 100, or any other familiar-looking number. When the
                    limit lives in configuration rather than in the code shown to you,
                    describe it relatively ("one character beyond the configured
                    maximum") instead of guessing a figure that will be wrong.

                    STATE THE UNIT, AND HONOUR IT. A limit measured in BYTES is not the
                    same as one measured in characters. If the code measures bytes -
                    getBytes(...).length, a byte[] size, a database column in bytes -
                    then say so in testData and include a MULTI-BYTE case, because that
                    is where real systems break: in UTF-8, Latin text is 1 byte per
                    character but Bangla, Arabic, Chinese and emoji are 2-4, so a
                    99-BYTE ceiling is hit at roughly 33 Bangla characters while 99
                    Latin characters still fit. For any byte-measured field, cover: at
                    the limit in plain ASCII, one past it, and a multi-byte value that
                    crosses the limit with far fewer characters than expected.

                    MAKE testData MATCH WHAT THE CASE SAYS. If a case is "exactly at the
                    maximum", the value in testData must really be that size - state it
                    as a count the tester can reproduce ("the letter A repeated 99
                    times"), never a hand-typed string whose length nobody can verify at
                    a glance and which is usually wrong.

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

                PRECONDITIONS ARE PART OF THE TEST, NOT A NOTE ABOVE IT.
                Code rarely reaches the rule you want to test on the first check.
                Before it gets there it verifies its own requirements, and the FIRST
                one that fails is what the tester will actually observe. A case that
                leaves an earlier requirement unmet does not test what its title says
                - it silently tests that earlier check instead, and it will keep
                "passing" while the rule you meant to cover goes unexercised.

                So, for every case: walk the code from the entry point to the
                behaviour under test and list what has to already be true to get
                there. That takes whatever form the project uses -

                - something referenced must already exist (a parent record, an
                  account, a configured entry, a file, a registered device);
                - something must be in a particular state (approved, active,
                  enabled, unlocked, not already used, not expired);
                - an earlier step of the flow must have happened (registered before
                  ordering, uploaded before processing, opened before editing);
                - a quantity or collection must be there to act on (a non-empty
                  cart, available stock, remaining balance or quota);
                - a setting or toggle must be present and switched on.

                Put every one of them in "preconditions", concretely: name what must
                exist or be true, and say it in terms of what a tester does through
                the product to get there ("a merchant account has been created and
                approved", not "merchant row present in DB"). Then write "steps" as
                if that state is already in place.

                Three limits on this:
                - GROUND THEM IN THE CODE, like everything else. Only a requirement
                  the given code actually checks belongs here. Do not add a login,
                  an approval or a permission the code has no notion of.
                - IF A TESTER CANNOT REACH THE STATE, DROP THE CASE. A precondition
                  that can only be set up by editing storage directly or changing a
                  setting and restarting is not something a test run can establish.
                - A MISSING PRECONDITION IS ONE CASE, NOT ONE PER OPERATION. "The
                  referenced account does not exist" is a single NEGATIVE case for
                  that rule. Repeating it for every operation that shares the check
                  proves the same gate over and over.

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
                    "preconditions": "string - everything that must already exist or be true for the steps to reach the behaviour under test, e.g. 'A savings account exists and has at least one recorded transaction'",
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
                - A TESTER MUST BE ABLE TO RUN IT. Every case has to be executable by
                  someone using the product normally - filling in fields, calling the
                  API. If proving it would need a config value changed, the service
                  restarted, a row edited directly in the database, or an internal
                  component forced to fail, then it is not a test case for this suite;
                  leave it out. "Verify the system rejects the request when the
                  configured signature length exceeds the hash output" is a code review
                  observation, not something a tester can set up or a request can
                  trigger. The same goes for expecting a crash or an internal server
                  error as the correct outcome: correct software answers a bad request
                  with a clear rejection, so make the rejection the expected result.
                - DO NOT QUOTE VALUES YOU CANNOT SEE. Never state an exact configured
                  number - a signature length, a page size, a timeout - unless it
                  appears in the code you were given. Describe it by its role ("the
                  configured maximum") so the case stays correct whatever the setting
                  is. An invented figure turns into a hard assertion downstream and
                  fails against a perfectly healthy system.
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
        return buildBusinessTestCaseUserPrompt(changedFiles, classes, impact, existingTestCases, "");
    }

    /**
     * @param relatedImplementation source of the collaborators the changed code
     *        calls into, or "" when unavailable. The diff shows WHAT changed;
     *        the rules that decide whether a change is even reachable usually
     *        live one call away, in a service the diff never touches.
     */
    public String buildBusinessTestCaseUserPrompt(List<ChangedFile> changedFiles, List<ClassInfo> classes,
                                                   ImpactResult impact, List<ManualTestCase> existingTestCases,
                                                   String relatedImplementation) {
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

        appendRelatedImplementation(sb, relatedImplementation);
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
                - IMPORTS: emit exactly this block, verbatim, in every file, before
                  anything else. A missing import fails the compile and loses every
                  test in the file, so err towards importing more than you need - an
                  unused import costs nothing:

                      import io.restassured.RestAssured;
                      import io.restassured.http.ContentType;
                      import io.restassured.response.Response;
                      import org.testng.Assert;
                      import org.testng.annotations.AfterClass;
                      import org.testng.annotations.BeforeClass;
                      import org.testng.annotations.Test;
                      import static io.restassured.RestAssured.given;
                      import static org.hamcrest.Matchers.*;

                  Then call it class-qualified - Assert.assertTrue(..),
                  Assert.assertNotEquals(..). Do NOT write
                  "import static org.testng.Assert.assertTrue;" and then call
                  "Assert.assertTrue(..)": that static-imports the METHOD while the
                  call names the CLASS, which was never imported, and it fails with
                  "cannot find symbol: variable Assert".
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
                  first, then - for a SUCCESS response only - body fields via Hamcrest
                  matchers.
                  WHERE A RESPONSE FIELD LIVES: if an "API contract" section is present
                  it lists every documented response field at its full JSON path, which
                  is exactly the path REST Assured takes. Copy it. When the contract
                  shows "data.id string(uuid)", the extraction is
                  .extract().path("data.id") and nothing else - not "id", not a guess,
                  not a fallback chain. Same for an assertion: .body("data.feeType", ..).
                  An array element is written "data[].code" in the contract and is
                  reached as "data[0].code" or with hasItem(..).
                  With NO contract section you are never shown a response shape, so
                  assert only fields the case's own Expected Result actually names, and
                  stay tolerant about where they sit: if creates come back wrapped as
                  {"status":..,"data":{..}} the path is "data.id".
                  Either way, asserting a field the case never mentions is a guess, and
                  a wrong guess reads as a product defect.
                - ERROR RESPONSES: ASSERT THE STATUS, KEEP THE BODY GENERIC.
                  An "API contract" section settles this whenever it is present AND
                  documents the status you are asserting: if it lists fields for a 400
                  or a 409, those field names are real and you may assert them at the
                  paths given. If it documents only 2xx - which is what springdoc
                  produces unless somebody wrote @ApiResponse annotations, and so what
                  you will usually see - then it tells you NOTHING about errors and
                  every rule below applies in full. Silence in the contract is not
                  permission; it is the absence of information.

                  Otherwise you are shown the request schemas, never the ERROR schema,
                  so you do not know what an error body looks like here. Stacks return
                  {"message":..}, {"error":..}, {"errors":[..]}, {"title":..,"detail":..}
                  or an empty body, and a guessed field name fails on a server that is
                  behaving perfectly correctly. That turns a passing system into a red
                  report and buries the failures that matter.
                  So for any case expecting an error:
                    * The STATUS CODE is the assertion. It is the part of the contract
                      you actually know. Very often it should be the only one.
                    * Do NOT assert a JSON path in an error body - no .body("message",
                      ..), no .body("error.code", ..). You are guessing the field name.
                    * Do NOT assert exact error wording. Message text gets reworded and
                      localized without the behavior changing at all.
                    * Do NOT assert the error body is non-empty - plenty of APIs answer
                      4xx with no body, and that is legitimate.
                  THE DEFAULT FOR AN ERROR CASE IS THE STATUS CODE AND NOTHING ELSE:

                      given().contentType(ContentType.JSON).body(invalidPayload)
                              .when().post("/api/customers")
                              .then().statusCode(anyOf(is(400), is(422)));

                  That is a complete, correct negative test. Do not add a body check to
                  make it look more thorough - you will usually be adding a guess.
                  Most frameworks answer a rejected request with a GENERIC envelope
                  that names no field at all. Spring Boot's default is exactly this:

                      {"timestamp":"..","status":400,"error":"Bad Request","path":"/orders"}

                  There is no "price", no "email", no field name anywhere in it. So an
                  assertion that the body mentions the offending field FAILS on a
                  service that just behaved perfectly - it returned 400 for bad input,
                  which is the whole point of the test. The status assertion already
                  proved the rule; the body check only added a way to be wrong.

                  Add a body assertion ONLY when the manual case QUOTES the message
                  text it expects - actual words in quotes in the Expected Result, not
                  a general statement that an error is shown. In that case match those
                  quoted words loosely against the whole body, case-insensitively, and
                  print the body on failure so the report is diagnosable:

                      String body = given().contentType(ContentType.JSON).body(payload)
                              .when().post("/api/slots")
                              .then().statusCode(anyOf(is(400), is(409)))
                              .extract().asString();
                      Assert.assertTrue(body.toLowerCase().contains("already exists"),
                              "Expected the quoted message. Body was: " + body);

                  Never assert that an error body mentions a FIELD NAME. Prefer
                  asserting less and being right to asserting more and being wrong: an
                  over-specified error assertion reports a defect that is not there,
                  and someone has to spend time proving it is not there.

                  NO JSON PATH ON AN ERROR RESPONSE - NOT ONE, INCLUDING THE
                  ENVELOPE'S OWN FIELDS. Never write .body("success", ..),
                  .body("error", ..), .body("message", ..), .body("code", ..),
                  .body("errors[0]...", ..) or any other path against a 4xx/5xx
                  response. You have been shown the code that REJECTS the request; you
                  have not been shown the shape it rejects it with, and services differ
                  completely. One real example: a service whose success responses look
                  like {"success":true,"data":{..}} answers a rejected request with
                  {"status":400,"error":"Validation Failed","message":"Input validation
                  failed","path":".."} - no "success" field anywhere. Asserting
                  .body("success", is(false)) there fails against a service that
                  behaved perfectly, and the report shows a defect that does not exist.
                  The status code is the assertion for an error case. When you also
                  need text, take the WHOLE body as a string and search it
                  case-insensitively - that works whatever the envelope turns out to
                  be:

                      String body = given()...post("/api/v1/merchants")
                              .then().statusCode(400)
                              .extract().asString();
                      Assert.assertTrue(body.toLowerCase().contains("email"),
                              "Body was: " + body);

                  And only do that when the case QUOTES the words AND you can see the
                  service producing them in the implementation source. A service whose
                  handler returns a fixed "Input validation failed" for every rejection
                  will never contain the field-specific sentence a manual case imagined.
                - NEVER ASSERT A CONSTANT YOU WERE NOT SHOWN. Signature/token lengths,
                  page sizes, timeouts, retry counts and similar live in configuration
                  you cannot see from here. Asserting hasLength(64) on a signature whose
                  configured length is actually something else fails on a perfectly
                  healthy service, and the number is a guess even when it looks
                  authoritative. Unless the exact value appears in the manual case or
                  the source you were given, assert the SHAPE instead:
                      .body("signature", notNullValue())
                      .body("signature", not(emptyString()))
                  Two tests must never assert two DIFFERENT lengths for the same field -
                  that is a guess contradicting itself, and at least one of them is
                  guaranteed to fail.
                - COUNT, DO NOT ESTIMATE. When a case turns on an exact size - "name at
                  exactly 99 characters", "list of 50" - the value you emit must really
                  be that size. A name described as 99 characters that is actually 88
                  does not test the boundary at all: it sits well inside the limit and
                  passes for the wrong reason, while its sibling "100" case fails because
                  it is under the limit too. If a limit is measured in BYTES, remember
                  multi-byte characters: Bangla, Arabic, Chinese and emoji are 2-4 bytes
                  each in UTF-8, so a 99-BYTE limit is reached at ~33 Bangla characters.
                  Build such values with an explicit repeat, e.g.
                  "a".repeat(99), rather than by typing a literal you cannot verify.
                - ONLY AUTOMATE WHAT A REQUEST CAN REACH. If proving a case would require
                  changing server configuration, restarting the service, editing a
                  database directly, or forcing an internal failure, it CANNOT be done
                  over HTTP from here - OMIT it. In particular, never send an ordinary
                  valid payload and assert 5xx: a 500 is the service breaking, not a
                  business rule, and no request body can make a correctly configured
                  service crash on demand. If you cannot name the field in YOUR request
                  that causes the failure, the case does not belong in this file.
                - BE CONSISTENT ACROSS TESTS. Two tests sending the SAME payload to the
                  SAME endpoint must assert the SAME status - if one expects 200 and
                  another 500, one of them is certainly wrong, and a reviewer cannot
                  tell which. Likewise, a test's name must match what it actually sends:
                  a method called ...WithOptionalFieldsEmpty must send those fields as
                  empty strings, not omit them (omitting them is a different test, and
                  usually a duplicate of the happy path you already wrote).
                - NUMERIC BODY ASSERTIONS ARE TYPE-BRITTLE - COMPARE NUMERICALLY.
                  A JSON number arrives as Integer, Float, Double or BigDecimal
                  depending on its value and the parser's mood, and Hamcrest's
                  equalTo is TYPE-STRICT: equalTo(999.99f) fails against 999.99 read
                  as a Double or BigDecimal, and against 999, reporting the useless
                  "JSON path price doesn't match. Expected: <999.99F> Actual: <999>".
                  Never write equalTo with a typed numeric literal - no 999.99f, no
                  0.0F, no 10L. Instead extract the value and compare as a number,
                  which works whatever type it arrived as:

                      Number price = given().contentType(ContentType.JSON).body(body)
                              .when().post("/orders")
                              .then().statusCode(200)
                              .extract().path("price");
                      Assert.assertEquals(price.doubleValue(), 999.99, 0.001,
                              "price should round-trip unchanged");

                  For a whole number use equalTo(19) - an int literal is safe because
                  whole JSON numbers parse as Integer. Strings, booleans and null are
                  unambiguous too, so equalTo("Alice") is fine.
                - ONLY ASSERT A VALUE THIS TEST PUT THERE. Asserting that order 2
                  belongs to "Bob" tests whatever happens to be in the database today,
                  so it fails the moment someone else's data is there - and it passes
                  for no good reason when it does pass. Create the record, keep the
                  value you sent, and assert that value came back. Never assert against
                  a record you did not create in this same test.
                - A NARROWED TYPE DOES NOT MEAN THE API NOW REJECTS THINGS. This is the
                  single most common wrong assumption. Changing a field from double to
                  int adds NO validation - the JSON layer silently CONVERTS instead:
                    * 19.99 into an int field -> accepted, stored as 19 (200, not 400)
                    * "50" as a string -> accepted, coerced to 50 (200)
                    * -1 -> accepted; nothing rejects negatives unless something says so
                    * a value too large for the type -> 400, because it cannot be parsed
                      at all - that is a parse failure, not a business rule
                  Before asserting ANY rejection, look for the thing that does the
                  rejecting: @Valid on the parameter plus @Min/@Max/@Positive/@NotNull
                  on the field, or an explicit if-check that throws. If you cannot point
                  to it in the code you were given, the API almost certainly ACCEPTS the
                  value, and asserting 4xx will fail against a service behaving exactly
                  as written.
                - WHEN INPUT IS SILENTLY CONVERTED, ASSERT THE CONVERSION. That is the
                  real, testable contract, and it is a stronger test than a wrong 4xx:

                      given().contentType(ContentType.JSON).body(bodyWithPrice1999)
                              .when().post("/orders")
                              .then().statusCode(200)
                              .body("price", equalTo(19));   // 19.99 truncates to 19

                  This passes today, documents the data loss for a reviewer, and starts
                  failing the moment someone adds validation or changes the rounding -
                  which is exactly when a tester wants to be told.
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
                  and hides the defect it was written to catch.

                  THE "EXPECTED RESULT" TEXT DECIDES THE STATUS - NOT the case's title,
                  not its Type column. Read what the Expected Result actually says the
                  system does, and assert that:
                    * "An error message ... is displayed", "is rejected", "is not
                      saved"                                            -> 4xx
                    * "A success message ... is displayed", "were skipped", "the rest
                      were saved"                                       -> 2xx
                  A title beginning "Fail to ..." does NOT by itself mean 4xx. Plenty of
                  APIs handle a bad or duplicate input by succeeding and reporting what
                  they skipped - e.g. a case titled "Fail to add duplicate holidays"
                  whose Expected Result is "a success message: some holidays were
                  already declared and were skipped" is a 200, and asserting 4xx there
                  invents a failure. Equally, a case typed "Positive" whose Expected
                  Result is "An error message ... already exists" is a 4xx. When title
                  and Expected Result disagree, the Expected Result wins every time.
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
                  before the assertion. That field is a comment to act on, not one to
                  copy into the code.
                - THAT FIELD IS THE FLOOR, NOT THE LIMIT. It was written by someone
                  describing the feature, so it is often partial and sometimes empty -
                  and a missing line there is never permission to skip the setup. The
                  endpoint list and the implementation source below are the authority.
                  For the request this case makes, work out what the service requires
                  BEFORE it can reach the rule under test - a resource it loads by id
                  and rejects when absent, a status or flag it insists on, a step that
                  must have run first - and build every one of them with real calls,
                  whether the case mentions them or not. Assume nothing exists on the
                  target: any id you send must come from a create in this same test,
                  and any state must be reached by calling the endpoint that sets it.
                  A test that skips a prerequisite the code requires does not fail
                  honestly - it fails AT the prerequisite, so the rule it is named
                  after is never exercised and the report shows a 404 that reads like
                  an application defect.
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
                  "data.id", not "id". This is the ONE place a path is worth using,
                  because a null id fails loudly on the next line rather than
                  masquerading as a defect - and the "Request payload schemas" and
                  implementation source below tell you the real shape. If neither shows
                  you the response envelope, take the first path that can be true:

                      Response created = given()...post("/api/v1/merchants").then()
                              .statusCode(anyOf(is(200), is(201))).extract().response();
                      String merchantId = created.path("data.id") != null
                              ? created.path("data.id") : created.path("id");
                      Assert.assertNotNull(merchantId, "Precondition failed: no id in " + created.asString());
                - NEVER PUT AN ID IN AN INSTANCE FIELD. This is the single biggest
                  cause of broken generated suites. Writing

                      private String merchantId;              // WRONG

                  and then using it in .post("/merchants/{id}", merchantId) makes the
                  test depend on some OTHER test having run first and filled it in.
                  TestNG guarantees no order, so the field is usually still null and
                  the run dies with "Unnamed path parameter cannot be null (path
                  parameter at index 0 is null)" - before a single request is sent, so
                  the report shows an ERROR with no request/response to diagnose. It is
                  worse still when nothing assigns the field at all, which is easy to
                  do and impossible to see by reading one method.
                  Every id MUST be a LOCAL variable, created inside the very test that
                  uses it:

                      @Test
                      public void createSettlementAccountWithMaxLengthIban() {
                          String merchantId = given().contentType(ContentType.JSON).body(merchantBody)
                                  .when().post("/api/v1/merchants")
                                  .then().statusCode(anyOf(is(200), is(201)))
                                  .extract().path("data.id");        // local, not a field
                          Assert.assertNotNull(merchantId, "Precondition failed: no merchant id returned");

                          given().contentType(ContentType.JSON).body(accountBody)
                                  .when().post("/api/v1/merchants/{merchantId}/settlement-accounts", merchantId)
                                  .then().statusCode(201);
                      }

                  Yes, this repeats the create across tests. That repetition is the
                  point: each test then passes on its own, in any order, twice in a
                  row, and a failure means something is actually wrong.
                  The ONLY instance fields allowed are immutable constants shared by
                  every test - a base path, a fixed payload template. Never an id,
                  never anything a test assigns.
                - IDS ARE OFTEN NOT NUMBERS. Declare an extracted id as String unless
                  you have seen it is numeric: many APIs use UUIDs, and
                  "Integer feeId = ....extract().path(\"id\")" throws
                  "class java.lang.String cannot be cast to class java.lang.Integer"
                  at runtime - an ERROR, after a successful 201, which reads like the
                  API broke when it did exactly what it should. String works for a
                  UUID and for a number used in a path, so prefer it.
                - A test that MUTATES or DELETES its subject must create its own, never
                  share one with another test. Tests must pass in any order and pass
                  twice in a row.
                - Put only READ-ONLY shared fixtures in @BeforeClass. Anything a test
                  changes belongs inside that test.
                - Clean up what you created in @AfterClass where a DELETE endpoint
                  exists, so repeated runs don't pile up rows. CLEANUP MUST NEVER
                  ASSERT: no .then().statusCode(..) and no Assert in an @AfterClass.
                  A failing assertion there is reported as a suite-level ERROR that
                  is nothing to do with any test - e.g. asserting on DELETE when the
                  API has no DELETE answers 405 and turns a green run red. Fire the
                  request and ignore the outcome entirely:

                      given().when().delete("/orders/{id}", createdId);

                  If the endpoint list has no DELETE for that resource, write no
                  cleanup at all rather than calling a path that does not exist.
                - GUARD EVERY ID YOU EXTRACT. .extract().path("id") returns null when
                  the create failed or the id sits under a different key, and passing
                  null into .get("/orders/{id}", id) dies with "Unnamed path parameter
                  cannot be null" - an ERROR that hides which precondition actually
                  broke. Check it immediately, so the failure names its own cause:

                      Integer orderId = ... .extract().path("id");
                      Assert.assertNotNull(orderId, "Precondition failed: create returned no id");

                  Never use a literal id you did not create for a case that expects to
                  FIND something - only for a case that expects 404.
                - EVERY IDENTIFYING VALUE IN EVERY CREATE MUST BE UNIQUE PER RUN.
                  Not most creates - every one, in every test. The fields that bite
                  are the ones a service indexes: registration and licence numbers,
                  codes, SKUs, serial numbers, references, emails, usernames, phone
                  numbers, slugs. A fixed "REG123456" passes the first time you ever
                  run the suite and returns 409 forever after, and five tests that
                  each hardcoded the same one collide with each other inside a single
                  run. Neither is a defect in the service, but both read as one.
                  Do it with a %%d placeholder and .formatted(System.currentTimeMillis()),
                  NEVER by writing + System.currentTimeMillis() inside the text block:

                      String body = \"""
                          {
                            "registrationNo": "CR-%%d",
                            "contactEmail": "m%%d@example.com"
                          }
                          \""".formatted(System.currentTimeMillis(), System.currentTimeMillis());

                  The ONE exception is a duplicate/conflict case, where the second
                  create must repeat the FIRST CALL'S OWN value to earn its 409. Hold
                  that value in a local and use it twice - never share a hardcoded one
                  with another test, which collides by accident rather than by design.
                - Build every payload from the "API contract" section when there is
                  one, and from "Request payload schemas" otherwise, using those exact
                  field names and, for an enum field, one of its listed values. A field
                  the schema doesn't list will be rejected or ignored; a required one
                  you omit fails the create outright.
                - TAKE EVERY LIMIT FROM THE CONTRACT, NEVER FROM YOUR OWN SENSE OF WHAT
                  IS REASONABLE. The contract writes them next to the field -
                  "(maxLength 50)", "(minimum 0)", "enum[FLAT|PERCENTAGE|TIERED]". A
                  boundary case uses THAT number: 50 characters for a maxLength of 50,
                  51 to go past it. A test that asserts a 64-character limit against a
                  service configured for 16 fails forever and reads as a defect. If the
                  contract states no limit for a field, there is no documented limit,
                  and a boundary case for it cannot be written - say so rather than
                  inventing one.
                - A field typed string(uuid) in the contract must receive a real UUID.
                  Never send a placeholder, a name, or the text of a variable that was
                  never set - "null" is four characters of text, not an absent value,
                  and a service given it answers 500 for something that cannot exist.
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
                - A PRECONDITION IS NOT ALWAYS A CREATE. When the case requires
                  something to be in a state - approved, activated, enabled, verified,
                  cancelled - creating it is only half the setup; call the endpoint
                  that moves it into that state as well, and assert 2xx on that call
                  like any other setup step. Sending the real request against a
                  freshly-created record that is still in its initial state exercises
                  the state check, not the rule the case is about.
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
        return buildApiAutomationUserPrompt(cases, classes, payloadSchemas, "", "");
    }

    public String buildApiAutomationUserPrompt(List<ManualTestCase> cases, List<ClassInfo> classes,
                                               List<TypeSchema> payloadSchemas, String implementationSource) {
        return buildApiAutomationUserPrompt(cases, classes, payloadSchemas, implementationSource, "");
    }

    /**
     * @param implementationSource source of the controllers/services behind the
     *        endpoints, or "" when unavailable. Endpoint signatures alone say
     *        nothing about what a handler REQUIRES to already exist - that a fee
     *        cannot be created until its merchant does, say - so without this the
     *        model invents an id, gets 404/500, and writes assertions that could
     *        never have passed.
     * @param apiContract the target's own OpenAPI document, rendered, or "" when
     *        no document could be reached. This is the only section that
     *        describes a RESPONSE. Without it every assertion about a returned
     *        body is a guess, and the two guesses that cost whole runs were
     *        asserting an error envelope the service does not use and reading
     *        an id from "id" when it sits at "data.id".
     */
    public String buildApiAutomationUserPrompt(List<ManualTestCase> cases, List<ClassInfo> classes,
                                               List<TypeSchema> payloadSchemas,
                                               String implementationSource,
                                               String apiContract) {
        StringBuilder sb = new StringBuilder();

        appendApiEndpoints(sb, classes);
        if (sb.isEmpty()) {
            sb.append("## API endpoints\n\nNone were detected in this commit.\n\n");
        }
        appendApiContract(sb, apiContract);
        appendPayloadSchemas(sb, payloadSchemas);
        appendImplementation(sb, implementationSource);

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
                    "preconditions": "string - what must already exist or be true before step 1 - the config deployed, and any data or state the affected operation itself requires",
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
                - A setting only shows its effect through an operation that reads it,
                  and that operation has its own requirements. State them in
                  "preconditions" - the data that must already exist, the state it
                  must be in, the earlier step that must have run - otherwise the
                  case fails on the way to the setting and proves nothing about the
                  change.
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
    /**
     * Source of the code around the change, so business rules are READ rather
     * than assumed.
     *
     * <p>A diff shows what changed, but the rule that governs it often sits in a
     * collaborator the diff never touches - a service that checks a parent
     * record exists before doing anything, a validator that rejects a value, a
     * default applied when a field is absent. Without that code the model writes
     * plausible-sounding cases whose preconditions can never hold and whose
     * expected results contradict what the system actually does.
     */
    private void appendRelatedImplementation(StringBuilder sb, String relatedImplementation) {
        if (relatedImplementation == null || relatedImplementation.isBlank()) {
            return;
        }
        sb.append("""
                ## Related implementation (source)

                The code the change reaches into: the services, validators and
                repositories the changed classes call. This is the AUTHORITY on how the
                feature behaves - the diff alone only shows what was edited. Read it and
                let it decide every case you write:

                - PRECONDITIONS. This code is where they are visible: a lookup that
                  fails when the thing is absent, a status or flag checked before the
                  work is done, a step that assumes an earlier one ran. Every such
                  check is a precondition of the behaviour behind it - list it in
                  "preconditions" as required by the PRECONDITIONS rule above. A case
                  whose setup the system cannot reach is not a test, it is a guess.
                - WHAT IS ACTUALLY REJECTED. Only a check present in this code produces
                  an error. Where there is no check, the value is accepted however wrong
                  it looks - and "accepted when it should not be" is itself the case
                  worth writing, stated as what the system really does.
                - DEFAULTS AND CONVERSIONS. A field filled in when omitted, a value
                  rounded, truncated or coerced - each is a rule a tester can observe,
                  and each is invisible in the diff.
                - THE REAL ORDER OF OPERATIONS. When several checks apply, the first one
                  to fail is the error the user sees. Expected results must match that
                  order, not the order the fields appear in - and if the check you are
                  testing sits behind another one, satisfying that earlier check is a
                  precondition of your case, not a second thing to assert.

                Ground every case in this code. If nothing here supports a scenario you
                were considering, do not write it.

                """);
        sb.append(relatedImplementation).append("\n\n");
    }


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
    /**
     * The real implementation behind the endpoints, so preconditions can be READ
     * rather than guessed.
     *
     * <p>A handler that loads a parent entity and throws when it is absent makes
     * every test for that endpoint depend on creating the parent first. That
     * requirement appears neither in the endpoint signature nor in the request
     * schema - it lives in the method body. Supplying it is the difference
     * between a suite that runs and one that 404s through every case.
     */
    private void appendImplementation(StringBuilder sb, String implementationSource) {
        if (implementationSource == null || implementationSource.isBlank()) {
            return;
        }
        sb.append("""
                ## Endpoint implementation (source)

                The controllers and services behind the endpoints above. READ THESE
                BEFORE WRITING ANY TEST - they are the authority on three things the
                endpoint list cannot tell you:

                1. WHAT MUST ALREADY EXIST. Where a handler loads another entity and
                   throws if it is missing, every test for that endpoint must create
                   that entity first, over the API, and use the id it returns. Follow
                   the whole chain: if a fee requires a merchant, create the merchant.
                2. WHAT IS ACTUALLY VALIDATED, and therefore what can be rejected.
                   Only a check visible here - a validation annotation, an explicit
                   throw - produces a 4xx. Where the code checks nothing the value is
                   accepted however wrong it looks, and asserting a rejection fails
                   against a service behaving exactly as written.
                3. WHAT THE RESPONSE REALLY LOOKS LIKE - field names, whether the body
                   is wrapped in an envelope, and whether an id is a UUID String or a
                   number. Extract ids using the shape shown here.

                If a case cannot be set up from these endpoints and this code, OMIT it
                rather than inventing an id and hoping.

                """);
        sb.append(implementationSource).append("\n\n");
    }


    /**
     * The contract section, already rendered and budgeted by ApiContractRenderer.
     * Placed before the source-derived schemas because it outranks them: those
     * describe the DTOs at this commit, this describes what the service you are
     * about to call actually accepts and returns.
     */
    private void appendApiContract(StringBuilder sb, String apiContract) {
        if (apiContract == null || apiContract.isBlank()) {
            return;
        }
        sb.append(apiContract);
        if (!apiContract.endsWith("\n\n")) {
            sb.append("\n");
        }
    }

    private void appendPayloadSchemas(StringBuilder sb, List<TypeSchema> schemas) {
        if (schemas == null || schemas.isEmpty()) {
            return;
        }
        sb.append("## Request payload schemas (read from the project's source)\n\n");
        sb.append("These are the ACTUAL fields. Use these names exactly - do not invent, ")
                .append("rename or guess a field, and do not omit one marked required.\n")
                .append("If an \"API contract\" section appears above, IT WINS on any disagreement: ")
                .append("it is what the deployed service accepts, while these are the types at this ")
                .append("commit. A field here that the contract does not list is one the target may ")
                .append("not have yet - send it anyway, since testing this commit is the point, but ")
                .append("do not build an assertion on it.\n\n");

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
