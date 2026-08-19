package com.company.aiqa.openapi;

import com.company.aiqa.openapi.ApiContract.EndpointContract;
import com.company.aiqa.openapi.ApiContract.FieldDoc;
import com.company.aiqa.openapi.ApiContract.ParamDoc;
import com.company.aiqa.openapi.ApiContract.ResponseContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Renders the contract into the prompt, inside a character budget.
 *
 * <p>The budget is the whole problem. mms documents 71 operations against 76
 * schemas; written out in full that is far more text than the endpoint list and
 * the implementation source combined, and the automation prompt is already
 * large enough that Groq's on-demand tier rejects it outright. Truncating the
 * list at N would silently drop whichever operations happen to sort last.
 *
 * <p>So relevance decides, and the manual cases define it: an operation whose
 * path is named in this batch gets its full request and response, because those
 * are the calls the batch is going to make. Everything else is a one-line
 * signature - still enough for the model to build a precondition against, at
 * roughly a twentieth of the cost. Only if the budget runs out inside the
 * relevant set does anything get cut, and then the count is stated.
 */
@Service
public class ApiContractRenderer {

    private static final Logger log = LoggerFactory.getLogger(ApiContractRenderer.class);

    /** Response fields past this are the audit columns nobody asserts. */
    private static final int MAX_RESPONSE_FIELDS = 22;

    private final OpenApiProperties properties;

    public ApiContractRenderer(OpenApiProperties properties) {
        this.properties = properties;
    }

    /**
     * @param caseText the batch's manual cases as one blob, used only to decide
     *                 which operations matter. Pass "" to render everything in
     *                 full order until the budget runs out.
     */
    public String render(ApiContract contract, String caseText) {
        if (contract == null || contract.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## API contract (the service's own OpenAPI document)\n\n");
        for (OpenApiSource source : contract.sources()) {
            sb.append("Read from ").append(source.describe()).append("\n");
        }
        sb.append("""

                This is what the service SAYS about itself, not what a DTO in the source
                suggests. Field names, types, enum values and limits here are authoritative:
                use them exactly, and never invent a field or a limit that is not listed.

                `*` marks a required field. `[]` marks an array element. A response field is
                written at its full JSON path, which is exactly the path REST Assured wants -
                copy it into .body("data.id", ..) or .extract().path("data.id") rather than
                working it out.

                """);
        for (String note : contract.notes()) {
            sb.append("IMPORTANT: ").append(note).append("\n");
        }
        sb.append("\n");

        List<EndpointContract> relevant = new ArrayList<>();
        List<EndpointContract> rest = new ArrayList<>();
        for (EndpointContract endpoint : contract.endpoints()) {
            (mentions(caseText, endpoint) ? relevant : rest).add(endpoint);
        }

        int budget = properties.getMaxPromptChars();
        int rendered = 0;
        int skipped = 0;
        List<EndpointContract> summarised = new ArrayList<>();
        // A resource's response shape is identical across its GET, POST and PUT.
        // mms has thirteen of them and five operations each, so writing the
        // shape out every time spends most of the budget saying the same thing.
        Map<String, String> shapesSeen = new LinkedHashMap<>();

        for (List<EndpointContract> group : List.of(relevant, rest)) {
            for (EndpointContract endpoint : group) {
                // Rendered against a COPY: an endpoint that turns out not to fit
                // must not leave its shapes behind, or a later "same body as
                // POST /x" points at an operation that only got a signature line.
                Map<String, String> attempt = new LinkedHashMap<>(shapesSeen);
                String detail = renderEndpoint(endpoint, attempt);
                if (sb.length() + detail.length() > budget) {
                    summarised.add(endpoint);
                    skipped++;
                    continue;
                }
                sb.append(detail);
                shapesSeen.clear();
                shapesSeen.putAll(attempt);
                rendered++;
            }
        }

        if (!summarised.isEmpty()) {
            sb.append("### Remaining operations (signature only - ask the implementation source for their payloads)\n\n");
            for (EndpointContract endpoint : summarised) {
                if (sb.length() > budget + 4000) {
                    break;
                }
                sb.append("- ").append(endpoint.signature());
                if (!endpoint.requestFields().isEmpty()) {
                    sb.append("  body: ").append(String.join(", ", topLevelNames(endpoint.requestFields())));
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        log.info("API contract rendered: {} operation(s) in full, {} as signatures, {} chars.",
                rendered, skipped, sb.length());
        return sb.toString();
    }

    private String renderEndpoint(EndpointContract e, Map<String, String> shapesSeen) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(e.signature());
        if (!e.summary().isBlank()) {
            sb.append(" - ").append(e.summary());
        }
        sb.append("\n");

        if (!e.pathParams().isEmpty()) {
            sb.append("path params: ").append(renderParams(e.pathParams())).append("\n");
        }
        if (!e.queryParams().isEmpty()) {
            sb.append("query params: ").append(renderParams(e.queryParams())).append("\n");
        }
        if (!e.requestFields().isEmpty()) {
            sb.append("request body (").append(e.requestMediaType()).append("):\n");
            for (FieldDoc field : e.requestFields()) {
                sb.append("  ").append(renderField(field)).append("\n");
            }
        } else if (List.of("POST", "PUT", "PATCH").contains(e.httpMethod())) {
            sb.append("request body: not documented\n");
        }
        for (ResponseContract response : e.responses()) {
            sb.append("response ").append(response.status());
            if (response.fields().isEmpty()) {
                sb.append(": no body documented\n");
                continue;
            }
            String shape = shapeKey(response);
            String firstSeen = shapesSeen.get(shape);
            if (firstSeen != null) {
                sb.append(": same body as ").append(firstSeen).append("\n");
                continue;
            }
            shapesSeen.put(shape, e.signature());
            sb.append(":\n");
            int shown = 0;
            for (FieldDoc field : response.fields()) {
                if (shown++ >= MAX_RESPONSE_FIELDS) {
                    sb.append("  ... ").append(response.fields().size() - MAX_RESPONSE_FIELDS)
                            .append(" more field(s)\n");
                    break;
                }
                sb.append("  ").append(renderField(field)).append("\n");
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * Identity for a response body: its rendered fields. Two operations sharing
     * it get one copy and a pointer, which is both shorter and clearer than the
     * same forty lines twice.
     */
    private String shapeKey(ResponseContract response) {
        StringBuilder key = new StringBuilder();
        for (FieldDoc field : response.fields()) {
            key.append(renderField(field)).append('\n');
        }
        return key.toString();
    }

    private String renderParams(List<ParamDoc> params) {
        List<String> parts = new ArrayList<>();
        for (ParamDoc p : params) {
            parts.add(p.name() + (p.required() ? "*" : "") + " " + p.type());
        }
        return String.join(", ", parts);
    }

    /** One field: path, type, limits, enum, example - in that order of usefulness. */
    private String renderField(FieldDoc field) {
        StringBuilder sb = new StringBuilder();
        sb.append(field.path());
        if (field.required()) {
            sb.append("*");
        }
        sb.append(" ").append(field.type());
        if (!field.enumValues().isEmpty()) {
            sb.append(" enum[").append(String.join("|", field.enumValues())).append("]");
        }
        if (!field.constraints().isBlank()) {
            sb.append(" (").append(field.constraints()).append(")");
        }
        if (!field.example().isBlank()) {
            sb.append("  e.g. ").append(field.example());
        }
        if (!field.description().isBlank()) {
            sb.append("  - ").append(field.description());
        }
        return sb.toString();
    }

    private List<String> topLevelNames(List<FieldDoc> fields) {
        List<String> names = new ArrayList<>();
        for (FieldDoc field : fields) {
            String name = field.path();
            int dot = name.indexOf('.');
            if (dot > 0) {
                name = name.substring(0, dot);
            }
            if (!names.contains(name) && names.size() < 12) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Whether this batch's cases name the operation's path.
     *
     * <p>Matched as a pattern, not a literal: a case cites
     * {@code /api/v1/fees/f0000000-.../tiers} and the contract declares
     * {@code /api/v1/fees/{id}/tiers}, so every {@code {param}} becomes a
     * one-segment wildcard. Falling back to the resource word - "fees",
     * "capabilities" - catches the cases that describe the call in prose
     * instead. Over-matching only costs prompt space; under-matching costs the
     * model the schema for a call it is about to write.
     */
    boolean mentions(String caseText, EndpointContract endpoint) {
        if (caseText == null || caseText.isBlank()) {
            return true;
        }
        String text = caseText.toLowerCase(Locale.ROOT);
        String path = endpoint.path().toLowerCase(Locale.ROOT);

        StringBuilder regex = new StringBuilder();
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty()) {
                continue;
            }
            regex.append("/");
            regex.append(segment.startsWith("{") ? "[^/\\s]+" : Pattern.quote(segment));
        }
        if (regex.length() > 0 && Pattern.compile(regex.toString()).matcher(text).find()) {
            return true;
        }
        return text.contains(resourceWord(path));
    }

    /** The last non-parameter segment - "fees" for /api/v1/fees/{id}. */
    private String resourceWord(String path) {
        String[] segments = path.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            if (!segments[i].isBlank() && !segments[i].startsWith("{")) {
                return segments[i];
            }
        }
        return path;
    }
}
