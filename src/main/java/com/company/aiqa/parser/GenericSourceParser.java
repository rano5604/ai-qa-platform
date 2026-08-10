package com.company.aiqa.parser;

import com.company.aiqa.model.ApiEndpointInfo;
import com.company.aiqa.model.ChangedFile;
import com.company.aiqa.model.ClassInfo;
import com.company.aiqa.model.MethodInfo;
import com.company.aiqa.model.SourceLanguage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort, regex-based structural extraction for languages that don't
 * have a dedicated AST parser wired up (everything except Java - see
 * JavaParserService for that). This intentionally trades AST accuracy for
 * zero extra dependencies: it recognizes common "class Foo" / "function
 * bar(...)" shaped declarations across the supported languages well enough
 * to give the LLM useful structure and to seed the dependency graph, but
 * it will miss unusual syntax (multi-line signatures, decorators/annotations
 * split across lines, etc). If a language needs true accuracy, that's the
 * signal to add a real parser for it instead (e.g. an ANTLR or
 * language-native AST library) and route it here from SourceParsingService.
 */
@Service
public class GenericSourceParser {

    // One declaration-shaped construct per family; deliberately loose.
    private static final Pattern TYPE_DECL = Pattern.compile(
            "\\b(?:class|interface|struct|enum|mixin|trait)\\s+([A-Za-z_][A-Za-z0-9_]*)");

    private static final Pattern FUNCTION_DECL = Pattern.compile(
            "\\b(?:function|def|func|fn)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)"
                    + "|\\b([A-Za-z_][A-Za-z0-9_<>,\\[\\]? ]*?)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)\\s*\\{");

    private static final Pattern IMPORT_LIKE = Pattern.compile(
            "^\\s*(?:import|from|require|using)\\s+['\"]?([\\w./:@-]+)['\"]?", Pattern.MULTILINE);

    private static final Pattern IDENTIFIER = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)\\b");

    /**
     * Express (app.get/router.post/...) and Flask/FastAPI method-shorthand
     * decorators (@app.get(...), @router.post(...)) share the same
     * "receiver.verb('/path'" shape whether called directly or used as a
     * decorator, so one pattern covers both. The receiver name is
     * deliberately restricted to common router/app variable names (app,
     * router, api, blueprint, bp) rather than matching ANY ".get(" - that
     * would false-positive constantly on plain map/dict lookups
     * (JS Map.get(key), Python dict.get(key)), which are far more common in
     * real code than routing calls.
     */
    private static final Pattern VERB_ROUTE = Pattern.compile(
            "\\b(?:app|router|api|blueprint|bp)\\.(get|post|put|delete|patch)\\(\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);

    /** Flask's generic @app.route("/path", methods=["POST"]) form - method defaults to GET when omitted. */
    private static final Pattern GENERIC_ROUTE = Pattern.compile(
            "@\\w+\\.route\\(\\s*[\"']([^\"']+)[\"'](?:\\s*,\\s*methods\\s*=\\s*\\[([^\\]]*)])?",
            Pattern.CASE_INSENSITIVE);

    public List<ClassInfo> parse(ChangedFile file, SourceLanguage language) {
        String content = file.fileContent();
        if (content == null || content.isBlank()) {
            return List.of();
        }

        List<String> imports = new ArrayList<>();
        Matcher importMatcher = IMPORT_LIKE.matcher(content);
        while (importMatcher.find()) {
            imports.add(importMatcher.group(1));
        }

        // Referenced capitalized identifiers - a cheap proxy for "types this file touches",
        // used the same way JavaParserService's referencedTypes feeds the dependency graph.
        Set<String> referenced = new LinkedHashSet<>(imports);
        Matcher idMatcher = IDENTIFIER.matcher(content);
        while (idMatcher.find()) {
            referenced.add(idMatcher.group(1));
        }

        List<MethodInfo> methods = extractMethods(content);
        methods.addAll(extractRouteEndpoints(content));

        Matcher typeMatcher = TYPE_DECL.matcher(content);
        List<ClassInfo> results = new ArrayList<>();
        boolean foundAny = false;
        while (typeMatcher.find()) {
            foundAny = true;
            String simpleName = typeMatcher.group(1);
            results.add(new ClassInfo(
                    simpleName,
                    simpleName,
                    "",
                    file.path(),
                    imports,
                    methods,
                    new ArrayList<>(referenced),
                    language
            ));
        }

        // Some languages (Dart widgets, Go files, Python scripts) may have no
        // class-shaped declaration at all - fall back to one ClassInfo keyed
        // by the file name so the file still gets AI-generated coverage.
        if (!foundAny) {
            String fallbackName = fileNameWithoutExtension(file.path());
            results.add(new ClassInfo(
                    fallbackName,
                    fallbackName,
                    "",
                    file.path(),
                    imports,
                    methods,
                    new ArrayList<>(referenced),
                    language
            ));
        }

        return results;
    }

    private List<MethodInfo> extractMethods(String content) {
        List<MethodInfo> methods = new ArrayList<>();
        Matcher matcher = FUNCTION_DECL.matcher(content);
        while (matcher.find()) {
            String name = matcher.group(1) != null ? matcher.group(1) : matcher.group(4);
            String params = matcher.group(1) != null ? matcher.group(2) : matcher.group(5);
            if (name == null) continue;

            List<String> paramTypes = params == null || params.isBlank()
                    ? List.of()
                    : List.of(params.split(","));

            methods.add(new MethodInfo(
                    name,
                    name + "(" + (params == null ? "" : params.trim()) + ")",
                    paramTypes,
                    "",
                    List.of(),
                    true,
                    null
            ));
        }
        return methods;
    }

    /**
     * Best-effort route detection for non-AST-parsed languages (see VERB_ROUTE
     * / GENERIC_ROUTE javadoc). Each match becomes its own synthetic
     * MethodInfo (named "&lt;METHOD&gt; &lt;path&gt;") carrying an
     * ApiEndpointInfo, rather than trying to fuzzily attach it to whichever
     * nearby function extractMethods() found - simpler and more robust than
     * cross-referencing two independent best-effort matches. Request-body
     * shape isn't extracted here (there's no reliable way to infer it from
     * arbitrary JS/Python without real parsing); PromptBuilder asks the LLM
     * to synthesize a plausible payload from the path/feature context instead.
     */
    private List<MethodInfo> extractRouteEndpoints(String content) {
        List<MethodInfo> endpoints = new ArrayList<>();

        Matcher verbMatcher = VERB_ROUTE.matcher(content);
        while (verbMatcher.find()) {
            String httpMethod = verbMatcher.group(1).toUpperCase(Locale.ROOT);
            String path = verbMatcher.group(2);
            endpoints.add(routeMethodInfo(httpMethod, path));
        }

        Matcher genericMatcher = GENERIC_ROUTE.matcher(content);
        while (genericMatcher.find()) {
            String path = genericMatcher.group(1);
            String methodsList = genericMatcher.group(2);
            String httpMethod = firstMethodOrGet(methodsList);
            endpoints.add(routeMethodInfo(httpMethod, path));
        }

        return endpoints;
    }

    private MethodInfo routeMethodInfo(String httpMethod, String path) {
        ApiEndpointInfo endpoint = new ApiEndpointInfo(httpMethod, path, "", List.of(), List.of());
        String name = httpMethod + " " + path;
        return new MethodInfo(name, name, List.of(), "", List.of(), true, endpoint);
    }

    private String firstMethodOrGet(String methodsList) {
        if (methodsList == null || methodsList.isBlank()) {
            return "GET";
        }
        String first = methodsList.split(",")[0];
        return first.replaceAll("[\"'\\s]", "").toUpperCase(Locale.ROOT);
    }

    private String fileNameWithoutExtension(String path) {
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
