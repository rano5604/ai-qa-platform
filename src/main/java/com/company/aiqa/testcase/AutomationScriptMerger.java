package com.company.aiqa.testcase;

import com.company.aiqa.model.TestCaseResult;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Folds the per-batch automation scripts generated for one commit into a
 * single Java class.
 *
 * <p>Generation is batched to stay inside the model's output limit, which
 * naturally yields several files (ShopSlotManagementTest, ...Test2, ...Test3).
 * That's an artefact of how it was produced, not something a reader should
 * have to care about - one commit's automation belongs in one file, named for
 * the commit it verifies.
 *
 * <p>Merging is done with a real parser rather than string concatenation:
 * imports have to be unioned and de-duplicated, only one {@code @BeforeClass}
 * may survive, and method names can collide across batches. Text munging gets
 * all three wrong.
 */
@Service
public class AutomationScriptMerger {

    private static final Logger log = LoggerFactory.getLogger(AutomationScriptMerger.class);

    private static final String GENERATED_PACKAGE = "com.company.aiqa.generated";

    /**
     * A commit hash starts with a digit often enough that it can't be a Java
     * identifier on its own, so the class is prefixed. The full hash is kept
     * verbatim after it, so the file still names the commit exactly.
     */
    private static final String CLASS_PREFIX = "AutomationTest_";

    /**
     * Class-level setup annotations, of which exactly one may survive a merge.
     * TestNG's @BeforeClass is what the generator emits now; @BeforeAll is
     * JUnit's and is matched too, so a script generated before the switch still
     * merges cleanly instead of producing a class with several setup methods.
     */
    private static final Set<String> SETUP_ANNOTATIONS = Set.of("BeforeClass", "BeforeAll");

    /**
     * An OWN parser instance rather than StaticJavaParser, whose language
     * level is global mutable state: generated scripts use text blocks (the
     * prompt requires them, precisely to avoid escaping bugs), and those only
     * parse at JAVA_15+. Depending on some other class's static initializer
     * having set that global first makes merging succeed or fail based on
     * class-load order.
     */
    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));

    /**
     * @param scripts    every per-batch script generated for this commit
     * @param commitHash the commit these verify - becomes part of the class name
     * @return one TestCaseResult holding the merged source (not yet written), or
     *         the single input unchanged when there's nothing to merge
     */
    public TestCaseResult merge(List<TestCaseResult> scripts, String commitHash) {
        if (scripts.isEmpty()) {
            throw new IllegalArgumentException("Nothing to merge - no scripts were generated.");
        }

        String className = CLASS_PREFIX + sanitizeForClassName(commitHash);
        Set<String> imports = new LinkedHashSet<>();
        List<BodyDeclaration<?>> members = new ArrayList<>();
        Set<String> takenSignatures = new HashSet<>();
        Set<String> fieldNames = new HashSet<>();
        boolean setupKept = false;

        for (TestCaseResult script : scripts) {
            ParseResult<CompilationUnit> parsed = parser.parse(script.testCode());
            if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
                // A script that doesn't parse also wouldn't compile - skip it
                // rather than corrupting the merged file, and say so loudly.
                log.warn("Skipping '{}' during merge - it doesn't parse: {}",
                        script.testFileName(), parsed.getProblems());
                continue;
            }
            CompilationUnit cu = parsed.getResult().get();

            cu.getImports().stream().map(ImportDeclaration::toString).forEach(i -> imports.add(i.trim()));

            for (TypeDeclaration<?> type : cu.getTypes()) {
                if (!(type instanceof ClassOrInterfaceDeclaration decl)) {
                    continue;
                }

                // Collect this script's members first, so a rename can rewrite
                // the call sites that live alongside the method being renamed.
                List<BodyDeclaration<?>> scriptMembers = new ArrayList<>();

                for (BodyDeclaration<?> member : decl.getMembers()) {
                    if (member instanceof MethodDeclaration method) {
                        boolean isSetup = method.getAnnotations().stream()
                                .anyMatch(a -> SETUP_ANNOTATIONS.contains(a.getNameAsString()));
                        if (isSetup) {
                            // Every batch emits the identical baseURI setup;
                            // keeping them all would leave several @BeforeClass
                            // methods running in unspecified order.
                            if (setupKept) {
                                continue;
                            }
                            setupKept = true;
                        }
                    } else if (member instanceof FieldDeclaration field) {
                        // A duplicate field name is an unconditional compile
                        // error, and unlike methods there's no overloading to
                        // fall back on - keep the first declaration.
                        if (!claimFieldNames(field, fieldNames)) {
                            continue;
                        }
                    }
                    scriptMembers.add(member);
                }

                renameCollidingMethods(scriptMembers, takenSignatures);
                members.addAll(scriptMembers);
            }
        }

        if (members.isEmpty()) {
            throw new IllegalStateException(
                    "None of the " + scripts.size() + " generated script(s) could be parsed for merging.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(GENERATED_PACKAGE).append(";\n\n");
        imports.forEach(i -> sb.append(i).append("\n"));
        sb.append("\n/**\n * Automated API tests for commit ").append(commitHash).append(".\n")
                .append(" * Generated by ai-qa-platform from that commit's manual test cases.\n */\n");
        sb.append("public class ").append(className).append(" {\n\n");
        for (BodyDeclaration<?> member : members) {
            sb.append(indent(member.toString())).append("\n\n");
        }
        sb.append("}\n");

        String fileName = className + ".java";
        log.info("Merged {} script(s) into {} ({} member(s)).", scripts.size(), fileName, members.size());
        return new TestCaseResult(className, fileName, sb.toString(), null);
    }

    /** Keeps only characters legal in a Java identifier. */
    private String sanitizeForClassName(String commitHash) {
        if (commitHash == null || commitHash.isBlank()) {
            return "unknown";
        }
        return commitHash.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    /**
     * Renames methods whose SIGNATURE is already taken by an earlier batch, and
     * rewrites the calls to them inside the same batch.
     *
     * <p>Two things here were previously wrong, and both produced a merged file
     * that didn't compile.
     *
     * <p>First, collisions are judged on name AND parameter types, not name
     * alone. Batches routinely emit a helper of the same name with different
     * parameters - {@code createShop()} in one, {@code createShop(Integer
     * areaId)} in another - and those are a legal Java overload that should
     * both survive untouched. Renaming on name alone mangled a perfectly valid
     * pair.
     *
     * <p>Second, when a rename genuinely is needed, the call sites have to move
     * with it. Renaming only the declaration left every caller in that batch
     * pointing at the earlier batch's method, which then failed to compile
     * against the wrong argument list - the "cannot be applied to given types"
     * error, which is far more confusing than the duplicate would have been.
     */
    private void renameCollidingMethods(List<BodyDeclaration<?>> scriptMembers, Set<String> takenSignatures) {
        for (BodyDeclaration<?> member : scriptMembers) {
            if (!(member instanceof MethodDeclaration method)) {
                continue;
            }
            String originalName = method.getNameAsString();
            int parameterCount = method.getParameters().size();

            String uniqueName = originalName;
            for (int suffix = 2; takenSignatures.contains(signatureOf(uniqueName, method)); suffix++) {
                uniqueName = originalName + suffix;
            }

            if (!uniqueName.equals(originalName)) {
                log.info("Renaming '{}' to '{}' while merging - that exact signature is already taken.",
                        originalName, uniqueName);
                method.setName(uniqueName);
                rewriteCalls(scriptMembers, originalName, parameterCount, uniqueName);
            }
            takenSignatures.add(signatureOf(uniqueName, method));
        }
    }

    /** name + erased parameter types - what actually has to be unique in a class. */
    private String signatureOf(String name, MethodDeclaration method) {
        return name + "(" + method.getParameters().stream()
                .map(p -> p.getType().asString())
                .reduce((a, b) -> a + "," + b).orElse("") + ")";
    }

    /**
     * Points unqualified calls of {@code oldName} with a matching argument
     * count at {@code newName}. Argument count stands in for real overload
     * resolution, which would need a symbol solver and the whole classpath -
     * within one generated batch it separates the cases that actually arise.
     */
    private void rewriteCalls(List<BodyDeclaration<?>> scriptMembers, String oldName, int parameterCount, String newName) {
        for (BodyDeclaration<?> member : scriptMembers) {
            member.findAll(MethodCallExpr.class).stream()
                    // An unqualified call, or an explicit this.foo() - never
                    // someRequest.foo(), which is a different method entirely.
                    .filter(call -> call.getScope().isEmpty() || call.getScope().get() instanceof ThisExpr)
                    .filter(call -> oldName.equals(call.getNameAsString()))
                    .filter(call -> call.getArguments().size() == parameterCount)
                    .forEach(call -> call.setName(newName));
        }
    }

    /**
     * Registers a field's variable names, returning false when any of them is
     * already declared by an earlier batch.
     */
    private boolean claimFieldNames(FieldDeclaration field, Set<String> fieldNames) {
        List<String> names = field.getVariables().stream()
                .map(v -> v.getNameAsString())
                .toList();
        if (names.stream().anyMatch(fieldNames::contains)) {
            log.info("Dropping duplicate field declaration {} while merging.", names);
            return false;
        }
        fieldNames.addAll(names);
        return true;
    }

    /** Re-indents a member by one level so the merged class reads normally. */
    private String indent(String code) {
        return code.lines().map(line -> line.isBlank() ? line : "    " + line)
                .reduce((a, b) -> a + "\n" + b).orElse(code);
    }
}
