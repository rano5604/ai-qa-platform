package com.company.aiqa.openapi;

import com.company.aiqa.openapi.ApiContract.EndpointContract;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The contract for everything this run is going to call: locate the documents,
 * load them, merge them into one endpoint list.
 *
 * <p>Several components mean several documents, and they are merged rather than
 * chosen between - a test that creates a merchant in one service and a fee in
 * another needs both. The first document to describe an operation wins, which
 * matters when a gateway republishes a downstream service's paths: the
 * candidate order puts the service the run is actually pointed at first.
 *
 * <p>{@code notes} is the important output after the endpoints themselves. A
 * contract that documents only its 200s - which is what springdoc produces
 * unless somebody has written {@code @ApiResponse} annotations, and so what
 * these services all produce - must say so, or the generator treats silence
 * about 400 as licence to invent one. That was already the single most
 * expensive class of false failure in this platform, and handing over a
 * contract makes it MORE tempting, not less.
 */
@Service
public class OpenApiContractService {

    private static final Logger log = LoggerFactory.getLogger(OpenApiContractService.class);

    private final SwaggerLocator locator;
    private final OpenApiSpecLoader loader;
    private final OpenApiContractExtractor extractor;
    private final OpenApiProperties properties;

    public OpenApiContractService(SwaggerLocator locator, OpenApiSpecLoader loader,
                                  OpenApiContractExtractor extractor, OpenApiProperties properties) {
        this.locator = locator;
        this.loader = loader;
        this.extractor = extractor;
        this.properties = properties;
    }

    /**
     * @param explicitUrls URLs from the request, which skip discovery entirely.
     *                     A caller who knows the document's address should not
     *                     have to make the repo say so.
     */
    public ApiContract collect(Path repoRoot, String baseUri, List<String> explicitUrls) {
        if (!properties.isEnabled()) {
            log.info("aiqa.openapi.enabled=false - generating from source-derived DTOs only.");
            return ApiContract.empty();
        }

        List<OpenApiSource> candidates = new ArrayList<>();
        if (explicitUrls != null) {
            explicitUrls.stream()
                    .filter(u -> u != null && !u.isBlank())
                    .forEach(u -> candidates.add(OpenApiSource.url("request", u.trim(), "supplied on the request")));
        }
        if (candidates.isEmpty()) {
            candidates.addAll(locator.locate(repoRoot, baseUri));
        }
        if (candidates.isEmpty()) {
            return ApiContract.empty();
        }

        Map<String, EndpointContract> merged = new LinkedHashMap<>();
        List<OpenApiSource> loaded = new ArrayList<>();
        Set<String> componentsSeen = new LinkedHashSet<>();
        int attempts = 0;

        for (OpenApiSource source : candidates) {
            if (attempts >= properties.getMaxAttempts()) {
                log.info("Stopping after {} attempt(s) - remaining candidates were not tried.", attempts);
                break;
            }
            // One document per component is enough. The rest of that
            // component's candidates are fallbacks for THIS one failing.
            if (componentsSeen.contains(source.component())) {
                continue;
            }
            attempts++;
            Optional<JsonNode> spec = loader.load(source, repoRoot);
            if (spec.isEmpty()) {
                continue;
            }
            componentsSeen.add(source.component());
            loaded.add(source);
            for (EndpointContract endpoint : extractor.extract(spec.get(), source.component())) {
                merged.putIfAbsent(endpoint.signature(), endpoint);
            }
        }

        if (merged.isEmpty()) {
            log.info("None of the {} OpenAPI candidate(s) yielded a document - falling back to source-derived DTOs.",
                    candidates.size());
            return ApiContract.empty();
        }
        List<EndpointContract> endpoints = List.copyOf(merged.values());
        log.info("API contract: {} operation(s) from {} document(s).", endpoints.size(), loaded.size());
        return new ApiContract(List.copyOf(loaded), endpoints, notes(endpoints));
    }

    /**
     * What the contract does not tell us. Every note here is derived from the
     * document, never assumed.
     */
    private List<String> notes(List<EndpointContract> endpoints) {
        List<String> notes = new ArrayList<>();

        Set<String> documented = new TreeSet<>();
        endpoints.forEach(e -> e.responses().forEach(r -> documented.add(r.status())));
        boolean anyError = documented.stream().anyMatch(s -> s.startsWith("4") || s.startsWith("5"));
        if (!anyError) {
            notes.add("This document declares only " + String.join(", ", documented)
                    + " responses. It says NOTHING about the shape of a 4xx or 5xx body, so no error "
                    + "response field below is documented and none may be asserted.");
        }

        long withoutBody = endpoints.stream()
                .filter(e -> List.of("POST", "PUT", "PATCH").contains(e.httpMethod()))
                .filter(e -> e.requestFields().isEmpty())
                .count();
        if (withoutBody > 0) {
            notes.add(withoutBody + " write operation(s) declare no request body schema - for those, the "
                    + "payload has to come from the implementation source as before.");
        }
        return notes;
    }
}
