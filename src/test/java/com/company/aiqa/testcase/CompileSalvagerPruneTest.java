package com.company.aiqa.testcase;

import com.company.aiqa.model.TestCaseResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Taken from the mms script: merchantId and capabilityId were declared, read by
 * most of the suite, and assigned nowhere - so those tests errored with
 * "path parameter at index 0 is null" before sending anything.
 */
class CompileSalvagerPruneTest {

    private final CompileSalvager salvager = new CompileSalvager(null);

    private static TestCaseResult script(String body) {
        return new TestCaseResult("Api", "ApiTest.java", """
                package com.company.aiqa.generated;

                import io.restassured.http.ContentType;
                import org.testng.annotations.AfterClass;
                import org.testng.annotations.BeforeClass;
                import org.testng.annotations.Test;
                import static io.restassured.RestAssured.given;

                public class ApiTest {
                %s
                }
                """.formatted(body), null);
    }

    @Test
    void removesTheMethodsAndTheFieldTheyRead() {
        CompileSalvager.Salvaged pruned = salvager.dropMethodsUsingUnassignedFields(script("""
                    private String merchantId;

                    @Test
                    public void createsFeeForMerchant() {
                        given().when().post("/api/v1/merchants/{id}/fees", merchantId).then().statusCode(201);
                    }

                    @AfterClass
                    public void cleanup() {
                        given().when().delete("/api/v1/merchants/{id}", merchantId);
                    }
                """));

        assertEquals(2, pruned.removedMethods().size(), pruned.removedMethods().toString());
        assertTrue(pruned.removedMethods().contains("cleanup"), pruned.removedMethods().toString());
        assertFalse(pruned.script().testCode().contains("merchantId"), pruned.script().testCode());
    }

    @Test
    void keepsATestThatCreatesItsOwnFixture() {
        String source = """
                    @Test
                    public void createsFee() {
                        String merchantId = given().when().post("/api/v1/merchants").then().extract().path("data.id");
                        given().when().post("/api/v1/merchants/{id}/fees", merchantId).then().statusCode(201);
                    }
                """;
        CompileSalvager.Salvaged pruned = salvager.dropMethodsUsingUnassignedFields(script(source));

        assertEquals(0, pruned.removedMethods().size());
        assertTrue(pruned.script().testCode().contains("createsFee"));
    }

    @Test
    void keepsAFieldThatSetupAssigns() {
        CompileSalvager.Salvaged pruned = salvager.dropMethodsUsingUnassignedFields(script("""
                    private String merchantId;

                    @BeforeClass
                    public void setUp() {
                        merchantId = given().when().post("/api/v1/merchants").then().extract().path("data.id");
                    }

                    @Test
                    public void createsFee() {
                        given().when().post("/api/v1/merchants/{id}/fees", merchantId).then().statusCode(201);
                    }
                """));

        assertEquals(0, pruned.removedMethods().size());
    }

    /**
     * The shape that made the prune dangerous. merchantId is BOTH an unassigned
     * field and, in another test, a local holding an id that test created for
     * itself. Matching the bare name deleted twelve correct, self-contained mms
     * tests along with the broken ones.
     */
    @Test
    void keepsATestWhoseLocalShadowsTheOrphanField() {
        CompileSalvager.Salvaged pruned = salvager.dropMethodsUsingUnassignedFields(script("""
                    private String merchantId;

                    @Test
                    public void readsTheNullField() {
                        given().when().post("/api/v1/merchants/{id}/fees", merchantId).then().statusCode(201);
                    }

                    @Test
                    public void buildsItsOwnMerchant() {
                        String merchantId = given().when().post("/api/v1/merchants").then().extract().path("data.id");
                        given().when().post("/api/v1/merchants/{id}/stores", merchantId).then().statusCode(400);
                    }
                """));

        assertEquals(1, pruned.removedMethods().size(), pruned.removedMethods().toString());
        assertTrue(pruned.removedMethods().contains("readsTheNullField"), pruned.removedMethods().toString());
        assertTrue(pruned.script().testCode().contains("buildsItsOwnMerchant"), pruned.script().testCode());
    }

    /** this.x names the field even where a local shadows it. */
    @Test
    void aQualifiedReadIsStillAReadOfTheField() {
        CompileSalvager.Salvaged pruned = salvager.dropMethodsUsingUnassignedFields(script("""
                    private String merchantId;

                    @Test
                    public void readsTheFieldDespiteTheLocal() {
                        String merchantId = "shadow";
                        given().when().post("/api/v1/merchants/{id}/fees", this.merchantId).then().statusCode(201);
                    }
                """));

        assertEquals(1, pruned.removedMethods().size(), pruned.removedMethods().toString());
    }

    /**
     * feeConfigId, from the 2026-08-18 mms run: assigned by a private helper
     * nobody calls, and read by five tier tests that all errored on a null path
     * parameter. "Assigned somewhere" is not the question - "assigned by
     * something that runs first" is.
     */
    @Test
    void dropsTestsWhoseFixtureHelperIsNeverCalled() {
        CompileSalvager.Salvaged pruned = salvager.dropMethodsUsingUnassignedFields(script("""
                    private String feeConfigId;

                    private void createFeeConfiguration() {
                        feeConfigId = given().when().post("/api/v1/fees").then().extract().path("data.id");
                    }

                    @Test
                    public void addsATier() {
                        given().when().post("/api/v1/fees/{id}/tiers", feeConfigId).then().statusCode(201);
                    }
                """));

        assertEquals(1, pruned.removedMethods().size(), pruned.removedMethods().toString());
        assertTrue(pruned.removedMethods().contains("addsATier"), pruned.removedMethods().toString());
        // The helper assigns it, so the field is not an orphan and stays.
        assertTrue(pruned.script().testCode().contains("feeConfigId"), pruned.script().testCode());
    }

    /** A field another @Test happens to assign is not a fixture: TestNG orders nothing. */
    @Test
    void dropsATestRelyingOnAnotherTestsAssignment() {
        CompileSalvager.Salvaged pruned = salvager.dropMethodsUsingUnassignedFields(script("""
                    private String feeConfigId;

                    @Test
                    public void createsAFee() {
                        feeConfigId = given().when().post("/api/v1/fees").then().extract().path("data.id");
                    }

                    @Test
                    public void addsATier() {
                        given().when().post("/api/v1/fees/{id}/tiers", feeConfigId).then().statusCode(201);
                    }
                """));

        assertEquals(1, pruned.removedMethods().size(), pruned.removedMethods().toString());
        assertTrue(pruned.removedMethods().contains("addsATier"), pruned.removedMethods().toString());
    }

    /** The same read is sound once a @BeforeClass reaches the assignment. */
    @Test
    void keepsATestWhoseFieldASetupHelperAssigns() {
        CompileSalvager.Salvaged pruned = salvager.dropMethodsUsingUnassignedFields(script("""
                    private String feeConfigId;

                    @BeforeClass
                    public void setUp() {
                        createFeeConfiguration();
                    }

                    private void createFeeConfiguration() {
                        feeConfigId = given().when().post("/api/v1/fees").then().extract().path("data.id");
                    }

                    @Test
                    public void addsATier() {
                        given().when().post("/api/v1/fees/{id}/tiers", feeConfigId).then().statusCode(201);
                    }
                """));

        assertEquals(0, pruned.removedMethods().size(), pruned.removedMethods().toString());
    }

    /** A test that creates its own fixture into the field is self-sufficient. */
    @Test
    void keepsATestThatAssignsTheFieldItself() {
        CompileSalvager.Salvaged pruned = salvager.dropMethodsUsingUnassignedFields(script("""
                    private String feeConfigId;

                    @Test
                    public void updatesAFee() {
                        feeConfigId = given().when().post("/api/v1/fees").then().extract().path("data.id");
                        given().when().put("/api/v1/fees/{id}", feeConfigId).then().statusCode(200);
                    }
                """));

        assertEquals(0, pruned.removedMethods().size(), pruned.removedMethods().toString());
    }

    /**
     * A null-guarded @AfterClass costs nothing and asserts nothing, so it is
     * kept while the field survives - unlike the orphan case, where the field
     * itself goes and the cleanup would no longer compile.
     */
    @Test
    void keepsCleanupWhenTheFieldSurvives() {
        CompileSalvager.Salvaged pruned = salvager.dropMethodsUsingUnassignedFields(script("""
                    private String feeConfigId;

                    @BeforeClass
                    public void setUp() {
                        feeConfigId = given().when().post("/api/v1/fees").then().extract().path("data.id");
                    }

                    @AfterClass
                    public void cleanup() {
                        if (feeConfigId != null) {
                            given().when().delete("/api/v1/fees/{id}", feeConfigId);
                        }
                    }
                """));

        assertEquals(0, pruned.removedMethods().size(), pruned.removedMethods().toString());
    }
}
