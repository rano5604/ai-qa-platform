package com.company.aiqa.openapi;

import java.util.List;

/**
 * What a service says about itself: every endpoint, the payload it accepts, and
 * the payload it answers with.
 *
 * <p>The response half is the part that did not exist before. The generator had
 * always been shown request shapes - read out of the project's DTOs - and never
 * a response, so every assertion about a returned body was a guess. Two guesses
 * in particular cost whole runs: {@code .body("success", is(false))} against an
 * error the service returns in a completely different envelope, and
 * {@code .extract().path("id")} where the id actually sits at {@code data.id},
 * which returns null and takes the next call down with it.
 *
 * <p>Fields are FLATTENED with dotted names - {@code data.id},
 * {@code data.tiers[].rate} - because that is precisely the syntax REST Assured
 * uses. The model does not have to work the path out from a nested structure;
 * it can copy it.
 */
public record ApiContract(

        /** Where this came from, so a reader can re-fetch it. */
        List<OpenApiSource> sources,

        List<EndpointContract> endpoints,

        /** What the contract does NOT cover - see {@link OpenApiContractService}. */
        List<String> notes
) {

    public static ApiContract empty() {
        return new ApiContract(List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return endpoints.isEmpty();
    }

    /** One operation: a method and a path, with everything documented about it. */
    public record EndpointContract(
            String component,
            String httpMethod,
            String path,
            String summary,
            List<ParamDoc> pathParams,
            List<ParamDoc> queryParams,
            /** "application/json", or "" when the operation takes no body. */
            String requestMediaType,
            List<FieldDoc> requestFields,
            List<ResponseContract> responses,
            /**
             * Simple name of the request body's top-level schema - "CreateMerchantRequest" -
             * or "" when the body has no {@code $ref} (an inline schema, or no body at all).
             * Not rendered into the prompt; it exists so a required-field check that the
             * contract itself under-documents can go find this exact class in the target's
             * OWN source and read its Bean Validation annotations instead - see
             * AutomationScriptMerger's contract-plus-source repair.
             */
            String requestSchemaName
    ) {
        public String signature() {
            return httpMethod + " " + path;
        }
    }

    /** A documented response for one status code. */
    public record ResponseContract(String status, String description, List<FieldDoc> fields) {
    }

    public record ParamDoc(String name, String type, boolean required, String description) {
    }

    /**
     * One field, at its full dotted path from the root of the payload.
     *
     * @param constraints rendered limits - "maxLength 50", "minimum 0". These are
     *                    the numbers a boundary case must use; inventing them is
     *                    how a test came to assert a 64-character signature
     *                    against a service configured for 16.
     */
    public record FieldDoc(
            String path,
            String type,
            boolean required,
            List<String> enumValues,
            String constraints,
            String example,
            String description
    ) {
    }
}
