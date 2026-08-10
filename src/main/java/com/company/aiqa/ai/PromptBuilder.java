package com.company.aiqa.ai;

import com.company.aiqa.model.ApiEndpointInfo;
import com.company.aiqa.model.ChangedFile;
import com.company.aiqa.model.ClassInfo;
import com.company.aiqa.model.ConfigType;
import com.company.aiqa.model.ImpactResult;
import com.company.aiqa.model.ManualTestCase;
import com.company.aiqa.model.MethodInfo;
import com.company.aiqa.model.SourceLanguage;
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
                    - Values that violate an explicit business rule visible in the code
                      (e.g. a status transition that shouldn't be allowed, a duplicate
                      that should be rejected, an operation on an already-finalized record)
                    - Calling an operation without the required preconditions met
                    - Unauthorized/unauthenticated attempts, if any access control is visible or implied
                    Every case's expectedResult must describe the rejection/error behavior,
                    not a successful outcome.
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
                    If the diff genuinely has no security-relevant surface (e.g. a pure
                    UI label change), return an empty array rather than inventing
                    irrelevant security cases.
                    """;
            default -> """
                    FOCUS: POSITIVE (happy-path) test cases only.

                    Write test cases for the normal, expected, correctly-used behavior
                    of the feature - valid input, typical values, the standard sequence
                    of steps a real user would follow. Keep this set small (2-5 cases):
                    positive coverage is the easy part: focus your limited case count on
                    the distinct legitimate scenarios actually implied by the diff, not
                    trivial restatements of the same scenario with different numbers.
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
        List<ApiEndpointInfo> endpoints = classes.stream()
                .flatMap(c -> c.methods().stream())
                .map(MethodInfo::apiEndpoint)
                .filter(e -> e != null)
                .toList();

        if (endpoints.isEmpty()) {
            return;
        }

        sb.append("## API endpoints\n\n");
        for (ApiEndpointInfo endpoint : endpoints) {
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
            sb.append("\n");
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
