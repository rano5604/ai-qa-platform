package com.company.aiqa.impact;

import com.company.aiqa.config.PipelineProperties;
import com.company.aiqa.model.ClassInfo;
import com.company.aiqa.model.DependencyGraph;
import com.company.aiqa.model.ImpactResult;
import com.company.aiqa.model.MethodInfo;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Step: "Find Dependencies" -> impact radius used when building LLM context.
 *
 * Starting from the classes (and, where DependencyService's method-level
 * call graph has edges - Java only, see its javadoc - the specific methods)
 * that were directly changed, does a bounded breadth-first walk over the
 * reverse dependency edges to find every class/method that could plausibly
 * be affected - these are the units whose behavior the generated tests
 * should also guard.
 */
@Service
public class ImpactAnalysisService {

    private final PipelineProperties pipelineProperties;

    public ImpactAnalysisService(PipelineProperties pipelineProperties) {
        this.pipelineProperties = pipelineProperties;
    }

    public ImpactResult computeImpact(List<ClassInfo> changedClasses, DependencyGraph graph) {
        int depth = pipelineProperties.getImpactDepth();

        Set<String> directlyChangedClasses = new HashSet<>();
        changedClasses.forEach(c -> directlyChangedClasses.add(c.simpleName()));
        Set<String> impactedClasses = bfs(directlyChangedClasses, depth, graph::dependentsOf);

        // Every method in a changed file is treated as a change candidate
        // (see MethodInfo.changed's javadoc) - this mirrors that same
        // file-level approximation at method granularity, rather than trying
        // to map diff hunks to individual method bodies.
        Set<String> directlyChangedMethods = new HashSet<>();
        for (ClassInfo c : changedClasses) {
            for (MethodInfo m : c.methods()) {
                directlyChangedMethods.add(c.simpleName() + "." + m.name());
            }
        }
        Set<String> impactedMethods = bfs(directlyChangedMethods, depth, graph::methodDependentsOf);

        return new ImpactResult(directlyChangedClasses, impactedClasses, depth,
                directlyChangedMethods, impactedMethods);
    }

    /** Bounded breadth-first walk over reverse edges, shared by the class-level and method-level graphs. */
    private Set<String> bfs(Set<String> seeds, int depth, Function<String, Set<String>> dependentsOf) {
        Set<String> visited = new HashSet<>(seeds);
        Deque<String> frontier = new ArrayDeque<>(seeds);

        for (int hop = 0; hop < depth && !frontier.isEmpty(); hop++) {
            Deque<String> nextFrontier = new ArrayDeque<>();
            for (String node : frontier) {
                for (String dependent : dependentsOf.apply(node)) {
                    if (visited.add(dependent)) {
                        nextFrontier.add(dependent);
                    }
                }
            }
            frontier = nextFrontier;
        }
        return visited;
    }
}
