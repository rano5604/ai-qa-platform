package com.company.aiqa.testcase;

import com.company.aiqa.parser.JavaSources;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads a single DTO class from the TARGET project's own source - not this
 * platform's - to find which fields it enforces with Bean Validation's
 * presence annotations ({@code @NotNull}, {@code @NotBlank}, {@code @NotEmpty}).
 * A second, independent source of "this field is actually required" alongside
 * the OpenAPI contract: springdoc only lists a field in a schema's "required"
 * array when it can trace a JSR-380 annotation to it at generation time, so a
 * field the service genuinely rejects when absent - but whose annotation
 * springdoc didn't pick up - is invisible to the contract while still sitting
 * right there on the DTO. See mms's {@code CreateMerchantRequest.accountNo}:
 * required at runtime (a 400 names it explicitly), absent from the schema's
 * {@code required} array, present as {@code @NotBlank} on the field itself.
 *
 * <p>Deliberately narrow, the same way the contract-only repair was: finds the
 * file by exact simple name with no build-tool or classpath awareness, matches
 * presence annotations by their SIMPLE name so both {@code jakarta.validation}
 * and the older {@code javax.validation} match without resolving which is on
 * the classpath, and gives up quietly - an empty result, never a thrown
 * exception - on anything it can't find or parse. A missed field costs nothing
 * beyond the gap that already existed; a wrong one would mean injecting into a
 * payload with no real ground truth behind it, which is worse than the bug
 * this exists to fix.
 */
final class RequestDtoSourceScanner {

    private static final Logger log = LoggerFactory.getLogger(RequestDtoSourceScanner.class);

    private static final Set<String> PRESENCE_ANNOTATIONS = Set.of("NotNull", "NotBlank", "NotEmpty");

    /** Never worth descending into - build output, VCS metadata, vendored dependencies. */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "target", "build", "node_modules", ".idea", "out", "dist", ".gradle");

    private RequestDtoSourceScanner() {
    }

    /**
     * @param repoRoot        the target project's local checkout; {@code null}
     *                        skips this entirely, matching every other
     *                        optional-by-construction step in this pipeline
     * @param simpleClassName e.g. "CreateMerchantRequest"; blank skips
     * @return field/record-component names carrying a presence annotation, or
     *         an empty set when the class can't be found or parsed
     */
    static Set<String> requiredFieldNames(Path repoRoot, String simpleClassName) {
        if (repoRoot == null || simpleClassName == null || simpleClassName.isBlank()) {
            return Set.of();
        }
        Path file = findClassFile(repoRoot, simpleClassName);
        if (file == null) {
            return Set.of();
        }
        try {
            CompilationUnit cu = JavaSources.parse(Files.readString(file));
            Set<String> found = new LinkedHashSet<>();
            for (TypeDeclaration<?> type : cu.getTypes()) {
                collectFromType(type, simpleClassName, found);
            }
            return found;
        } catch (ParseProblemException | IOException e) {
            log.debug("Could not read required fields from {} for '{}': {}", file, simpleClassName, e.getMessage());
            return Set.of();
        }
    }

    private static Path findClassFile(Path repoRoot, String simpleClassName) {
        String targetFileName = simpleClassName + ".java";
        try (Stream<Path> paths = Files.walk(repoRoot)) {
            return paths
                    .filter(p -> !isUnderSkippedDir(repoRoot, p))
                    .filter(p -> p.getFileName() != null && p.getFileName().toString().equals(targetFileName))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.debug("Could not scan {} for '{}': {}", repoRoot, targetFileName, e.getMessage());
            return null;
        }
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

    /** Recurses into nested types too - a request DTO declared inside its controller/service is common. */
    private static void collectFromType(TypeDeclaration<?> type, String simpleClassName, Set<String> out) {
        if (!type.getNameAsString().equals(simpleClassName)) {
            for (BodyDeclaration<?> member : type.getMembers()) {
                if (member instanceof TypeDeclaration<?> nested) {
                    collectFromType(nested, simpleClassName, out);
                }
            }
            return;
        }

        if (type instanceof RecordDeclaration record) {
            for (Parameter param : record.getParameters()) {
                if (hasPresenceAnnotation(param.getAnnotations())) {
                    out.add(param.getNameAsString());
                }
            }
        }
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof FieldDeclaration field && hasPresenceAnnotation(field.getAnnotations())) {
                for (VariableDeclarator v : field.getVariables()) {
                    out.add(v.getNameAsString());
                }
            }
        }
    }

    private static boolean hasPresenceAnnotation(List<AnnotationExpr> annotations) {
        return annotations.stream().anyMatch(a -> PRESENCE_ANNOTATIONS.contains(a.getNameAsString()));
    }
}
