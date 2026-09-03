package com.company.aiqa.testcase;

import com.company.aiqa.model.TestCaseResult;
import com.company.aiqa.openapi.ApiContract;
import com.company.aiqa.openapi.ApiContract.EndpointContract;
import com.company.aiqa.openapi.ApiContract.FieldDoc;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scenario this exists for: mms's live OpenAPI document lists "userId" as
 * required on CreateMerchantRequest, and nine generated tests still omitted
 * it - every one of them 400'd, all with the same validation error, and the
 * contract had the answer the whole time. Generic on purpose: nothing here
 * names mms, a path, or a field - the fixture builds a throwaway contract for
 * each case, the same way any other target's document would be read.
 */
class ContractRequiredFieldsRepairTest {

    private final AutomationScriptMerger merger = new AutomationScriptMerger();

    private static TestCaseResult script(String body) {
        String source = """
                package com.company.aiqa.generated;

                import io.restassured.RestAssured;
                import io.restassured.http.ContentType;
                import io.restassured.response.Response;
                import org.testng.annotations.BeforeClass;
                import org.testng.annotations.Test;
                import static io.restassured.RestAssured.given;
                import static org.hamcrest.Matchers.*;

                public class SomeApiTest {
                %s
                }
                """.formatted(body);
        return new TestCaseResult("SomeApi", "SomeApiTest.java", source, null);
    }

    private static FieldDoc required(String path, String type) {
        return new FieldDoc(path, type, true, List.of(), "", "", "");
    }

    private static FieldDoc requiredEnum(String path, String type, List<String> values) {
        return new FieldDoc(path, type, true, values, "", "", "");
    }

    private static EndpointContract endpoint(String method, String path, FieldDoc... fields) {
        return endpoint(method, path, "", fields);
    }

    private static EndpointContract endpoint(String method, String path, String requestSchemaName, FieldDoc... fields) {
        return new EndpointContract("some-service", method, path, "", List.of(), List.of(),
                "application/json", List.of(fields), List.of(), requestSchemaName);
    }

    private static ApiContract contract(EndpointContract... endpoints) {
        return new ApiContract(List.of(), List.of(endpoints), List.of());
    }

    private String merged(String body, ApiContract contract) {
        return merger.merge(List.of(script(body)), "abc123", contract).testCode();
    }

    private String merged(String body, ApiContract contract, java.nio.file.Path repoRoot) {
        return merger.merge(List.of(script(body)), "abc123", contract, repoRoot).testCode();
    }

    @Test
    void injectsAMissingRequiredFieldUsingTheFormattedCallAlreadyThere() {
        ApiContract contract = contract(endpoint("POST", "/api/v1/merchants",
                required("userId", "integer(int64)"), required("legalName", "string")));

        String out = merged("""
                    @Test
                    public void createsMerchant() {
                        String body = \"""
                            {
                              "legalName": "%s"
                            }
                            \""".formatted("Acme");
                        given().contentType(ContentType.JSON).body(body).when().post("/api/v1/merchants").then().statusCode(201);
                    }
                """, contract);

        assertTrue(out.contains("\"userId\""), out);
        assertTrue(out.contains("System.currentTimeMillis()"), out);
        // legalName was already present - must not be touched or duplicated.
        assertEquals(1, out.split("\"legalName\"", -1).length - 1, out);
    }

    @Test
    void leavesAFieldAloneWhenTheModelAlreadyNamedIt() {
        ApiContract contract = contract(endpoint("POST", "/api/v1/merchants",
                required("userId", "integer(int64)")));

        String out = merged("""
                    @Test
                    public void createsMerchant() {
                        String body = \"""
                            {
                              "userId": 42
                            }
                            \""";
                        given().contentType(ContentType.JSON).body(body).when().post("/api/v1/merchants").then().statusCode(201);
                    }
                """, contract);

        assertEquals(1, out.split("\"userId\"", -1).length - 1, out);
        assertTrue(out.contains("\"userId\": 42"), out);
    }

    @Test
    void matchesAPathParamAgainstAContractPlaceholder() {
        ApiContract contract = contract(endpoint("POST", "/api/v1/merchants/{merchantId}/stores",
                required("name", "string")));

        String out = merged("""
                    @Test
                    public void createsStore() {
                        String merchantId = "m-1";
                        String body = \"""
                            {
                              "city": "Riyadh"
                            }
                            \""";
                        given().contentType(ContentType.JSON).body(body).when().post("/api/v1/merchants/{merchantId}/stores", merchantId).then().statusCode(201);
                    }
                """, contract);

        assertTrue(out.contains("\"name\""), out);
    }

    @Test
    void aFieldWithNoFormattedCallFallsBackToTheContractExample() {
        ApiContract contract = contract(endpoint("POST", "/api/v1/capabilities",
                new FieldDoc("description", "string", true, List.of(), "", "Accepts contactless payments", "")));

        String out = merged("""
                    @Test
                    public void createsCapability() {
                        String body = \"""
                            {
                              "capabilityCode": "NFC"
                            }
                            \""";
                        given().contentType(ContentType.JSON).body(body).when().post("/api/v1/capabilities").then().statusCode(201);
                    }
                """, contract);

        assertTrue(out.contains("\"description\": \"Accepts contactless payments\""), out);
    }

    @Test
    void anEnumFieldGetsItsFirstDeclaredValue() {
        ApiContract contract = contract(endpoint("POST", "/api/v1/merchants",
                requiredEnum("businessType", "string", List.of("CORPORATION", "LLC"))));

        String out = merged("""
                    @Test
                    public void createsMerchant() {
                        String body = \"""
                            {
                              "legalName": "Acme"
                            }
                            \""";
                        given().contentType(ContentType.JSON).body(body).when().post("/api/v1/merchants").then().statusCode(201);
                    }
                """, contract);

        assertTrue(out.contains("\"businessType\": \"CORPORATION\""), out);
    }

    @Test
    void aFieldTheContractDoesNotMarkRequiredIsInjectedWhenTheDtoSourceRequiresIt(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path repoRoot) throws java.io.IOException {
        // The exact mms shape: "userId" the contract itself marks required,
        // "accountNo" it merely DOCUMENTS (present, but required: false) while
        // the live service 400s without it - because the DTO's own @NotBlank
        // never made it into the generated OpenAPI schema's "required" array.
        ApiContract contract = contract(endpoint("POST", "/api/v1/merchants", "CreateMerchantRequest",
                required("userId", "integer(int64)"),
                new FieldDoc("accountNo", "string", false, List.of(), "", "01670636125", "")));

        java.nio.file.Path dto = repoRoot.resolve("src/main/java/com/example/mms/dto/CreateMerchantRequest.java");
        java.nio.file.Files.createDirectories(dto.getParent());
        java.nio.file.Files.writeString(dto, """
                package com.example.mms.dto;

                import jakarta.validation.constraints.NotBlank;
                import jakarta.validation.constraints.NotNull;

                public class CreateMerchantRequest {
                    @NotNull
                    private Long userId;

                    @NotBlank
                    private String accountNo;
                }
                """);

        String out = merged("""
                    @Test
                    public void createsMerchant() {
                        String body = \"""
                            {
                              "legalName": "%s"
                            }
                            \""".formatted("Acme");
                        given().contentType(ContentType.JSON).body(body).when().post("/api/v1/merchants").then().statusCode(201);
                    }
                """, contract, repoRoot);

        assertTrue(out.contains("\"userId\""), out);
        // A .formatted(...) call is already there to extend, so this prefers a
        // per-run-unique value over the contract's static example - same reason
        // createPreconditionData's own merchantId is timestamp-based, not fixed.
        assertTrue(out.contains("\"accountNo\": \"%s\""), out);
        assertTrue(out.contains("\"gen-\" + System.currentTimeMillis()"), out);
    }

    @Test
    void sourceGroundingNeverInventsAFieldTheContractNeverMentionedAtAll(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path repoRoot) throws java.io.IOException {
        // Contract documents ONLY userId - "internalAuditFlag" exists on the DTO
        // with @NotNull, but never appears in the OpenAPI schema at all (not even
        // as an optional property). Ground truth for what a REQUEST needs is the
        // contract's field list; source only upgrades required-ness within it.
        ApiContract contract = contract(endpoint("POST", "/api/v1/merchants", "CreateMerchantRequest",
                required("userId", "integer(int64)")));

        java.nio.file.Path dto = repoRoot.resolve("CreateMerchantRequest.java");
        java.nio.file.Files.writeString(dto, """
                package com.example;

                import jakarta.validation.constraints.NotNull;

                public class CreateMerchantRequest {
                    @NotNull
                    private Long userId;

                    @NotNull
                    private Boolean internalAuditFlag;
                }
                """);

        String out = merged("""
                    @Test
                    public void createsMerchant() {
                        String body = \"""
                            {
                              "legalName": "Acme"
                            }
                            \""";
                        given().contentType(ContentType.JSON).body(body).when().post("/api/v1/merchants").then().statusCode(201);
                    }
                """, contract, repoRoot);

        assertFalse(out.contains("internalAuditFlag"), out);
    }

    @Test
    void nullRepoRootMeansOnlyTheContractHalfRuns() {
        ApiContract contract = contract(endpoint("POST", "/api/v1/merchants", "CreateMerchantRequest",
                new FieldDoc("accountNo", "string", false, List.of(), "", "01670636125", "")));

        String out = merged("""
                    @Test
                    public void createsMerchant() {
                        String body = \"""
                            {
                              "legalName": "Acme"
                            }
                            \""";
                        given().contentType(ContentType.JSON).body(body).when().post("/api/v1/merchants").then().statusCode(201);
                    }
                """, contract, null);

        // accountNo is not required per the contract, and there's no repo to
        // check the DTO's own annotations against - so it must stay untouched.
        assertFalse(out.contains("accountNo"), out);
    }

    @Test
    void nestedRequiredFieldsAreNeverTouched() {
        ApiContract contract = contract(endpoint("POST", "/api/v1/merchants",
                required("address.city", "string")));

        String out = merged("""
                    @Test
                    public void createsMerchant() {
                        String body = \"""
                            {
                              "legalName": "Acme"
                            }
                            \""";
                        given().contentType(ContentType.JSON).body(body).when().post("/api/v1/merchants").then().statusCode(201);
                    }
                """, contract);

        assertFalse(out.contains("address.city"), out);
        assertFalse(out.contains("\"city\""), out);
    }

    @Test
    void anEmptyContractIsANoOpJustLikeBeforeThisRepairExisted() {
        String out = merged("""
                    @Test
                    public void createsMerchant() {
                        String body = \"""
                            {
                              "legalName": "Acme"
                            }
                            \""";
                        given().contentType(ContentType.JSON).body(body).when().post("/api/v1/merchants").then().statusCode(201);
                    }
                """, ApiContract.empty());

        assertFalse(out.contains("userId"), out);
        assertEquals(1, out.split("\"legalName\"", -1).length - 1, out);
    }

    @Test
    void theTwoArgMergeOverloadStillWorksUnchanged() {
        TestCaseResult result = merger.merge(List.of(script("""
                    @Test
                    public void createsFee() {
                        given().contentType(ContentType.JSON).when().post("/api/v1/fees").then().statusCode(201);
                    }
                """)), "abc123");

        assertTrue(result.testCode().contains("createsFee"), result.testCode());
    }
}
