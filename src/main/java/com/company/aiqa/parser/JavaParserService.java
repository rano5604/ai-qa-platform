package com.company.aiqa.parser;

import com.company.aiqa.model.ApiEndpointInfo;
import com.company.aiqa.model.ChangedFile;
import com.company.aiqa.model.ClassInfo;
import com.company.aiqa.model.MethodInfo;
import com.company.aiqa.model.SourceLanguage;
import com.company.aiqa.model.TypeSchema;
import com.company.aiqa.git.GitDiffService;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Step 2 of the pipeline: "Parse Changed Java Files".
 *
 * Wraps JavaParser to turn raw source text into structured ClassInfo/MethodInfo
 * that the dependency and impact stages can reason about. Also recognizes
 * Spring MVC REST endpoints (@RestController/@RequestMapping and friends) so
 * PromptBuilder can tell the LLM the exact HTTP method/path/payload to
 * reference in a manual test case's steps for an API-only project - see
 * ApiEndpointInfo and project.ProjectShapeAnalyzer.
 */
@Service
public class JavaParserService {

    private static final Logger log = LoggerFactory.getLogger(JavaParserService.class);

    /** Spring MVC's shorthand mapping annotations -> the HTTP method each implies. */
    private static final Map<String, String> MAPPING_ANNOTATION_TO_METHOD = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH"
    );

    /** Parses every changed (non-deleted) *.java file into zero or more ClassInfo entries. */
    public List<ClassInfo> parseChangedFiles(List<ChangedFile> changedFiles) {
        List<ClassInfo> allClasses = new ArrayList<>();
        for (ChangedFile file : changedFiles) {
            if (file.changeType() == ChangedFile.ChangeType.DELETED
                    || file.fileContent().isBlank()
                    || !file.path().toLowerCase().endsWith(".java")) {
                continue;
            }
            try {
                allClasses.addAll(parseSingleFile(file));
            } catch (Exception e) {
                log.warn("Failed to parse {}: {}", file.path(), e.getMessage());
            }
        }
        return allClasses;
    }

    /**
     * One AST walk of the whole repository, returning every class that owns at
     * least one REST endpoint.
     *
     * <p>Needed because endpoints derived only from a commit's CHANGED files
     * are too narrow for automation: a service-layer change (say
     * SlotService.java) is still exercised over HTTP through a controller the
     * commit never touched, so scanning only the diff finds nothing and the
     * change looks un-automatable when it plainly isn't.
     *
     * <p>Java/Spring MVC only, same as endpoint extraction generally. Files
     * that fail to parse are skipped rather than failing the whole scan.
     *
     * <p>Reads the source AS IT WAS at the given commit, not the working tree -
     * see GitDiffService.SourceAtCommit for why that distinction is the
     * difference between real endpoints and ones that don't exist yet.
     */
    public List<ClassInfo> scanAllEndpoints(GitDiffService.SourceAtCommit source) {
        List<ClassInfo> withEndpoints = new ArrayList<>();
        for (String path : source.paths()) {
            if (!path.toLowerCase().endsWith(".java")) {
                continue;
            }
            collectEndpointClasses(source, path, withEndpoints);
        }
        log.info("Endpoint scan at commit: {} class(es) expose endpoints.", withEndpoints.size());
        return withEndpoints;
    }

    /**
     * Reads the real shape of the given types out of the repository, following
     * their own field types outward.
     *
     * <p>Automation needs this to build a payload the API will actually accept.
     * Given only a type NAME the model invents field names, the create call is
     * rejected, and the precondition it was building silently never exists -
     * which surfaces later as a 404 on the actual assertion and reads like an
     * application defect rather than a bad guess.
     *
     * <p>Types are located by FILE NAME rather than by parsing everything: Java
     * requires a public type's file to match its name, so an index of
     * "Foo.java" -&gt; path costs one directory walk and no AST work, and only
     * the handful of types actually reachable from a request body get parsed.
     *
     * <p>The walk is transitive but bounded - a DTO holding another DTO holding
     * an enum is exactly the case that matters, while an unbounded walk through
     * a domain model would flood the prompt.
     *
     * @param rootTypeNames simple names to start from (the @RequestBody types)
     * @param maxDepth      how far to follow nested types; 2 covers DTO -&gt; DTO -&gt; enum
     * @param maxTypes      hard ceiling on how many schemas come back
     */
    public List<TypeSchema> scanTypeSchemas(GitDiffService.SourceAtCommit source, Set<String> rootTypeNames,
                                            int maxDepth, int maxTypes) {
        if (rootTypeNames == null || rootTypeNames.isEmpty()) {
            return List.of();
        }

        Map<String, String> byTypeName = indexSourcePathsByTypeName(source);
        if (byTypeName.isEmpty()) {
            return List.of();
        }

        List<TypeSchema> schemas = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        Set<String> currentLevel = new LinkedHashSet<>(rootTypeNames);

        for (int depth = 0; depth <= maxDepth && !currentLevel.isEmpty() && schemas.size() < maxTypes; depth++) {
            Set<String> nextLevel = new LinkedHashSet<>();
            for (String typeName : currentLevel) {
                if (schemas.size() >= maxTypes) {
                    break;
                }
                if (!visited.add(typeName)) {
                    continue;
                }
                String path = byTypeName.get(typeName);
                if (path == null) {
                    // Not a project type - a JDK or framework class. Nothing to read.
                    continue;
                }
                TypeSchema schema = readTypeSchema(source, path, typeName);
                if (schema == null) {
                    continue;
                }
                schemas.add(schema);
                for (TypeSchema.FieldSchema field : schema.fields()) {
                    for (String referenced : typeNamesIn(field.type())) {
                        if (!visited.contains(referenced) && byTypeName.containsKey(referenced)) {
                            nextLevel.add(referenced);
                        }
                    }
                }
            }
            currentLevel = nextLevel;
        }

        log.info("Read {} payload type schema(s) at commit (roots: {}).", schemas.size(), rootTypeNames);
        return schemas;
    }

    /** "Foo" -&gt; its path, for every source file in the commit. No parsing. */
    private Map<String, String> indexSourcePathsByTypeName(GitDiffService.SourceAtCommit source) {
        Map<String, String> byTypeName = new java.util.HashMap<>();
        for (String path : source.paths()) {
            if (!path.toLowerCase().endsWith(".java")) {
                continue;
            }
            int slash = path.lastIndexOf('/');
            String fileName = slash >= 0 ? path.substring(slash + 1) : path;
            byTypeName.putIfAbsent(fileName.substring(0, fileName.length() - 5), path);
        }
        return byTypeName;
    }

    private TypeSchema readTypeSchema(GitDiffService.SourceAtCommit source, String path, String typeName) {
        try {
            String content = source.read(path);
            if (content == null) {
                return null;
            }
            CompilationUnit cu = JavaSources.parse(content);

            for (TypeDeclaration<?> type : cu.getTypes()) {
                if (!typeName.equals(type.getNameAsString())) {
                    continue;
                }

                if (type instanceof com.github.javaparser.ast.body.EnumDeclaration enumDecl) {
                    List<String> values = enumDecl.getEntries().stream()
                            .map(e -> e.getNameAsString())
                            .toList();
                    return new TypeSchema(typeName, "enum", values, List.of());
                }

                if (type instanceof com.github.javaparser.ast.body.RecordDeclaration recordDecl) {
                    List<TypeSchema.FieldSchema> components = recordDecl.getParameters().stream()
                            .map(p -> new TypeSchema.FieldSchema(
                                    p.getNameAsString(), p.getType().asString(), isRequired(p.getAnnotations())))
                            .toList();
                    return new TypeSchema(typeName, "record", List.of(), components);
                }

                List<TypeSchema.FieldSchema> fields = new ArrayList<>();
                for (com.github.javaparser.ast.body.FieldDeclaration field : type.getFields()) {
                    // Constants aren't part of the wire format.
                    if (field.isStatic()) {
                        continue;
                    }
                    boolean required = isRequired(field.getAnnotations());
                    for (com.github.javaparser.ast.body.VariableDeclarator variable : field.getVariables()) {
                        fields.add(new TypeSchema.FieldSchema(
                                variable.getNameAsString(), variable.getType().asString(), required));
                    }
                }
                return fields.isEmpty() ? null : new TypeSchema(typeName, "class", List.of(), fields);
            }
        } catch (Exception e) {
            log.trace("Could not read schema for {} from {}: {}", typeName, path, e.getMessage());
        }
        return null;
    }

    /** Bean-validation annotations that make a field non-omittable. */
    private boolean isRequired(com.github.javaparser.ast.NodeList<AnnotationExpr> annotations) {
        return annotations.stream().anyMatch(a -> {
            String name = a.getNameAsString();
            return "NotNull".equals(name) || "NotBlank".equals(name) || "NotEmpty".equals(name);
        });
    }

    /**
     * Pulls candidate type names out of a declared type, so generics and
     * collections resolve to the thing that matters - {@code List<ItemDto>}
     * yields both "List" and "ItemDto", and only the latter matches a project
     * file.
     */
    private List<String> typeNamesIn(String declaredType) {
        if (declaredType == null || declaredType.isBlank()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String token : declaredType.split("[^A-Za-z0-9_$]+")) {
            // Project types are upper-camel; this skips primitives and keywords.
            if (!token.isEmpty() && Character.isUpperCase(token.charAt(0))) {
                names.add(token);
            }
        }
        return names;
    }

    private void collectEndpointClasses(GitDiffService.SourceAtCommit source, String path, List<ClassInfo> out) {
        try {
            String content = source.read(path);
            if (content == null) {
                return;
            }
            // Cheap pre-filter so the vast majority of files never pay for a
            // full parse - a Spring controller has to mention one of these.
            if (!content.contains("Mapping") && !content.contains("Controller")) {
                return;
            }
            ChangedFile synthetic = new ChangedFile(path, ChangedFile.ChangeType.MODIFIED, "", content);

            for (ClassInfo parsed : parseSingleFile(synthetic)) {
                if (parsed.methods().stream().anyMatch(m -> m.apiEndpoint() != null)) {
                    out.add(parsed);
                }
            }
        } catch (Exception e) {
            log.trace("Skipping {} during endpoint scan: {}", path, e.getMessage());
        }
    }

    private List<ClassInfo> parseSingleFile(ChangedFile file) {
        CompilationUnit cu = JavaSources.parse(file.fileContent());

        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getName().asString())
                .orElse("");

        List<String> imports = cu.getImports().stream()
                .map(ImportDeclaration::getNameAsString)
                .collect(Collectors.toList());

        List<ClassInfo> classInfos = new ArrayList<>();

        for (TypeDeclaration<?> type : cu.getTypes()) {
            String simpleName = type.getNameAsString();
            String qualifiedName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;

            // Class-level @RequestMapping base path (if any) - combined with
            // each method's own mapping annotation below. A class with no
            // such annotation (not a controller) simply yields an empty base
            // path, so non-controller methods correctly get no ApiEndpointInfo.
            String basePath = type.getAnnotations().stream()
                    .filter(a -> "RequestMapping".equals(a.getNameAsString()))
                    .findFirst()
                    .map(this::extractPathValue)
                    .orElse("");

            List<MethodInfo> methods = type.getMethods().stream()
                    .map(m -> toMethodInfo(m, basePath))
                    .collect(Collectors.toList());

            Set<String> referenced = new LinkedHashSet<>(imports);
            type.findAll(ClassOrInterfaceType.class)
                    .forEach(t -> referenced.add(t.getNameAsString()));

            classInfos.add(new ClassInfo(
                    qualifiedName,
                    simpleName,
                    packageName,
                    file.path(),
                    imports,
                    methods,
                    new ArrayList<>(referenced),
                    SourceLanguage.JAVA
            ));
        }
        return classInfos;
    }

    private MethodInfo toMethodInfo(MethodDeclaration method, String basePath) {
        List<String> paramTypes = method.getParameters().stream()
                .map(p -> p.getType().asString())
                .collect(Collectors.toList());

        List<String> calledMethods = method.findAll(MethodCallExpr.class).stream()
                .map(MethodCallExpr::getNameAsString)
                .distinct()
                .collect(Collectors.toList());

        String signature = "%s %s(%s)".formatted(
                method.getType().asString(),
                method.getNameAsString(),
                String.join(", ", paramTypes)
        );

        return new MethodInfo(
                method.getNameAsString(),
                signature,
                paramTypes,
                method.getType().asString(),
                calledMethods,
                true, // every method in a changed file is treated as a change candidate
                extractEndpoint(method, basePath)
        );
    }

    /** Returns this method's ApiEndpointInfo if it carries a Spring MVC mapping annotation, else null. */
    private ApiEndpointInfo extractEndpoint(MethodDeclaration method, String basePath) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String name = annotation.getNameAsString();

            String httpMethod = MAPPING_ANNOTATION_TO_METHOD.get(name);
            String methodPath;
            if (httpMethod != null) {
                methodPath = extractPathValue(annotation);
            } else if ("RequestMapping".equals(name)) {
                httpMethod = extractRequestMethod(annotation);
                methodPath = extractPathValue(annotation);
            } else {
                continue;
            }

            String path = combinePaths(basePath, methodPath);
            String requestBodyType = "";
            List<String> pathVariables = new ArrayList<>();
            List<String> queryParams = new ArrayList<>();

            for (Parameter param : method.getParameters()) {
                for (AnnotationExpr paramAnnotation : param.getAnnotations()) {
                    switch (paramAnnotation.getNameAsString()) {
                        case "RequestBody" -> requestBodyType = param.getType().asString();
                        case "PathVariable" -> pathVariables.add(param.getNameAsString());
                        case "RequestParam" -> queryParams.add(param.getNameAsString());
                        default -> { /* not a param shape we track */ }
                    }
                }
            }

            return new ApiEndpointInfo(
                    httpMethod == null ? "GET" : httpMethod,
                    path,
                    requestBodyType,
                    pathVariables,
                    queryParams
            );
        }
        return null;
    }

    /** Joins a class-level base path and a method-level path with exactly one "/" between them. */
    private String combinePaths(String basePath, String methodPath) {
        String base = basePath == null ? "" : basePath.trim();
        String method = methodPath == null ? "" : methodPath.trim();
        if (base.isEmpty()) {
            return method.isEmpty() ? "/" : method;
        }
        if (method.isEmpty()) {
            return base;
        }
        String left = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String right = method.startsWith("/") ? method : "/" + method;
        return left + right;
    }

    /**
     * Extracts the "value"/"path" element of a mapping annotation - handles all
     * three JavaParser annotation shapes (@Foo, @Foo("x"), @Foo(value="x")) and
     * an array value (@Foo({"a","b"})), taking the first element for the array
     * case since a test-case prompt only needs one representative path.
     */
    private String extractPathValue(AnnotationExpr annotation) {
        if (annotation instanceof SingleMemberAnnotationExpr single) {
            return stringFrom(single.getMemberValue());
        }
        if (annotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if ("value".equals(pair.getNameAsString()) || "path".equals(pair.getNameAsString())) {
                    return stringFrom(pair.getValue());
                }
            }
        }
        return "";
    }

    /** Extracts @RequestMapping(method = RequestMethod.POST)'s HTTP method, or null if unspecified (defaults to GET). */
    private String extractRequestMethod(AnnotationExpr annotation) {
        if (!(annotation instanceof NormalAnnotationExpr normal)) {
            return null;
        }
        for (MemberValuePair pair : normal.getPairs()) {
            if ("method".equals(pair.getNameAsString()) && pair.getValue() instanceof FieldAccessExpr fieldAccess) {
                return fieldAccess.getNameAsString();
            }
        }
        return null;
    }

    private String stringFrom(Expression expr) {
        if (expr instanceof StringLiteralExpr literal) {
            return literal.asString();
        }
        if (expr instanceof ArrayInitializerExpr array && !array.getValues().isEmpty()) {
            return stringFrom(array.getValues().get(0));
        }
        return "";
    }
}
