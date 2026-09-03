package com.company.aiqa.testcase;

import com.company.aiqa.model.TestCaseResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The base-URI cases here are the mms failure: a merged script with no
 * {@code @BeforeClass} let REST Assured fall back to localhost:8080, so every
 * request in a 30-test run went to the wrong host.
 */
class AutomationScriptMergerTest {

    private final AutomationScriptMerger merger = new AutomationScriptMerger();

    private static TestCaseResult script(String body) {
        String source = """
                package com.company.aiqa.generated;

                import io.restassured.RestAssured;
                import io.restassured.http.ContentType;
                import org.testng.annotations.BeforeClass;
                import org.testng.annotations.Test;
                import static io.restassured.RestAssured.given;

                public class SomeApiTest {
                %s
                }
                """.formatted(body);
        return new TestCaseResult("SomeApi", "SomeApiTest.java", source, null);
    }

    private String merged(String body) {
        return merger.merge(List.of(script(body)), "abc123").testCode();
    }

    @Test
    void addsSetupWhenTheScriptHasNone() {
        String out = merged("""
                    @Test
                    public void createsFee() {
                        given().contentType(ContentType.JSON).when().post("/api/v1/fees").then().statusCode(201);
                    }
                """);

        assertTrue(out.contains("@BeforeClass"), out);
        assertTrue(out.contains("RestAssured.baseURI = System.getProperty(\"baseUri\""), out);
    }

    @Test
    void addsTheAssignmentToAnExistingSetupThatForgotIt() {
        String out = merged("""
                    private String merchantId;

                    @BeforeClass
                    public void setUp() {
                        merchantId = "seeded";
                    }
                """);

        int assignment = out.indexOf("RestAssured.baseURI");
        int fixture = out.indexOf("merchantId = \"seeded\"");
        assertTrue(assignment >= 0, out);
        // The target has to be set before anything tries to build a fixture against it.
        assertTrue(assignment < fixture, out);
    }

    @Test
    void rewritesAHardcodedTargetToReadTheRunsBaseUri() {
        String out = merged("""
                    @BeforeClass
                    public void setUp() {
                        RestAssured.baseURI = "http://localhost:9999";
                    }
                """);

        assertTrue(out.contains("System.getProperty(\"baseUri\", \"http://localhost:9999\")"), out);
    }

    @Test
    void leavesACorrectSetupAlone() {
        String out = merged("""
                    @BeforeClass
                    public void setUp() {
                        RestAssured.baseURI = System.getProperty("baseUri", "http://localhost:8080");
                    }
                """);

        assertEqualsOnce(out, "RestAssured.baseURI");
        assertFalse(out.contains("aiqaConfigureBaseUri"), out);
    }

    /**
     * The mms failure: the script asserted .body("success", is(false)) on a
     * rejected request, but that field only exists in the SUCCESS envelope -
     * the error one is {"status":400,"error":...}. Four tests failed against a
     * service that had answered correctly.
     */
    @Test
    void dropsBodyAssertionsFromErrorChains() {
        String out = merged("""
                    @Test
                    public void rejectsBadEmail() {
                        given().contentType(ContentType.JSON).body("{}")
                                .when().post("/api/v1/merchants")
                                .then().statusCode(400).body("success", is(false));
                    }
                """);

        assertTrue(out.contains("statusCode(400)"), out);
        assertFalse(out.contains("\"success\""), out);
    }

    @Test
    void dropsThemWhenTheStatusIsAMatcherOrComesLast() {
        String out = merged("""
                    @Test
                    public void rejectsBadPrice() {
                        given().contentType(ContentType.JSON).body("{}")
                                .when().post("/api/v1/fees")
                                .then().body("error", is("Bad Request")).statusCode(anyOf(is(400), is(422)));
                    }
                """);

        assertFalse(out.contains("\"error\""), out);
        assertTrue(out.contains("statusCode(anyOf(is(400), is(422)))"), out);
    }

    /** A 2xx body assertion is usually the point of the test - proving a value was stored, or truncated. */
    @Test
    void keepsBodyAssertionsOnSuccessChains() {
        String out = merged("""
                    @Test
                    public void truncatesPrice() {
                        given().contentType(ContentType.JSON).body("{}")
                                .when().post("/api/v1/orders")
                                .then().statusCode(200).body("price", equalTo(19));
                    }
                """);

        assertTrue(out.contains("body(\"price\", equalTo(19))"), out);
    }

    /** The request's own payload is also called body(..) - it must never be mistaken for an assertion. */
    @Test
    void keepsTheRequestPayload() {
        String out = merged("""
                    @Test
                    public void rejectsBadEmail() {
                        String payload = "{}";
                        given().contentType(ContentType.JSON).body(payload)
                                .when().post("/api/v1/merchants")
                                .then().statusCode(400);
                    }
                """);

        assertTrue(out.contains("body(payload)"), out);
    }

    // ---------------------------------------------- incremental generation ----
    // What makes "generate only the new cases, append to the existing script"
    // possible at all: the trace-back comment the prompt already requires
    // above every @Test ("put its Test Case ID in a comment above the method
    // so a human can trace script back to case"), read back out of a real file.

    @Test
    void mergedFileNameIsDeterministicFromTheCommitHashAlone() {
        assertEquals("AutomationTest_abc123.java", merger.mergedFileName("abc123"));
        assertEquals(merger.mergedFileName("abc123"), merger.mergedFileName("abc123"));
    }

    @Test
    void extractsEveryTraceBackIdAboveATestMethod() {
        String existing = script("""
                    // TC-001
                    @Test
                    public void createsFee() {
                        given().contentType(ContentType.JSON).when().post("/api/v1/fees").then().statusCode(201);
                    }

                    // TC-003
                    @Test
                    public void rejectsBadFee() {
                        given().contentType(ContentType.JSON).when().post("/api/v1/fees").then().statusCode(400);
                    }
                """).testCode();

        assertEquals(Set.of("TC-001", "TC-003"), merger.alreadyAutomatedCaseIds(existing));
    }

    @Test
    void aScriptWithNoTraceBackCommentsYieldsNoIds() {
        String existing = script("""
                    @Test
                    public void createsFee() {
                        given().contentType(ContentType.JSON).when().post("/api/v1/fees").then().statusCode(201);
                    }
                """).testCode();

        assertTrue(merger.alreadyAutomatedCaseIds(existing).isEmpty());
    }

    @Test
    void nullOrBlankSourceYieldsNoIdsRatherThanThrowing() {
        assertTrue(merger.alreadyAutomatedCaseIds(null).isEmpty());
        assertTrue(merger.alreadyAutomatedCaseIds("").isEmpty());
        assertTrue(merger.alreadyAutomatedCaseIds("   ").isEmpty());
    }

    @Test
    void toleratesWindowsLineEndingsBetweenTheCommentAndTheAnnotation() {
        String existing = script("// TC-007\r\n    @Test\r\n    public void x() { }\r\n").testCode();

        assertEquals(Set.of("TC-007"), merger.alreadyAutomatedCaseIds(existing));
    }

    /**
     * The actual mechanism a resumed generation uses: prepend the existing
     * file (read off disk) to the fresh batch's scripts before merging, so the
     * old method survives and the new one is added alongside it - not a
     * literal text append, but the observable result is the same, and it goes
     * through the exact same collision-safe path a same-run batch would.
     */
    @Test
    void anExistingScriptPrependedToANewBatchKeepsBothMethods() {
        TestCaseResult existing = script("""
                    // TC-001
                    @Test
                    public void createsFee() {
                        given().contentType(ContentType.JSON).when().post("/api/v1/fees").then().statusCode(201);
                    }
                """);
        TestCaseResult freshBatch = script("""
                    // TC-002
                    @Test
                    public void deletesFee() {
                        given().when().delete("/api/v1/fees/1").then().statusCode(204);
                    }
                """);

        String out = merger.merge(List.of(existing, freshBatch), "abc123").testCode();

        assertTrue(out.contains("createsFee"), out);
        assertTrue(out.contains("deletesFee"), out);
        assertEquals(Set.of("TC-001", "TC-002"), merger.alreadyAutomatedCaseIds(out));
    }

    /**
     * Filtering upstream (AutomationGenerationService excludes already-covered
     * cases before calling the model) is what's SUPPOSED to prevent this, but
     * the merger's own collision handling is the backstop if it doesn't: a
     * method name collision renames the newer one rather than losing either.
     */
    @Test
    void aMethodNameCollisionBetweenExistingAndFreshRenamesRatherThanDropping() {
        TestCaseResult existing = script("""
                    // TC-001
                    @Test
                    public void createsFee() {
                        given().contentType(ContentType.JSON).when().post("/api/v1/fees").then().statusCode(201);
                    }
                """);
        TestCaseResult freshBatch = script("""
                    // TC-001
                    @Test
                    public void createsFee() {
                        given().contentType(ContentType.JSON).when().post("/api/v1/fees").then().statusCode(201);
                    }
                """);

        String out = merger.merge(List.of(existing, freshBatch), "abc123").testCode();

        assertTrue(out.contains("public void createsFee()"), out);
        assertTrue(out.contains("public void createsFee2()"), out);
    }

    /**
     * The TailorBookApp failure: two batches each emitted an identical private
     * helper type ({@code Prereqs}), and Java has no overloading for types, so
     * the merged file had "class Prereqs is already defined" and every one of
     * its tests was lost (a run reported as 0 tests). The duplicate must be
     * dropped, once, keeping the single shared declaration.
     */
    @Test
    void anIdenticalNestedHelperTypeAcrossBatchesIsDeclaredOnlyOnce() {
        String helperAndTest = """
                    private static class Prereqs {
                        final Long shopId;
                        Prereqs(Long shopId) {
                            this.shopId = shopId;
                        }
                    }

                    // TC-%s
                    @Test
                    public void createsOrder%s() {
                        Prereqs p = new Prereqs(1L);
                        given().when().post("/api/orders/" + p.shopId).then().statusCode(201);
                    }
                """;
        TestCaseResult batchOne = script(helperAndTest.formatted("001", "One"));
        TestCaseResult batchTwo = script(helperAndTest.formatted("002", "Two"));

        String out = merger.merge(List.of(batchOne, batchTwo), "abc123").testCode();

        assertEqualsOnce(out, "class Prereqs");
        // Both tests - which each reference the shared type - must survive.
        assertTrue(out.contains("createsOrderOne"), out);
        assertTrue(out.contains("createsOrderTwo"), out);
    }

    /**
     * A differing type of the same name can't both survive under one name, so
     * the first is kept and the file still compiles - the whole point, versus a
     * duplicate declaration that fails every test in the file.
     */
    @Test
    void aDifferingNestedTypeOfTheSameNameKeepsTheFirstAndStaysCompilable() {
        TestCaseResult batchOne = script("""
                    private static class Prereqs {
                        final Long shopId;
                        Prereqs(Long shopId) { this.shopId = shopId; }
                    }
                """);
        TestCaseResult batchTwo = script("""
                    private static class Prereqs {
                        final Long shopId;
                        final Long itemId;
                        Prereqs(Long shopId, Long itemId) { this.shopId = shopId; this.itemId = itemId; }
                    }
                """);

        String out = merger.merge(List.of(batchOne, batchTwo), "abc123").testCode();

        assertEqualsOnce(out, "class Prereqs");
        // First kept - the two-arg constructor from the second batch is gone.
        assertFalse(out.contains("Long itemId"), out);
    }

    /**
     * The regenerated TailorBookApp file: a Prereqs holder whose constructor
     * assigns this.shopId/this.itemId but never declares the fields - "cannot
     * find symbol: variable shopId", which all-or-nothing takes the whole file
     * with it. The merger declares the fields, typed from the constructor params.
     */
    @Test
    void declaresHolderFieldsTheConstructorAssignsButNeverDeclared() {
        String out = merged("""
                    private static class Prereqs {
                        Prereqs(Long shopId, Long itemId) {
                            this.shopId = shopId;
                            this.itemId = itemId;
                        }
                    }

                    // TC-001
                    @Test
                    public void usesPrereqs() {
                        Prereqs p = new Prereqs(1L, 2L);
                        given().when().get("/api/shops/" + p.shopId).then().statusCode(200);
                    }
                """);

        assertTrue(out.contains("Long shopId"), out);
        assertTrue(out.contains("Long itemId"), out);
        // Declared final, as a write-once holder is.
        assertTrue(out.replaceAll("\\s+", " ").contains("final Long shopId"), out);
    }

    /**
     * A file already on disk - whose cases were all previously automated, so
     * regeneration returned it untouched without a merge - never goes through the
     * merge-time repairs. withCompileSafetyRepairs is the execution-time analogue
     * of withBaseUriHonoured: it fixes the missing holder fields so the file
     * compiles and runs today, no regeneration round needed.
     */
    @Test
    void executionTimeRepairAddsMissingHolderFieldsToAnOnDiskScript() {
        String onDisk = """
                package com.company.aiqa.generated;

                import io.restassured.RestAssured;
                import static io.restassured.RestAssured.given;

                public class AutomationTest_x {
                    private static class Prereqs {
                        Prereqs(Long shopId, Long itemId) {
                            this.shopId = shopId;
                            this.itemId = itemId;
                        }
                    }
                }
                """;
        TestCaseResult repaired = merger.withCompileSafetyRepairs(
                new TestCaseResult("AutomationTest_x", "AutomationTest_x.java", onDisk, null));

        String out = repaired.testCode();
        assertTrue(out.replaceAll("\\s+", " ").contains("final Long shopId"), out);
        assertTrue(out.replaceAll("\\s+", " ").contains("final Long itemId"), out);
    }

    /**
     * The scoping bug that "not initialized in the default constructor" exposed:
     * a holder that declares SOME fields but not one the constructor assigns
     * ({@code this.token} with no {@code token} field) must get that field on the
     * HOLDER - never on the top-level test class, which has only a default
     * constructor and would then fail to compile with an uninitialised final.
     */
    @Test
    void addsAHolderFieldToTheHolderNotTheEnclosingClass() {
        String onDisk = """
                package com.company.aiqa.generated;

                import static io.restassured.RestAssured.given;

                public class AutomationTest_z {

                    // TC-001
                    @org.testng.annotations.Test
                    public void t() {
                        given().when().get("/api/x").then().statusCode(200);
                    }

                    private static class AuthInfo {
                        final Long shopId;
                        AuthInfo(String token, Long shopId) {
                            this.token = token;
                            this.shopId = shopId;
                        }
                    }
                }
                """;
        TestCaseResult out = merger.withCompileSafetyRepairs(
                new TestCaseResult("AutomationTest_z", "AutomationTest_z.java", onDisk, null));

        String code = out.testCode();
        String flat = code.replaceAll("\\s+", " ");
        // token is declared on AuthInfo...
        assertTrue(flat.contains("final String token"), code);
        // ...and the top-level class gained NO final field (it has only a default ctor).
        String topLevelBody = code.substring(code.indexOf("class AutomationTest_z"),
                code.indexOf("class AuthInfo"));
        assertFalse(topLevelBody.replaceAll("\\s+", " ").contains("final String token"), code);
        assertFalse(topLevelBody.replaceAll("\\s+", " ").contains("final Long shopId"), code);
    }

    /** A well-formed on-disk file must be returned untouched by the execution-time repair. */
    @Test
    void executionTimeRepairLeavesAGoodFileUnchanged() {
        String onDisk = """
                package com.company.aiqa.generated;

                import static io.restassured.RestAssured.given;

                public class AutomationTest_y {
                    @org.testng.annotations.Test
                    public void t() {
                        given().when().get("/api/x").then().statusCode(200);
                    }
                }
                """;
        TestCaseResult in = new TestCaseResult("AutomationTest_y", "AutomationTest_y.java", onDisk, null);
        TestCaseResult out = merger.withCompileSafetyRepairs(in);

        assertEquals(in.testCode(), out.testCode());
    }

    /** A holder that already declares its fields must not gain duplicate declarations. */
    @Test
    void leavesAWellFormedHolderAlone() {
        String out = merged("""
                    private static class Prereqs {
                        final Long shopId;
                        Prereqs(Long shopId) { this.shopId = shopId; }
                    }
                """);

        // Exactly one FIELD declaration - the repair must not add a second.
        assertEqualsOnce(out.replaceAll("\\s+", " "), "final Long shopId");
    }

    private static void assertEqualsOnce(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            count++;
        }
        assertTrue(count == 1, "expected exactly one '" + needle + "' but found " + count + " in:\n" + haystack);
    }
}
