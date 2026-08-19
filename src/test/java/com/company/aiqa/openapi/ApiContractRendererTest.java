package com.company.aiqa.openapi;

import com.company.aiqa.openapi.ApiContract.EndpointContract;
import com.company.aiqa.openapi.ApiContract.FieldDoc;
import com.company.aiqa.openapi.ApiContract.ResponseContract;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiContractRendererTest {

    private final OpenApiProperties properties = new OpenApiProperties();
    private final ApiContractRenderer renderer = new ApiContractRenderer(properties);

    private static FieldDoc f(String path, String type) {
        return new FieldDoc(path, type, false, List.of(), "", "", "");
    }

    private static EndpointContract endpoint(String method, String path, List<FieldDoc> request,
                                             List<FieldDoc> response) {
        return new EndpointContract("svc", method, path, "", List.of(), List.of(),
                request.isEmpty() ? "" : "application/json", request,
                List.of(new ResponseContract("200", "OK", response)));
    }

    private static ApiContract contract(EndpointContract... endpoints) {
        return new ApiContract(List.of(OpenApiSource.url("svc", "http://host/api-docs", "test")),
                List.of(endpoints), List.of());
    }

    /** A case citing a real id must still match the {id} the contract declares. */
    @Test
    void matchesAPathWithParametersAgainstACaseCitingRealIds() {
        EndpointContract tiers = endpoint("POST", "/api/v1/fees/{id}/tiers", List.of(), List.of());

        assertTrue(renderer.mentions(
                "Send POST /api/v1/fees/f0000000-0000-0000-0000-000000000000/tiers with a body", tiers));
    }

    @Test
    void matchesOnTheResourceWordWhenTheCaseIsProse() {
        EndpointContract fees = endpoint("GET", "/api/v1/fees", List.of(), List.of());

        assertTrue(renderer.mentions("List every fees record for the merchant", fees));
        assertFalse(renderer.mentions("Register a terminal against a store", fees));
    }

    /**
     * An operation the batch names is rendered in full even when it is not
     * first, and an unrelated one that no longer fits degrades to a signature
     * rather than pushing the relevant one out.
     */
    @Test
    void spendsTheBudgetOnTheOperationsTheBatchWillCall() {
        List<FieldDoc> many = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(f("widgetProperty" + i, "string"));
        }
        ApiContract contract = contract(
                endpoint("POST", "/api/v1/widgets", many, many),
                endpoint("POST", "/api/v1/fees", List.of(f("feeType", "string")), List.of(f("data.id", "string"))));
        // Room for the header and the small, relevant operation - not the large,
        // irrelevant one that comes first in the document.
        properties.setMaxPromptChars(1000);

        String rendered = renderer.render(contract, "Create a fee configuration via POST /api/v1/fees");

        assertTrue(rendered.contains("### POST /api/v1/fees"), rendered);
        assertTrue(rendered.contains("feeType string"), rendered);
        assertTrue(rendered.contains("Remaining operations"), rendered);
        assertTrue(rendered.contains("- POST /api/v1/widgets"), rendered);
        assertFalse(rendered.contains("  widgetProperty0 string"),
                "widgets should not be rendered in full:\n" + rendered);
    }

    /** mms repeats one response shape across five operations per resource. */
    @Test
    void writesARepeatedResponseShapeOnce() {
        List<FieldDoc> shape = List.of(f("success", "boolean"), f("data.id", "string(uuid)"));
        String rendered = renderer.render(contract(
                endpoint("GET", "/api/v1/fees/{id}", List.of(), shape),
                endpoint("PUT", "/api/v1/fees/{id}", List.of(f("currency", "string")), shape)), "");

        assertEquals(1, rendered.split("data\\.id string\\(uuid\\)", -1).length - 1, rendered);
        assertTrue(rendered.contains("same body as GET /api/v1/fees/{id}"), rendered);
    }

    /**
     * A shape must never be credited to an operation that only got a signature
     * line - "same body as POST /x" pointing at something not rendered tells
     * the model nothing.
     */
    @Test
    void neverPointsAtAnOperationThatWasNotRenderedInFull() {
        List<FieldDoc> shape = List.of(f("success", "boolean"), f("data.id", "string(uuid)"));
        properties.setMaxPromptChars(900);

        String rendered = renderer.render(contract(
                endpoint("GET", "/api/v1/aaa", List.of(), shape),
                endpoint("GET", "/api/v1/bbb", List.of(), shape),
                endpoint("GET", "/api/v1/ccc", List.of(), shape)), "");

        for (String line : rendered.lines().toList()) {
            if (line.contains("same body as")) {
                String target = line.substring(line.indexOf("same body as") + "same body as".length()).trim();
                assertTrue(rendered.contains("### " + target),
                        "points at an operation that was never rendered: " + line + "\n" + rendered);
            }
        }
    }

    /** The note is the guard against treating silence about 4xx as licence. */
    @Test
    void putsTheContractsOwnLimitationsUpFront() {
        ApiContract contract = new ApiContract(
                List.of(OpenApiSource.url("svc", "http://host/api-docs", "test")),
                List.of(endpoint("POST", "/api/v1/fees", List.of(), List.of(f("success", "boolean")))),
                List.of("This document declares only 200 responses."));

        String rendered = renderer.render(contract, "");

        assertTrue(rendered.indexOf("IMPORTANT: This document declares only 200")
                        < rendered.indexOf("### POST /api/v1/fees"), rendered);
    }

    @Test
    void rendersNothingWithoutAContract() {
        assertEquals("", renderer.render(ApiContract.empty(), "anything"));
        assertEquals("", renderer.render(null, "anything"));
    }
}
