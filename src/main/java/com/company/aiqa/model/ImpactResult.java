package com.company.aiqa.model;

import java.util.Set;

/**
 * Output of the impact-analysis stage: the directly changed classes/methods
 * plus everything transitively downstream of them, up to a configured depth.
 *
 * Method-level fields use "ClassName.methodName" entries (see DependencyGraph's
 * method-level graph) and are Java-only - see
 * DependencyService.buildMethodLevelEdges for why and its known limitations.
 * For a non-Java change (or when method-level tracing found nothing to
 * attribute), these are simply empty; the class-level fields above remain
 * the reliable, always-populated signal.
 */
public record ImpactResult(
        Set<String> directlyChangedClasses,
        Set<String> impactedClasses,
        int depthUsed,
        Set<String> directlyChangedMethods,
        Set<String> impactedMethods
) {
}
