package com.company.aiqa.model;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Directed graph of dependencies at two granularities, kept in-memory for the
 * duration of a single pipeline run:
 *   - class-level: className -> set of classes it references (used for
 *     ImpactAnalysisService's class-level BFS).
 *   - method-level: "ClassName.methodName" -> set of "ClassName.methodName"
 *     nodes it calls (used for method-level impact tracing - see
 *     DependencyService.buildMethodLevelEdges). Overloads collapse onto one
 *     node (keyed by name only, not full signature) since there's no
 *     classpath-based symbol resolution available to disambiguate them -
 *     see DependencyService's javadoc for why.
 */
public class DependencyGraph {

    private final Map<String, Set<String>> forwardEdges = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> reverseEdges = new ConcurrentHashMap<>();

    private final Map<String, Set<String>> methodForwardEdges = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> methodReverseEdges = new ConcurrentHashMap<>();

    public void addEdge(String fromClass, String toClass) {
        if (fromClass.equals(toClass)) return;
        forwardEdges.computeIfAbsent(fromClass, k -> new HashSet<>()).add(toClass);
        reverseEdges.computeIfAbsent(toClass, k -> new HashSet<>()).add(fromClass);
    }

    /** Classes that "fromClass" depends on. */
    public Set<String> dependenciesOf(String fromClass) {
        return forwardEdges.getOrDefault(fromClass, Set.of());
    }

    /** Classes that depend on "toClass" (i.e. would be impacted if it changes). */
    public Set<String> dependentsOf(String toClass) {
        return reverseEdges.getOrDefault(toClass, Set.of());
    }

    public Set<String> allClasses() {
        Set<String> all = new HashSet<>(forwardEdges.keySet());
        all.addAll(reverseEdges.keySet());
        return all;
    }

    /** fromMethod/toMethod are "ClassName.methodName" nodes - see class javadoc. */
    public void addMethodEdge(String fromMethod, String toMethod) {
        if (fromMethod.equals(toMethod)) return;
        methodForwardEdges.computeIfAbsent(fromMethod, k -> new HashSet<>()).add(toMethod);
        methodReverseEdges.computeIfAbsent(toMethod, k -> new HashSet<>()).add(fromMethod);
    }

    /** Methods that "fromMethod" calls. */
    public Set<String> methodDependenciesOf(String fromMethod) {
        return methodForwardEdges.getOrDefault(fromMethod, Set.of());
    }

    /** Methods that call "toMethod" (i.e. would be impacted if it changes). */
    public Set<String> methodDependentsOf(String toMethod) {
        return methodReverseEdges.getOrDefault(toMethod, Set.of());
    }
}
