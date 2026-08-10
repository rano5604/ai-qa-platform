package com.company.aiqa.parser;

import com.company.aiqa.model.ApiEndpointInfo;
import com.company.aiqa.model.ChangedFile;
import com.company.aiqa.model.ClassInfo;
import com.company.aiqa.model.MethodInfo;
import com.company.aiqa.model.SourceLanguage;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
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

    static {
        // StaticJavaParser defaults to an old language level that rejects
        // records (14+), text blocks (15+), sealed classes (17+), and
        // pattern matching in switch (21+) - all common in real codebases.
        // BLEEDING_EDGE tracks the latest preview/finalized features
        // supported by the javaparser-core version on the classpath, so
        // this stays current without needing to hardcode a specific level.
        StaticJavaParser.getConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);
    }

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

    private List<ClassInfo> parseSingleFile(ChangedFile file) {
        CompilationUnit cu = StaticJavaParser.parse(file.fileContent());

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
