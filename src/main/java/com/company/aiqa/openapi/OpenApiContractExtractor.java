package com.company.aiqa.openapi;

import com.company.aiqa.openapi.ApiContract.EndpointContract;
import com.company.aiqa.openapi.ApiContract.FieldDoc;
import com.company.aiqa.openapi.ApiContract.ParamDoc;
import com.company.aiqa.openapi.ApiContract.ResponseContract;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a parsed OpenAPI document into one contract per operation.
 *
 * <p>Two decisions carry the weight here.
 *
 * <p><b>Fields are flattened to dotted paths.</b> A response of
 * {@code {success, message, data:{id, tiers:[{rate}]}}} comes out as
 * {@code success}, {@code message}, {@code data.id},
 * {@code data.tiers[].rate}. That is not a summary of the structure, it IS the
 * REST Assured path - the model copies it into {@code .body("data.id", ..)}
 * rather than deriving it and getting it wrong. Nesting also costs a lot of
 * tokens to render as a tree and almost none as a list.
 *
 * <p><b>Resolution is bounded in three directions at once.</b> {@code $ref}
 * cycles are real - an order references a customer that references its orders -
 * so a ref already on the current branch stops. Depth stops at
 * {@code maxDepth}. Field count stops at {@code maxFieldsPerSchema}. Any of the
 * three alone leaves a way to produce a megabyte of prompt from a legal
 * document.
 */
@Service
public class OpenApiContractExtractor {

    private static final Logger log = LoggerFactory.getLogger(OpenApiContractExtractor.class);

    private static final List<String> HTTP_METHODS =
            List.of("get", "put", "post", "delete", "patch", "head", "options");

    /** Preferred request media types, best first. */
    private static final List<String> JSON_MEDIA = List.of("application/json", "application/*+json");

    private final OpenApiProperties properties;

    public OpenApiContractExtractor(OpenApiProperties properties) {
        this.properties = properties;
    }

    /**
     * @param component the deployable this document describes, carried onto every
     *                  endpoint so a merged multi-service contract still says
     *                  which service owns a path
     */
    public List<EndpointContract> extract(JsonNode spec, String component) {
        JsonNode paths = spec.path("paths");
        if (!paths.isObject()) {
            return List.of();
        }
        List<EndpointContract> endpoints = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = paths.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String path = entry.getKey();
            JsonNode item = entry.getValue();
            for (String method : HTTP_METHODS) {
                JsonNode operation = item.path(method);
                if (operation.isObject()) {
                    endpoints.add(toEndpoint(spec, component, method.toUpperCase(Locale.ROOT), path, item, operation));
                }
            }
        }
        log.info("Component '{}': {} operation(s) read from its OpenAPI document.", component, endpoints.size());
        return endpoints;
    }

    private EndpointContract toEndpoint(JsonNode spec, String component, String method, String path,
                                        JsonNode pathItem, JsonNode operation) {
        List<ParamDoc> pathParams = new ArrayList<>();
        List<ParamDoc> queryParams = new ArrayList<>();
        // Parameters declared on the path item apply to every operation under
        // it; the operation's own are added on top.
        collectParams(spec, pathItem.path("parameters"), pathParams, queryParams);
        collectParams(spec, operation.path("parameters"), pathParams, queryParams);

        JsonNode requestContent = operation.path("requestBody").path("content");
        String mediaType = preferredMediaType(requestContent);
        JsonNode requestSchema = mediaType.isEmpty() ? null : requestContent.path(mediaType).path("schema");
        List<FieldDoc> requestFields = requestSchema == null
                ? List.of()
                : flatten(spec, requestSchema, "");
        // Only a DIRECT $ref counts - an inline schema has no class in the
        // target's source to go read, and allOf/oneOf compositions are already
        // merged away by the time flatten() returns, so there is no single
        // class name left to name here.
        String requestSchemaName = requestSchema == null
                ? ""
                : simpleRefName(requestSchema.path("$ref").asText(""));

        List<ResponseContract> responses = new ArrayList<>();
        JsonNode responseNode = operation.path("responses");
        if (responseNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = responseNode.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                JsonNode content = entry.getValue().path("content");
                String type = preferredMediaType(content);
                List<FieldDoc> fields = type.isEmpty()
                        ? List.of()
                        : flatten(spec, content.path(type).path("schema"), "");
                responses.add(new ResponseContract(entry.getKey(),
                        text(entry.getValue(), "description"), fields));
            }
        }

        return new EndpointContract(component, method, path, text(operation, "summary"),
                pathParams, queryParams, mediaType, requestFields, responses, requestSchemaName);
    }

    private void collectParams(JsonNode spec, JsonNode params, List<ParamDoc> path, List<ParamDoc> query) {
        if (!params.isArray()) {
            return;
        }
        for (JsonNode raw : params) {
            JsonNode param = deref(spec, raw, new HashSet<>());
            String in = text(param, "in");
            ParamDoc doc = new ParamDoc(text(param, "name"),
                    typeOf(deref(spec, param.path("schema"), new HashSet<>())),
                    param.path("required").asBoolean(false),
                    text(param, "description"));
            if ("path".equals(in)) {
                path.add(doc);
            } else if ("query".equals(in)) {
                query.add(doc);
            }
        }
    }

    /** JSON when it is offered; otherwise whatever the operation does offer. */
    private String preferredMediaType(JsonNode content) {
        if (!content.isObject() || content.isEmpty()) {
            return "";
        }
        for (String candidate : JSON_MEDIA) {
            if (content.has(candidate)) {
                return candidate;
            }
        }
        // springdoc emits "*/*" for a @RestController that declares no produces,
        // and that is still a JSON body in practice.
        return content.fieldNames().next();
    }

    /**
     * Every leaf of a schema, at its dotted path.
     *
     * @param prefix path accumulated so far; "" at the root
     */
    private List<FieldDoc> flatten(JsonNode spec, JsonNode schema, String prefix) {
        List<FieldDoc> fields = new ArrayList<>();
        flattenInto(spec, schema, prefix, 0, new LinkedHashSet<>(), fields);
        return fields;
    }

    private void flattenInto(JsonNode spec, JsonNode rawSchema, String prefix, int depth,
                             Set<String> refsOnPath, List<FieldDoc> out) {
        if (out.size() >= properties.getMaxFieldsPerSchema() || depth > properties.getMaxDepth()) {
            return;
        }
        String ref = rawSchema.path("$ref").asText("");
        if (!ref.isEmpty() && !refsOnPath.add(ref)) {
            // Cycle. Name the type rather than recursing into it forever, so
            // the reader still learns something is there.
            out.add(new FieldDoc(prefix, simpleRefName(ref) + " (recursive)", false,
                    List.of(), "", "", ""));
            return;
        }
        JsonNode schema = deref(spec, rawSchema, refsOnPath);

        // allOf is composition: springdoc uses it for a schema that extends
        // another. Merging is the only reading that keeps both halves' fields.
        if (schema.path("allOf").isArray()) {
            for (JsonNode part : schema.path("allOf")) {
                flattenInto(spec, part, prefix, depth, refsOnPath, out);
            }
            return;
        }
        // oneOf/anyOf: take the first branch. Rendering every alternative
        // triples the size and the model has to pick one to write a payload.
        for (String union : List.of("oneOf", "anyOf")) {
            if (schema.path(union).isArray() && !schema.path(union).isEmpty()) {
                flattenInto(spec, schema.path(union).get(0), prefix, depth, refsOnPath, out);
                return;
            }
        }

        if (schema.path("type").asText("").equals("array") || schema.has("items")) {
            flattenInto(spec, schema.path("items"), prefix + "[]", depth, refsOnPath, out);
            return;
        }

        JsonNode props = schema.path("properties");
        if (!props.isObject() || props.isEmpty()) {
            if (!prefix.isEmpty()) {
                out.add(field(prefix, schema, false));
            }
            return;
        }

        Set<String> required = new LinkedHashSet<>();
        schema.path("required").forEach(n -> required.add(n.asText()));

        Iterator<Map.Entry<String, JsonNode>> it = props.fields();
        while (it.hasNext() && out.size() < properties.getMaxFieldsPerSchema()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String name = entry.getKey();
            String childPath = prefix.isEmpty() ? name : prefix + "." + name;
            JsonNode child = deref(spec, entry.getValue(), new LinkedHashSet<>(refsOnPath));

            boolean nested = child.path("properties").isObject()
                    || child.path("allOf").isArray()
                    || child.has("items")
                    || entry.getValue().has("$ref");
            if (nested && depth < properties.getMaxDepth()) {
                flattenInto(spec, entry.getValue(), childPath, depth + 1,
                        new LinkedHashSet<>(refsOnPath), out);
            } else {
                out.add(field(childPath, child, required.contains(name)));
            }
        }
    }

    private FieldDoc field(String path, JsonNode schema, boolean required) {
        List<String> enumValues = new ArrayList<>();
        schema.path("enum").forEach(n -> enumValues.add(n.asText()));
        return new FieldDoc(path, typeOf(schema), required, enumValues,
                constraintsOf(schema), text(schema, "example"), text(schema, "description"));
    }

    /** "string(uuid)", "number", "MerchantResponse" - whatever names it best. */
    private String typeOf(JsonNode schema) {
        String type = schema.path("type").asText("");
        String format = schema.path("format").asText("");
        if (type.isEmpty()) {
            String ref = schema.path("$ref").asText("");
            return ref.isEmpty() ? "object" : simpleRefName(ref);
        }
        return format.isEmpty() ? type : type + "(" + format + ")";
    }

    /**
     * The limits a boundary case has to use. springdoc emits {@code minLength: 0}
     * for any unannotated string, which states nothing and is dropped - carrying
     * it invites a "minimum length" test against a rule that does not exist.
     */
    private String constraintsOf(JsonNode schema) {
        List<String> parts = new ArrayList<>();
        if (schema.has("minLength") && schema.path("minLength").asInt() > 0) {
            parts.add("minLength " + schema.path("minLength").asInt());
        }
        if (schema.has("maxLength")) {
            parts.add("maxLength " + schema.path("maxLength").asInt());
        }
        if (schema.has("minimum")) {
            parts.add("minimum " + schema.path("minimum").asText());
        }
        if (schema.has("maximum")) {
            parts.add("maximum " + schema.path("maximum").asText());
        }
        if (schema.has("pattern")) {
            parts.add("pattern " + schema.path("pattern").asText());
        }
        return String.join(", ", parts);
    }

    /** Follows {@code $ref} inside this document. External refs are left alone. */
    private JsonNode deref(JsonNode spec, JsonNode node, Set<String> seen) {
        JsonNode current = node;
        for (int hops = 0; hops < 10; hops++) {
            String ref = current.path("$ref").asText("");
            if (ref.isEmpty() || !ref.startsWith("#/")) {
                return current;
            }
            seen.add(ref);
            JsonNode target = spec.at(ref.substring(1));
            if (target.isMissingNode()) {
                return current;
            }
            current = target;
        }
        return current;
    }

    private String simpleRefName(String ref) {
        int slash = ref.lastIndexOf('/');
        return slash < 0 ? ref : ref.substring(slash + 1);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isValueNode() ? value.asText("") : "";
    }
}
