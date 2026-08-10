package com.company.aiqa.dependency;

import com.company.aiqa.model.ClassInfo;
import com.company.aiqa.model.DependencyGraph;
import com.company.aiqa.model.MethodInfo;
import com.company.aiqa.model.SourceLanguage;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Step 3 of the pipeline: "Find Dependencies".
 *
 * Builds a lightweight class-level dependency graph across every supported
 * SourceLanguage. Edges for the units that changed come from whichever
 * parser produced their ClassInfo (JavaParserService for .java,
 * GenericSourceParser for everything else); edges for the rest of the
 * codebase are discovered by scanning the working copy so that impact
 * analysis can walk "who depends on the units that changed".
 *
 * Java files get AST-accurate reference detection via JavaParser. Every
 * other language uses a much simpler word-boundary text match - it can't
 * distinguish a real reference from a same-named local variable or comment,
 * but it's dependency-free and language-agnostic, and still gives the
 * impact-analysis stage a reasonable signal to work with. Swap in a real
 * parser per language here if you need tighter accuracy.
 *
 * Also builds a METHOD-level call graph (see buildMethodLevelEdges) so
 * ImpactAnalysisService can name the exact calling method(s) affected by a
 * changed method, not just the calling class - Java-only, see that method's
 * javadoc for why and its known limitations.
 */
@Service
public class DependencyService {

    private static final Logger log = LoggerFactory.getLogger(DependencyService.class);

    /**
     * Builds a dependency graph seeded with the changed units, then extends it
     * with edges discovered by scanning the rest of the repository's supported
     * source files for references to those changed units (one hop out).
     */
    public DependencyGraph buildGraph(String repoPath, List<ClassInfo> changedClasses) {
        DependencyGraph graph = new DependencyGraph();

        // 1. Edges out of the changed units themselves (already parsed).
        for (ClassInfo classInfo : changedClasses) {
            for (String referenced : classInfo.referencedTypes()) {
                graph.addEdge(classInfo.simpleName(), referenced);
            }
        }

        // 2. Scan the rest of the repo for units that reference the changed ones,
        //    so ImpactAnalysisService can walk the reverse edges.
        List<String> changedSimpleNames = changedClasses.stream().map(ClassInfo::simpleName).toList();

        try (Stream<Path> paths = Files.walk(Path.of(repoPath))) {
            paths.filter(p -> SourceLanguage.isSupported(p.toString()))
                 .forEach(p -> indexFileForReferences(p, changedSimpleNames, graph));
        } catch (IOException e) {
            log.warn("Could not fully scan repo at {} for dependency edges: {}", repoPath, e.getMessage());
        }

        buildMethodLevelEdges(repoPath, changedClasses, graph);

        return graph;
    }

    private void indexFileForReferences(Path path, List<String> changedSimpleNames, DependencyGraph graph) {
        String pathStr = path.toString();
        if (pathStr.toLowerCase().endsWith(".java")) {
            indexJavaFile(path, changedSimpleNames, graph);
        } else {
            indexGenericFile(path, changedSimpleNames, graph);
        }
    }

    private void indexJavaFile(Path path, List<String> changedSimpleNames, DependencyGraph graph) {
        try {
            String content = Files.readString(path);
            CompilationUnit cu = StaticJavaParser.parse(content);

            for (TypeDeclaration<?> type : cu.getTypes()) {
                String owner = type.getNameAsString();
                if (changedSimpleNames.contains(owner)) {
                    continue; // already indexed from the AST in step 1
                }
                type.findAll(ClassOrInterfaceType.class).forEach(refType -> {
                    String referenced = refType.getNameAsString();
                    if (changedSimpleNames.contains(referenced)) {
                        graph.addEdge(owner, referenced);
                    }
                });
            }
        } catch (Exception e) {
            // Best-effort: skip files that don't parse (generated code, non-UTF8, etc.)
            log.trace("Skipping {} during Java dependency scan: {}", path, e.getMessage());
        }
    }

    /** Word-boundary text match fallback for every non-Java supported language. */
    private void indexGenericFile(Path path, List<String> changedSimpleNames, DependencyGraph graph) {
        try {
            String content = Files.readString(path);
            String owner = fileNameWithoutExtension(path);
            if (changedSimpleNames.contains(owner)) {
                return; // already indexed from step 1
            }
            for (String changedName : changedSimpleNames) {
                if (owner.equals(changedName)) continue;
                Pattern wordBoundary = Pattern.compile("\\b" + Pattern.quote(changedName) + "\\b");
                if (wordBoundary.matcher(content).find()) {
                    graph.addEdge(owner, changedName);
                }
            }
        } catch (Exception e) {
            log.trace("Skipping {} during generic dependency scan: {}", path, e.getMessage());
        }
    }

    /**
     * Builds method-level call edges (DependencyGraph.addMethodEdge, keyed
     * "ClassName.methodName") so ImpactAnalysisService can trace impact down
     * to the exact calling method, not just the calling class. Java-only:
     * there's no classpath-based symbol solver wired up here (reliably
     * resolving an arbitrary cloned repo's full classpath isn't practical in
     * this architecture), so this uses two bounded, best-effort heuristics
     * instead of true call-graph resolution:
     *
     *   1. Within the already-parsed changed classes: MethodInfo.calledMethodNames
     *      (captured by JavaParserService) is matched by name against every
     *      OTHER changed method - cheap (no re-parsing) and accurate for
     *      same-diff call chains.
     *   2. Across the rest of the repo: each non-changed .java file is parsed
     *      (AST only, no symbol resolution) and every method call whose name
     *      matches a changed method is checked against a same-file "declared
     *      type" map (built from that file's own field/parameter/local-variable
     *      declarations) - e.g. recognizing that "orderService.calculate()"
     *      targets OrderService because a field "private final OrderService
     *      orderService;" says so. Reliable for the common
     *      dependency-injected-field/local-variable case, but WILL miss (or
     *      occasionally misattribute, since overloads collapse onto one node
     *      by name only) a call whose receiver type can't be determined this
     *      way - a chained call, a value returned from another call, a field
     *      declared only in a superclass, etc.
     */
    private void buildMethodLevelEdges(String repoPath, List<ClassInfo> changedClasses, DependencyGraph graph) {
        // method name -> declaring changed-class simple name(s), so a call
        // site's bare method name can be checked against every changed
        // method it might plausibly target.
        Map<String, Set<String>> changedMethodOwners = new HashMap<>();
        for (ClassInfo c : changedClasses) {
            for (MethodInfo m : c.methods()) {
                changedMethodOwners.computeIfAbsent(m.name(), k -> new HashSet<>()).add(c.simpleName());
            }
        }
        if (changedMethodOwners.isEmpty()) {
            return;
        }

        // 1. Edges among the changed classes' own already-parsed methods.
        for (ClassInfo c : changedClasses) {
            for (MethodInfo m : c.methods()) {
                String callerNode = c.simpleName() + "." + m.name();
                for (String calledName : m.calledMethodNames()) {
                    Set<String> owners = changedMethodOwners.get(calledName);
                    if (owners == null) continue;
                    for (String ownerClass : owners) {
                        graph.addMethodEdge(callerNode, ownerClass + "." + calledName);
                    }
                }
            }
        }

        // 2. Scan the rest of the repo's .java files for call sites targeting a changed method.
        List<String> changedSimpleNames = changedClasses.stream().map(ClassInfo::simpleName).toList();
        try (Stream<Path> paths = Files.walk(Path.of(repoPath))) {
            paths.filter(p -> p.toString().toLowerCase().endsWith(".java"))
                 .forEach(p -> indexJavaFileForMethodCalls(p, changedSimpleNames, changedMethodOwners, graph));
        } catch (IOException e) {
            log.warn("Could not fully scan repo at {} for method-level call edges: {}", repoPath, e.getMessage());
        }
    }

    private void indexJavaFileForMethodCalls(Path path, List<String> changedSimpleNames,
                                              Map<String, Set<String>> changedMethodOwners,
                                              DependencyGraph graph) {
        try {
            String content = Files.readString(path);
            CompilationUnit cu = StaticJavaParser.parse(content);

            for (TypeDeclaration<?> type : cu.getTypes()) {
                String ownerClass = type.getNameAsString();
                if (changedSimpleNames.contains(ownerClass)) {
                    continue; // already covered by the exact, already-parsed analysis in step 1
                }

                // Combined field + parameter + local-variable declared-type map for
                // this whole type - a small, deliberate imprecision (cross-method
                // name shadowing isn't accounted for) in exchange for one cheap pass.
                Map<String, String> declaredTypes = new HashMap<>();
                type.findAll(VariableDeclarator.class)
                        .forEach(vd -> declaredTypes.put(vd.getNameAsString(), vd.getType().asString()));
                type.findAll(Parameter.class)
                        .forEach(p -> declaredTypes.put(p.getNameAsString(), p.getType().asString()));

                for (MethodDeclaration callerMethod : type.getMethods()) {
                    String callerNode = ownerClass + "." + callerMethod.getNameAsString();
                    for (MethodCallExpr call : callerMethod.findAll(MethodCallExpr.class)) {
                        String calledName = call.getNameAsString();
                        Set<String> owners = changedMethodOwners.get(calledName);
                        if (owners == null) continue;

                        String receiverType = resolveReceiverType(call, ownerClass, declaredTypes);
                        if (receiverType != null && owners.contains(receiverType)) {
                            graph.addMethodEdge(callerNode, receiverType + "." + calledName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.trace("Skipping {} during Java method-call scan: {}", path, e.getMessage());
        }
    }

    /**
     * Best-effort receiver-type resolution for a method call, without a
     * classpath-based symbol solver: recognizes "this.foo()" (the enclosing
     * class itself), "identifier.foo()" where "identifier" was declared with
     * a known type in this same file (field/parameter/local variable), and
     * "ClassName.foo()" (a static-style call where the scope IS the class
     * name). Returns null (skip this call site) for anything else - a
     * chained call, a cast, a returned expression, etc - rather than guessing.
     */
    private String resolveReceiverType(MethodCallExpr call, String ownerClass, Map<String, String> declaredTypes) {
        if (call.getScope().isEmpty()) {
            return null; // bare call - could be inherited/same-class; not attributable without a symbol solver
        }
        Expression scope = call.getScope().get();
        if (scope instanceof ThisExpr) {
            return ownerClass;
        }
        if (scope instanceof NameExpr nameExpr) {
            String name = nameExpr.getNameAsString();
            String declared = declaredTypes.get(name);
            return declared != null ? simpleTypeName(declared) : name;
        }
        return null;
    }

    /** Strips generics ("List<Foo>" -&gt; "List"), array brackets, and package qualification. */
    private String simpleTypeName(String typeText) {
        int generic = typeText.indexOf('<');
        String base = (generic >= 0 ? typeText.substring(0, generic) : typeText)
                .replace("[]", "")
                .trim();
        int lastDot = base.lastIndexOf('.');
        return lastDot >= 0 ? base.substring(lastDot + 1) : base;
    }

    private String fileNameWithoutExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
