package com.company.aiqa.model;

import java.util.List;

/**
 * A REST endpoint a changed method exposes - extracted so PromptBuilder can
 * tell the LLM the exact HTTP method, path, and payload shape to reference in
 * a manual test case's steps, instead of it inventing a plausible-looking but
 * possibly wrong one. Set on MethodInfo.apiEndpoint() when JavaParserService
 * (Spring MVC annotations) or GenericSourceParser (best-effort regex, for
 * Express/Flask/FastAPI-style route decorators) recognizes the method as a
 * route handler; null otherwise.
 */
public record ApiEndpointInfo(
        String httpMethod,       // "GET" / "POST" / "PUT" / "DELETE" / "PATCH"
        String path,             // combined class-level + method-level path, e.g. "/api/v1/generate-tests"
        String requestBodyType,  // simple type name of the @RequestBody param, or "" if none
        List<String> pathVariables,
        List<String> queryParams
) {
}
