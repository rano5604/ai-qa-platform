package com.company.aiqa.testcase;

import com.company.aiqa.model.TestCaseResult;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    private static void assertEqualsOnce(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            count++;
        }
        assertTrue(count == 1, "expected exactly one '" + needle + "' but found " + count + " in:\n" + haystack);
    }
}
