package com.company.aiqa.openapi;

import com.company.aiqa.openapi.ApiContract.EndpointContract;
import com.company.aiqa.openapi.ApiContract.FieldDoc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cut down from mms's real document: the ApiResponse&lt;T&gt; envelope, a
 * $ref'd payload, an array of a $ref, enum values, and the length limits a
 * boundary case has to use.
 */
class OpenApiContractExtractorTest {

    private final OpenApiContractExtractor extractor = new OpenApiContractExtractor(new OpenApiProperties());
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode spec(String json) throws Exception {
        return mapper.readTree(json);
    }

    private EndpointContract only(List<EndpointContract> endpoints, String signature) {
        Optional<EndpointContract> match = endpoints.stream()
                .filter(e -> e.signature().equals(signature)).findFirst();
        assertTrue(match.isPresent(), "no " + signature + " in " + endpoints.stream()
                .map(EndpointContract::signature).toList());
        return match.get();
    }

    private FieldDoc field(List<FieldDoc> fields, String path) {
        Optional<FieldDoc> match = fields.stream().filter(f -> f.path().equals(path)).findFirst();
        assertTrue(match.isPresent(), "no '" + path + "' in " + fields.stream().map(FieldDoc::path).toList());
        return match.get();
    }

    private static final String MMS = """
            {
              "openapi": "3.1.0",
              "paths": {
                "/api/v1/fees": {
                  "post": {
                    "summary": "Create a fee configuration",
                    "requestBody": {
                      "content": {
                        "application/json": {
                          "schema": { "$ref": "#/components/schemas/CreateFeeConfigRequest" }
                        }
                      }
                    },
                    "responses": {
                      "200": {
                        "description": "OK",
                        "content": {
                          "*/*": { "schema": { "$ref": "#/components/schemas/ApiResponseFee" } }
                        }
                      }
                    }
                  }
                },
                "/api/v1/fees/{id}": {
                  "get": {
                    "parameters": [
                      { "name": "id", "in": "path", "required": true,
                        "schema": { "type": "string", "format": "uuid" } },
                      { "name": "verbose", "in": "query", "required": false,
                        "schema": { "type": "boolean" } }
                    ],
                    "responses": { "204": { "description": "No Content" } }
                  }
                }
              },
              "components": {
                "schemas": {
                  "CreateFeeConfigRequest": {
                    "type": "object",
                    "required": ["feeType", "effectiveFrom"],
                    "properties": {
                      "merchantId": { "type": "string", "format": "uuid" },
                      "capabilityCode": { "type": "string", "maxLength": 50, "minLength": 0,
                                          "example": "ONLINE_PAYMENT" },
                      "feeType": { "type": "string", "enum": ["FLAT", "PERCENTAGE", "TIERED"] },
                      "effectiveFrom": { "type": "string", "format": "date" }
                    }
                  },
                  "ApiResponseFee": {
                    "type": "object",
                    "properties": {
                      "success": { "type": "boolean" },
                      "data": { "$ref": "#/components/schemas/FeeResponse" }
                    }
                  },
                  "FeeResponse": {
                    "type": "object",
                    "properties": {
                      "id": { "type": "string", "format": "uuid" },
                      "tiers": { "type": "array", "items": { "$ref": "#/components/schemas/Tier" } }
                    }
                  },
                  "Tier": {
                    "type": "object",
                    "properties": { "rate": { "type": "number" } }
                  }
                }
              }
            }
            """;

    @Test
    void readsEveryOperation() throws Exception {
        List<EndpointContract> endpoints = extractor.extract(spec(MMS), "mms-app");

        assertEquals(List.of("POST /api/v1/fees", "GET /api/v1/fees/{id}"),
                endpoints.stream().map(EndpointContract::signature).toList());
        assertEquals("Create a fee configuration", only(endpoints, "POST /api/v1/fees").summary());
    }

    @Test
    void resolvesTheRequestRefAndItsConstraints() throws Exception {
        List<FieldDoc> fields = only(extractor.extract(spec(MMS), "mms"), "POST /api/v1/fees").requestFields();

        assertEquals("string(uuid)", field(fields, "merchantId").type());
        assertEquals("maxLength 50", field(fields, "capabilityCode").constraints());
        assertEquals("ONLINE_PAYMENT", field(fields, "capabilityCode").example());
        assertEquals(List.of("FLAT", "PERCENTAGE", "TIERED"), field(fields, "feeType").enumValues());
        assertTrue(field(fields, "feeType").required());
        assertFalse(field(fields, "merchantId").required());
    }

    /**
     * springdoc emits minLength 0 for every unannotated string. Carrying it
     * would invite a "minimum length" boundary case against a rule that does
     * not exist.
     */
    @Test
    void dropsAMinLengthOfZero() throws Exception {
        List<FieldDoc> fields = only(extractor.extract(spec(MMS), "mms"), "POST /api/v1/fees").requestFields();

        assertFalse(field(fields, "capabilityCode").constraints().contains("minLength"),
                field(fields, "capabilityCode").constraints());
    }

    /**
     * The whole point of the feature: a response field at the path REST Assured
     * takes. Reading the id from "id" rather than "data.id" returns null and
     * kills the next call in the test.
     */
    @Test
    void flattensTheResponseEnvelopeToRestAssuredPaths() throws Exception {
        List<FieldDoc> fields = only(extractor.extract(spec(MMS), "mms"), "POST /api/v1/fees")
                .responses().get(0).fields();
        List<String> paths = fields.stream().map(FieldDoc::path).toList();

        assertTrue(paths.contains("success"), paths.toString());
        assertTrue(paths.contains("data.id"), paths.toString());
        assertTrue(paths.contains("data.tiers[].rate"), paths.toString());
        assertFalse(paths.contains("id"), "a bare 'id' would be the wrong path: " + paths);
    }

    @Test
    void separatesPathParamsFromQueryParams() throws Exception {
        EndpointContract get = only(extractor.extract(spec(MMS), "mms"), "GET /api/v1/fees/{id}");

        assertEquals(1, get.pathParams().size(), get.pathParams().toString());
        assertEquals("id", get.pathParams().get(0).name());
        assertTrue(get.pathParams().get(0).required());
        assertEquals(1, get.queryParams().size(), get.queryParams().toString());
        assertEquals("verbose", get.queryParams().get(0).name());
    }

    @Test
    void recordsAStatusThatDocumentsNoBody() throws Exception {
        EndpointContract get = only(extractor.extract(spec(MMS), "mms"), "GET /api/v1/fees/{id}");

        assertEquals("204", get.responses().get(0).status());
        assertTrue(get.responses().get(0).fields().isEmpty());
    }

    /** A type that contains itself must stop, not recurse until the heap goes. */
    @Test
    void stopsOnASelfReferencingSchema() throws Exception {
        JsonNode spec = spec("""
                {
                  "openapi": "3.0.1",
                  "paths": {
                    "/tree": { "get": { "responses": { "200": { "content": {
                      "application/json": { "schema": { "$ref": "#/components/schemas/Node" } } } } } } }
                  },
                  "components": { "schemas": {
                    "Node": { "type": "object", "properties": {
                      "name": { "type": "string" },
                      "parent": { "$ref": "#/components/schemas/Node" }
                    } }
                  } }
                }
                """);

        List<FieldDoc> fields = extractor.extract(spec, "svc").get(0).responses().get(0).fields();

        assertTrue(fields.stream().anyMatch(f -> f.path().equals("name")),
                fields.stream().map(FieldDoc::path).toList().toString());
        assertTrue(fields.size() < 20, "recursion was not bounded: " + fields.size());
    }

    /** allOf is composition - both halves' fields have to survive it. */
    @Test
    void mergesAllOfRatherThanPickingOne() throws Exception {
        JsonNode spec = spec("""
                {
                  "openapi": "3.0.1",
                  "paths": {
                    "/x": { "post": { "requestBody": { "content": {
                      "application/json": { "schema": { "allOf": [
                        { "$ref": "#/components/schemas/Base" },
                        { "type": "object", "properties": { "extra": { "type": "string" } } }
                      ] } } } }, "responses": {} } }
                  },
                  "components": { "schemas": {
                    "Base": { "type": "object", "properties": { "id": { "type": "integer" } } }
                  } }
                }
                """);

        List<String> paths = extractor.extract(spec, "svc").get(0).requestFields()
                .stream().map(FieldDoc::path).toList();

        assertTrue(paths.contains("id") && paths.contains("extra"), paths.toString());
    }
}
