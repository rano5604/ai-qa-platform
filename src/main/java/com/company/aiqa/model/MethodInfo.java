package com.company.aiqa.model;

import java.util.List;

/**
 * A single method extracted from a parsed class.
 *
 * apiEndpoint is null for an ordinary method and set only when the parser
 * recognized this method as a REST route handler (a Spring MVC
 * @GetMapping/@PostMapping/etc, or a best-effort regex match on an
 * Express/Flask/FastAPI-style route decorator) - see ApiEndpointInfo.
 */
public record MethodInfo(
        String name,
        String signature,
        List<String> parameterTypes,
        String returnType,
        List<String> calledMethodNames,
        boolean changed,
        ApiEndpointInfo apiEndpoint
) {
}
