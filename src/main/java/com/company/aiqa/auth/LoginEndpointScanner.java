package com.company.aiqa.auth;

import com.company.aiqa.parser.JavaSources;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads the TARGET project's own source to answer two questions a run against an
 * authenticated service needs and can't get any other way: <b>where do I log
 * in</b>, and <b>what does this service use to guard its endpoints</b>.
 *
 * <p>This is the source half of the auth story. {@link AuthTokenResolver} does
 * the login at run time; but a caller who only has credentials shouldn't also
 * have to know the login path or which JSON field the token sits in. Both are in
 * the code: a controller method mapped to a login-ish path, returning a DTO with
 * a token field. So this finds them, and a run supplies just the credentials.
 *
 * <p>It also reports the <i>permission mechanism</i> - Spring Security, a JWT
 * bearer filter, method-level {@code @PreAuthorize} - so a report can say "this
 * target authenticates, here is how" instead of leaving a wall of 401s
 * unexplained. That is discovery for a human and for the diagnosis banner, not
 * an attempt to model authorization; role reasoning is deliberately out of
 * scope, because getting it subtly wrong is worse than not attempting it.
 *
 * <p>Entirely generic and best-effort: nothing here names a project, and every
 * lookup gives up quietly (an empty {@link Optional}, never a thrown exception)
 * on anything it can't find or parse - a run then falls back to explicit values
 * or common defaults, exactly as if this scan had never run.
 */
public final class LoginEndpointScanner {

    private static final Logger log = LoggerFactory.getLogger(LoginEndpointScanner.class);

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "target", "build", "node_modules", ".idea", "out", "dist", ".gradle");

    /** A mapping path that reads like authentication rather than a business resource. */
    private static final Pattern LOGIN_PATH = Pattern.compile(
            "(?i)(sign[\\-_]?in|log[\\-_]?in|authenticate|/token\\b|/auth\\b|/oauth)");

    /** Response field names that carry a token, most specific first. */
    private static final List<String> TOKEN_FIELDS = List.of(
            "accessToken", "access_token", "idToken", "id_token", "jwt", "token", "authToken");

    /** Simple names of the mapping annotations, mapped to their HTTP method. */
    private static final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "PostMapping", "RequestMapping", "GetMapping", "PutMapping");

    private LoginEndpointScanner() {
    }

    /**
     * What the scan found. Any field may be null: a login endpoint with no
     * determinable token field still yields a useful {@code path}, and a service
     * whose mechanism is recognised but whose login endpoint isn't still yields a
     * useful {@code mechanism} for the report.
     *
     * @param path      the login endpoint path (e.g. {@code /api/auth/signin}), or null
     * @param tokenPath the response field the token sits at (e.g. {@code token}), or null
     * @param mechanism a human-readable description of the guard (e.g.
     *                  {@code "Spring Security (JWT bearer filter)"}), or null
     */
    public record LoginDiscovery(String path, String tokenPath, String mechanism) {
        public boolean isEmpty() {
            return path == null && tokenPath == null && mechanism == null;
        }
    }

    /**
     * @param repoRoot the target project's local checkout; null yields an empty
     *                 discovery, matching every other optional-by-construction
     *                 step in this pipeline
     */
    public static LoginDiscovery discover(Path repoRoot) {
        if (repoRoot == null || !Files.isDirectory(repoRoot)) {
            return new LoginDiscovery(null, null, null);
        }
        List<Path> javaFiles = javaFiles(repoRoot);

        String loginPath = null;
        String returnTypeSimpleName = null;
        SecuritySignals security = new SecuritySignals();

        for (Path file : javaFiles) {
            String text;
            try {
                text = Files.readString(file);
            } catch (IOException e) {
                continue;
            }
            // Aggregate signals across the WHOLE tree, then decide once: a DTO
            // carrying the literal "Bearer" must not, on its own, outvote the
            // security config that actually names Spring Security.
            security.accumulate(text);
            if (loginPath == null && text.contains("Mapping")) {
                Optional<LoginMethod> found = findLoginMethod(text);
                if (found.isPresent()) {
                    loginPath = found.get().path();
                    returnTypeSimpleName = found.get().returnTypeSimpleName();
                }
            }
        }

        String tokenPath = returnTypeSimpleName == null ? null : findTokenField(javaFiles, returnTypeSimpleName);
        String mechanism = security.label();
        LoginDiscovery discovery = new LoginDiscovery(loginPath, tokenPath, mechanism);
        if (!discovery.isEmpty()) {
            log.info("Auth discovery: login path={}, token field={}, mechanism={}", loginPath, tokenPath, mechanism);
        }
        return discovery;
    }

    private record LoginMethod(String path, String returnTypeSimpleName) {
    }

    private static Optional<LoginMethod> findLoginMethod(String source) {
        CompilationUnit cu;
        try {
            cu = JavaSources.parse(source);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        for (TypeDeclaration<?> type : cu.getTypes()) {
            boolean isController = type.getAnnotations().stream()
                    .anyMatch(a -> a.getNameAsString().equals("RestController")
                            || a.getNameAsString().equals("Controller"));
            if (!isController) {
                continue;
            }
            String basePath = mappingValue(type.getAnnotations()).orElse("");
            for (MethodDeclaration method : type.getMethods()) {
                Optional<String> methodPath = method.getAnnotations().stream()
                        .filter(a -> MAPPING_ANNOTATIONS.contains(a.getNameAsString()))
                        .findFirst()
                        .flatMap(a -> mappingValue(List.of(a)));
                if (methodPath.isEmpty()) {
                    continue;
                }
                String full = joinPaths(basePath, methodPath.get());
                if (LOGIN_PATH.matcher(full).find()) {
                    return Optional.of(new LoginMethod(full, returnTypeSimpleName(method)));
                }
            }
        }
        return Optional.empty();
    }

    /** The token DTO's field name, read from the login method's declared return type. */
    private static String findTokenField(List<Path> javaFiles, String returnTypeSimpleName) {
        String targetFile = returnTypeSimpleName + ".java";
        for (Path file : javaFiles) {
            if (file.getFileName() == null || !file.getFileName().toString().equals(targetFile)) {
                continue;
            }
            Set<String> fields = fieldNames(file, returnTypeSimpleName);
            for (String candidate : TOKEN_FIELDS) {
                for (String field : fields) {
                    if (field.equalsIgnoreCase(candidate)) {
                        return field;
                    }
                }
            }
        }
        return null;
    }

    private static Set<String> fieldNames(Path file, String simpleClassName) {
        Set<String> names = new LinkedHashSet<>();
        try {
            CompilationUnit cu = JavaSources.parse(Files.readString(file));
            for (TypeDeclaration<?> type : cu.getTypes()) {
                if (!type.getNameAsString().equals(simpleClassName)) {
                    continue;
                }
                if (type instanceof RecordDeclaration record) {
                    record.getParameters().forEach(p -> names.add(p.getNameAsString()));
                }
                for (BodyDeclaration<?> member : type.getMembers()) {
                    if (member instanceof FieldDeclaration field) {
                        for (VariableDeclarator v : field.getVariables()) {
                            names.add(v.getNameAsString());
                        }
                    }
                }
            }
        } catch (RuntimeException | IOException e) {
            log.debug("Could not read fields of {}: {}", file, e.getMessage());
        }
        return names;
    }

    /**
     * Security signals seen across the whole source tree. Aggregated rather than
     * decided per file, so the strongest evidence wins regardless of walk order -
     * a config class naming Spring Security must not be outvoted by an unrelated
     * DTO that merely contains the word "Bearer".
     */
    private static final class SecuritySignals {
        private boolean springSecurity;
        private boolean jwt;
        private boolean methodSecurity;

        void accumulate(String text) {
            springSecurity |= text.contains("SecurityFilterChain")
                    || text.contains("WebSecurityConfigurerAdapter")
                    || text.contains("@EnableWebSecurity");
            jwt |= text.contains("Bearer") || text.toLowerCase(Locale.ROOT).contains("jwt")
                    || text.contains("OncePerRequestFilter");
            methodSecurity |= text.contains("@PreAuthorize") || text.contains("@Secured")
                    || text.contains("@RolesAllowed");
        }

        /** A short label for the guard, or null when nothing recognisable is present. */
        String label() {
            if (springSecurity && jwt) {
                return "Spring Security (JWT bearer filter)";
            }
            if (springSecurity) {
                return methodSecurity ? "Spring Security (method-level @PreAuthorize)" : "Spring Security";
            }
            if (jwt) {
                return "JWT bearer token";
            }
            return null;
        }
    }

    /** Reads the single-value {@code value}/{@code path} of a mapping annotation. */
    private static Optional<String> mappingValue(List<AnnotationExpr> annotations) {
        for (AnnotationExpr a : annotations) {
            String s = a.toString();
            java.util.regex.Matcher m = Pattern.compile("\"([^\"]*)\"").matcher(s);
            if (m.find()) {
                return Optional.of(m.group(1));
            }
            // A mapping with no path at all (@PostMapping) maps to the class base path.
            if (MAPPING_ANNOTATIONS.contains(a.getNameAsString()) || a.getNameAsString().endsWith("Mapping")) {
                return Optional.of("");
            }
        }
        return Optional.empty();
    }

    private static String returnTypeSimpleName(MethodDeclaration method) {
        String type = method.getType().asString();
        // Unwrap ResponseEntity<X>, Mono<X>, etc. to X.
        int lt = type.indexOf('<');
        if (lt >= 0) {
            int gt = type.lastIndexOf('>');
            if (gt > lt) {
                type = type.substring(lt + 1, gt);
            }
        }
        // Drop any remaining generic/array noise and package qualifier.
        type = type.replaceAll("[\\[\\]<>]", "").trim();
        int dot = type.lastIndexOf('.');
        if (dot >= 0) {
            type = type.substring(dot + 1);
        }
        return type.isBlank() ? null : type;
    }

    private static String joinPaths(String base, String path) {
        String b = base == null ? "" : base.trim();
        String p = path == null ? "" : path.trim();
        if (b.isEmpty()) {
            return p.startsWith("/") ? p : "/" + p;
        }
        if (!b.startsWith("/")) {
            b = "/" + b;
        }
        b = b.replaceAll("/+$", "");
        if (p.isEmpty()) {
            return b;
        }
        return b + (p.startsWith("/") ? p : "/" + p);
    }

    private static List<Path> javaFiles(Path repoRoot) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(repoRoot)) {
            paths.filter(p -> p.getFileName() != null && p.getFileName().toString().endsWith(".java"))
                    .filter(p -> !isUnderSkippedDir(repoRoot, p))
                    .forEach(files::add);
        } catch (IOException e) {
            log.debug("Could not scan {} for source: {}", repoRoot, e.getMessage());
        }
        return files;
    }

    private static boolean isUnderSkippedDir(Path root, Path path) {
        Path relative;
        try {
            relative = root.relativize(path);
        } catch (IllegalArgumentException e) {
            return true;
        }
        for (Path segment : relative) {
            if (SKIP_DIRS.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }
}
