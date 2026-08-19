package com.company.aiqa.testcase;

import com.company.aiqa.parser.JavaSources;
import com.company.aiqa.model.TestCaseResult;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
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
     * Imports written into every merged script whether or not the model asked
     * for them.
     *
     * <p>These cover the whole vocabulary the automation prompt permits: REST
     * Assured's entry points, TestNG's annotations and {@code Assert}, and the
     * Hamcrest matchers. Anything the script does not use is simply an unused
     * import - javac does not even warn by default - whereas a single missing
     * one fails the compile and takes every test in the file with it, which
     * surfaces as a run reporting 0 tests rather than as a fixable error in
     * one method.
     *
     * <p>Ordered so the on-disk file reads conventionally: plain imports first,
     * static ones after.
     */
    private static final List<String> REQUIRED_IMPORTS = List.of(
            "import io.restassured.RestAssured;",
            "import io.restassured.http.ContentType;",
            "import io.restassured.response.Response;",
            "import org.testng.Assert;",
            "import org.testng.annotations.AfterClass;",
            "import org.testng.annotations.BeforeClass;",
            "import org.testng.annotations.Test;",
            "import static io.restassured.RestAssured.given;",
            "import static org.hamcrest.Matchers.*;");

    /**
     * A parser configured here rather than StaticJavaParser, whose language
     * level is per-thread global state: generated scripts use text blocks (the
     * prompt requires them, precisely to avoid escaping bugs), and those only
     * parse at JAVA_15+. Depending on some other class's static initializer
     * having set that state first makes merging succeed or fail based on which
     * thread runs it - see JavaSources.
     */
    private JavaParser parser() {
        return JavaSources.parser(JavaSources.ANALYSIS_LEVEL);
    }

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
        // Seeded, not merely collected: the model reliably writes the code but
        // intermittently forgets an import for it - typically static-importing
        // Assert.assertTrue and then calling the class-qualified
        // Assert.assertTrue(..), which does not compile. One missing import
        // fails the whole file, so every test in the run is lost and the report
        // shows 0 tests rather than any result at all. These are the imports
        // every generated REST Assured/TestNG script needs; an unused import is
        // harmless, a missing one is fatal, so we always write them.
        Set<String> imports = new LinkedHashSet<>(REQUIRED_IMPORTS);
        List<BodyDeclaration<?>> members = new ArrayList<>();
        Set<String> takenSignatures = new HashSet<>();
        Set<String> fieldNames = new HashSet<>();
        boolean setupKept = false;
        MethodDeclaration keptSetup = null;
        // Statement text already in the kept setup, so the identical baseURI
        // line every batch emits is added once and fixture creation is not.
        Set<String> setupStatements = new LinkedHashSet<>();

        for (TestCaseResult script : scripts) {
            ParseResult<CompilationUnit> parsed = parser().parse(script.testCode());
            if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
                // A script that doesn't parse also wouldn't compile - skip it
                // rather than corrupting the merged file, and say so loudly.
                log.warn("Skipping '{}' during merge - it doesn't parse: {}",
                        script.testFileName(), parsed.getProblems());
                continue;
            }
            CompilationUnit cu = parsed.getResult().get();

            repairOversizedFloatLiterals(cu, script.testFileName());
            dropErrorBodyAssertions(cu, script.testFileName());

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
                            // Several @BeforeClass methods would run in
                            // unspecified order, so only one survives - but its
                            // BODY has to absorb the others. Batch 1's setup
                            // usually just sets baseURI, while later batches
                            // create the merchant/account/fee their tests then
                            // reference through a field. Dropping those outright
                            // left every such field null, and each test died
                            // with "path parameter at index 0 is null" long
                            // before it sent a request.
                            if (setupKept) {
                                absorbSetupBody(keptSetup, method, setupStatements);
                                continue;
                            }
                            setupKept = true;
                            keptSetup = method;
                            recordStatements(method, setupStatements);
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

        ensureBaseUriIsHonoured(members, keptSetup);

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
    /**
     * The statement that makes a run's {@code -DbaseUri} reach REST Assured.
     * The fallback matches REST Assured's own default, so a script run without
     * the property behaves exactly as before.
     */
    private static final String BASE_URI_PROPERTY = "System.getProperty(\"baseUri\", \"http://localhost:8080\")";

    /**
     * Guarantees the merged class actually targets the base URI the run was
     * given. Nothing else does.
     *
     * <p>This is the failure that produced an entire report of 404s against
     * mms: not one of the generated batches emitted a {@code @BeforeClass}, so
     * {@code RestAssured.baseURI} was never assigned, REST Assured fell back to
     * its built-in {@code http://localhost:8080}, and every request went to the
     * platform's own app instead of the service under test. The runner passed
     * {@code -DbaseUri} correctly; no code read it. Thirty tests failed for a
     * reason that had nothing to do with them, and each one looked like a
     * defect in the application.
     *
     * <p>Two shapes are repaired, because the model gets this wrong in two
     * ways: no assignment at all (add one), and an assignment to a hardcoded
     * literal (rewrite it to read the property, keeping the literal as the
     * fallback). Both are checked here rather than asked for in the prompt -
     * an instruction the model follows most of the time still costs a whole
     * run when it doesn't.
     */
    private void ensureBaseUriIsHonoured(List<BodyDeclaration<?>> members, MethodDeclaration keptSetup) {
        List<AssignExpr> assignments = members.stream()
                .flatMap(m -> m.findAll(AssignExpr.class).stream())
                .filter(a -> a.getTarget().toString().endsWith("baseURI"))
                .toList();

        if (!assignments.isEmpty()) {
            for (AssignExpr assign : assignments) {
                if (assign.getValue().toString().contains("System.getProperty")) {
                    continue;
                }
                String pinned = assign.getValue().toString();
                parser().parseExpression(BASE_URI_PROPERTY.replace("\"http://localhost:8080\"", pinned))
                        .getResult()
                        .ifPresent(assign::setValue);
                log.info("Generated script pinned the base URI to {} - rewritten to read -DbaseUri, "
                        + "with that value as the fallback.", pinned);
            }
            return;
        }

        String statement = "RestAssured.baseURI = " + BASE_URI_PROPERTY + ";";

        if (keptSetup != null && keptSetup.getBody().isPresent()) {
            // First statement, not appended: anything else in the setup is
            // fixture creation, and creating a fixture against the wrong host
            // fails before the assignment could have helped.
            parser().parseStatement(statement).getResult()
                    .ifPresent(stmt -> keptSetup.getBody().get().getStatements().add(0, stmt));
            log.info("Merged script's setup did not set the base URI - added it, so -DbaseUri is honoured.");
            return;
        }

        parser().parseBodyDeclaration("""
                @BeforeClass
                public void aiqaConfigureBaseUri() {
                    %s
                }
                """.formatted(statement))
                .getResult()
                .ifPresent(setup -> {
                    members.add(0, setup);
                    log.warn("Merged script had no @BeforeClass at all, so REST Assured would have used its "
                            + "default http://localhost:8080 and ignored the run's target. Added a setup that "
                            + "reads -DbaseUri.");
                });
    }

    /**
     * Applies the base-URI guarantee to a script that is about to RUN, not one
     * being merged.
     *
     * <p>Scripts generated before that guarantee existed are sitting on disk
     * with no {@code @BeforeClass} at all, and re-generating them costs a full
     * round of LLM calls. Repairing at execution time means an existing suite
     * can be pointed at a real target today; a script that already reads the
     * property is returned untouched.
     *
     * @return the script with the repair applied, or the input unchanged when
     *         nothing needed doing or it could not be parsed
     */
    public TestCaseResult withBaseUriHonoured(TestCaseResult script) {
        ParseResult<CompilationUnit> parsed = parser().parse(script.testCode());
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
            log.warn("Could not parse {} to check its base URI - running it as written.", script.testFileName());
            return script;
        }
        CompilationUnit cu = parsed.getResult().get();
        String before = cu.toString();

        for (TypeDeclaration<?> type : cu.getTypes()) {
            if (!(type instanceof ClassOrInterfaceDeclaration decl)) {
                continue;
            }
            MethodDeclaration setup = decl.getMethods().stream()
                    .filter(m -> m.getAnnotations().stream()
                            .anyMatch(a -> SETUP_ANNOTATIONS.contains(a.getNameAsString())))
                    .findFirst()
                    .orElse(null);

            // The repair edits existing nodes in place - those are already part
            // of the tree - so only an ADDED setup has to be put back.
            List<BodyDeclaration<?>> members = new ArrayList<>(decl.getMembers());
            int sizeBefore = members.size();
            ensureBaseUriIsHonoured(members, setup);
            if (members.size() > sizeBefore) {
                decl.getMembers().add(0, members.get(0));
            }
        }

        if (cu.toString().equals(before)) {
            return script;
        }
        // A setup added here uses both of these; a script that never had one
        // may well not import them.
        cu.addImport("io.restassured.RestAssured");
        cu.addImport("org.testng.annotations.BeforeClass");
        log.info("Repaired {} so it targets the base URI this run was given.", script.testFileName());
        return new TestCaseResult(script.targetClassName(), script.testFileName(), cu.toString(), script.writtenPath());
    }

    /**
     * Removes body assertions from response chains that expect a 4xx or 5xx.
     *
     * <p>An error envelope is the service's own invention and the generator has
     * never been shown one. mms answers a rejected request with
     * {@code {"status":400,"error":"Validation Failed","message":"Input
     * validation failed","path":...}} while its SUCCESS envelope is
     * {@code {"success":true,"data":{...}}} - so a generated
     * {@code .body("success", is(false))} failed against a service that had just
     * behaved perfectly, four times in one run. The status code had already
     * proved the rule; the body check only added a way to be wrong.
     *
     * <p>Deliberately narrow, on three counts. Only chains asserting an error
     * status are touched - a 2xx body assertion is usually the real point of
     * the test (proving a value was stored, or truncated). Only calls inside a
     * {@code .then()} chain qualify, so the request's own
     * {@code .body(payload)} is never confused for an assertion. And nothing
     * else in the chain is altered, so the status assertion the case depends on
     * survives untouched.
     */
    private void dropErrorBodyAssertions(CompilationUnit cu, String fileName) {
        int removed = 0;
        for (MethodCallExpr call : new ArrayList<>(cu.findAll(MethodCallExpr.class))) {
            if (!"body".equals(call.getNameAsString()) || call.getScope().isEmpty()) {
                continue;
            }
            if (!chainContains(call.getScope().get(), "then") || !expectsErrorStatus(call)) {
                continue;
            }
            call.replace(call.getScope().get());
            removed++;
        }
        if (removed > 0) {
            log.info("Removed {} body assertion(s) from error-status chains in '{}' - the error envelope is the "
                    + "service's own shape, and the status assertion already proves the rule.", removed, fileName);
        }
    }

    /** True when this call sits on a chain that asserts a 4xx/5xx status - checked over the WHOLE chain, since statusCode may come before or after. */
    private boolean expectsErrorStatus(MethodCallExpr bodyCall) {
        Node root = bodyCall;
        while (root.getParentNode().isPresent() && root.getParentNode().get() instanceof MethodCallExpr parent) {
            root = parent;
        }
        return root.findAll(MethodCallExpr.class).stream()
                .filter(c -> "statusCode".equals(c.getNameAsString()))
                .flatMap(c -> c.findAll(IntegerLiteralExpr.class).stream())
                .anyMatch(literal -> {
                    try {
                        return Integer.parseInt(literal.getValue()) >= 400;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                });
    }

    /** Walks a call's scope chain looking for one named {@code name} - e.g. the then() that marks a response assertion. */
    private boolean chainContains(Expression scope, String name) {
        Expression current = scope;
        while (current instanceof MethodCallExpr call) {
            if (name.equals(call.getNameAsString())) {
                return true;
            }
            current = call.getScope().orElse(null);
        }
        return false;
    }

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

    /** Remembers what the kept setup already does, so duplicates aren't re-added. */
    private void recordStatements(MethodDeclaration setup, Set<String> seen) {
        setup.getBody().ifPresent(body ->
                body.getStatements().forEach(statement -> seen.add(statement.toString().trim())));
    }

    /**
     * Appends a later {@code @BeforeClass}'s statements to the one being kept,
     * skipping any it already performs.
     *
     * <p>Each batch re-emits {@code RestAssured.baseURI = ..}, which must run
     * once, while the fixture creation around it differs per batch and must all
     * run - so dedupe by statement text rather than dropping whole methods.
     * Order is preserved: batch order is the only sensible sequence, and a later
     * batch's fixture may well depend on an earlier one's.
     */
    private void absorbSetupBody(MethodDeclaration keptSetup, MethodDeclaration extraSetup, Set<String> seen) {
        if (keptSetup == null || keptSetup.getBody().isEmpty() || extraSetup.getBody().isEmpty()) {
            return;
        }
        BlockStmt target = keptSetup.getBody().get();
        int added = 0;
        for (Statement statement : extraSetup.getBody().get().getStatements()) {
            if (seen.add(statement.toString().trim())) {
                target.addStatement(statement.clone());
                added++;
            }
        }
        if (added > 0) {
            log.info("Merged {} statement(s) from '{}' into the kept @BeforeClass - they initialise fields "
                            + "the tests in that batch depend on.",
                    added, extraSetup.getNameAsString());
        }
    }

    /**
     * Drops the {@code f}/{@code F} suffix from any floating-point literal too
     * large to be a float, turning it into a valid double literal.
     *
     * <p>The model reaches for a typed literal when asserting a maximum, and
     * {@code equalTo(1.7976931348623157E308f)} is the result: that value is
     * Double.MAX_VALUE, some 10^270 times beyond Float.MAX_VALUE, so javac
     * rejects it with "floating-point number too large". One such token fails
     * the compile and every test in the file is lost - a 34-test run reported
     * as 0 tests - which is wildly out of proportion to a stray suffix.
     *
     * <p>Removing the suffix preserves the number exactly and is what the author
     * plainly meant. Literals that genuinely fit in a float are left alone.
     * JavaParser accepts the oversized literal happily - it does no range
     * checking - so this runs on the parsed AST before the file is written.
     *
     * <p>The prompt separately tells the model not to use typed numeric literals
     * at all, since Hamcrest's equalTo is type-strict; this is the safety net
     * for when it does anyway.
     */
    private void repairOversizedFloatLiterals(CompilationUnit cu, String fileName) {
        cu.findAll(DoubleLiteralExpr.class).forEach(literal -> {
            String text = literal.getValue();
            if (!text.endsWith("f") && !text.endsWith("F")) {
                return;
            }
            String withoutSuffix = text.substring(0, text.length() - 1);
            try {
                double value = Double.parseDouble(withoutSuffix.replace("_", ""));
                if (Double.isInfinite(value) || Math.abs(value) <= Float.MAX_VALUE) {
                    return;
                }
                literal.setValue(withoutSuffix);
                log.info("Repaired oversized float literal {} -> {} in '{}' - it would have failed the "
                                + "compile and taken every test in the file with it.",
                        text, withoutSuffix, fileName);
            } catch (NumberFormatException e) {
                // Not a number we can reason about - leave it exactly as written
                // rather than risk changing what the assertion means.
                log.debug("Left literal '{}' in '{}' untouched: {}", text, fileName, e.getMessage());
            }
        });
    }

    /** Re-indents a member by one level so the merged class reads normally. */
    private String indent(String code) {
        return code.lines().map(line -> line.isBlank() ? line : "    " + line)
                .reduce((a, b) -> a + "\n" + b).orElse(code);
    }
}
