package com.company.aiqa.testcase;

import com.company.aiqa.parser.JavaSources;
import com.company.aiqa.execution.DynamicTestCompiler;
import com.company.aiqa.model.TestCaseResult;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes a generated script compile by removing the individual test methods that
 * don't, rather than letting one bad line destroy the whole file.
 *
 * <p>Java compiles a file all-or-nothing, so a single unusable token - an
 * oversized float literal, an invented Hamcrest matcher, a missing import, a
 * stray syntax error - fails the compile and takes every test with it. A run
 * that should have reported "33 passed, 1 broken" instead reports zero tests,
 * which reads like the pipeline never ran and hides whatever the other thirty
 * three would have told you. That is a wildly disproportionate outcome, and it
 * is avoidable: the compiler names the LINE it objected to, and the line sits
 * inside one method.
 *
 * <p>So: compile; if it fails, map each error line to its enclosing method, drop
 * those methods, and compile again. Repeat until it compiles or there is nothing
 * left to drop. The dropped methods are returned so the caller can report them
 * honestly instead of quietly shipping a smaller suite.
 *
 * <p>Deliberately generic - it knows nothing about what the error was, only
 * which line it was on. Whatever new way the model finds to write uncompilable
 * Java, this contains the blast radius to one test.
 */
@Service
public class CompileSalvager {

    private static final Logger log = LoggerFactory.getLogger(CompileSalvager.class);

    /** "SomeTest.java:370: error text" - the shape DynamicTestCompiler produces. */
    private static final Pattern ERROR_LINE = Pattern.compile("^[^:]*:(\\d+):");

    /**
     * Bounded so a file whose errors keep moving - each removal revealing
     * another - cannot loop forever. In practice one or two passes is enough;
     * anything worse is a file not worth salvaging.
     */
    private static final int MAX_PASSES = 5;

    private final DynamicTestCompiler compiler;
    /** Per thread, not a shared field: a JavaParser instance is not thread-safe. */
    private JavaParser parser() {
        return JavaSources.parser(ParserConfiguration.LanguageLevel.JAVA_17);
    }

    public CompileSalvager(DynamicTestCompiler compiler) {
        this.compiler = compiler;
    }

    /** The salvaged script plus the names of any test methods removed to get there. */
    public record Salvaged(TestCaseResult script, List<String> removedMethods, List<String> unresolvedErrors) {
        public boolean isClean() {
            return removedMethods.isEmpty() && unresolvedErrors.isEmpty();
        }
    }

    /**
     * Returns a script that compiles, dropping the fewest test methods needed.
     *
     * <p>When the failure is NOT inside a test method - a bad import, a broken
     * class declaration - there is nothing to drop, so the original script is
     * returned unchanged along with the errors. Better to hand back the file the
     * model wrote, with the reason it fails, than to mangle it.
     */
    public Salvaged salvage(TestCaseResult script) {
        List<String> removed = new ArrayList<>();
        TestCaseResult current = script;

        for (int pass = 1; pass <= MAX_PASSES; pass++) {
            DynamicTestCompiler.CompilationResult result = compiler.compile(List.of(current));
            if (!result.compiledClassNames().isEmpty()) {
                if (!removed.isEmpty()) {
                    log.info("Salvaged '{}' by removing {} uncompilable test method(s): {}. "
                                    + "The remaining tests run normally.",
                            script.testFileName(), removed.size(), removed);
                }
                return new Salvaged(current, List.copyOf(removed), List.of());
            }

            Set<Integer> badLines = errorLines(result.errors());
            if (badLines.isEmpty()) {
                return giveUp(script, current, removed, result.errors(),
                        "compiler reported no line numbers");
            }

            Optional<CompilationUnit> parsed = parse(current.testCode());
            if (parsed.isEmpty()) {
                return giveUp(script, current, removed, result.errors(), "the file no longer parses");
            }

            CompilationUnit cu = parsed.get();
            List<MethodDeclaration> doomed = methodsCovering(cu, badLines);
            if (doomed.isEmpty()) {
                // Errors sit outside any method - imports, fields, the class
                // declaration itself. Nothing to remove without guessing.
                return giveUp(script, current, removed, result.errors(),
                        "the errors are outside any test method");
            }

            doomed.forEach(m -> {
                removed.add(m.getNameAsString());
                log.warn("Removing test method '{}' from '{}' - it does not compile. "
                                + "Keeping it would cost every other test in the file.",
                        m.getNameAsString(), script.testFileName());
                m.remove();
            });

            current = new TestCaseResult(current.targetClassName(), current.testFileName(),
                    cu.toString(), current.writtenPath());
        }

        return giveUp(script, current, removed, List.of(),
                "still failing after " + MAX_PASSES + " passes");
    }

    private Salvaged giveUp(TestCaseResult original, TestCaseResult current, List<String> removed,
                            List<String> errors, String why) {
        log.warn("Could not salvage '{}' - {}. Returning it as generated so the errors stay visible.",
                original.testFileName(), why);
        // Hand back whatever we have: if some methods were already removed the
        // partial file is still closer to compiling than the original.
        return new Salvaged(removed.isEmpty() ? original : current, List.copyOf(removed), List.copyOf(errors));
    }

    private Set<Integer> errorLines(List<String> errors) {
        Set<Integer> lines = new LinkedHashSet<>();
        for (String error : errors) {
            Matcher m = ERROR_LINE.matcher(error.trim());
            if (m.find()) {
                try {
                    lines.add(Integer.parseInt(m.group(1)));
                } catch (NumberFormatException ignored) {
                    // A line number we can't read is one we can't act on.
                }
            }
        }
        return lines;
    }

    /**
     * Every method whose source range covers one of the failing lines. Uses the
     * outermost enclosing method, so an error inside a lambda or an anonymous
     * class still removes the test method that contains it.
     */
    private List<MethodDeclaration> methodsCovering(CompilationUnit cu, Set<Integer> badLines) {
        List<MethodDeclaration> hits = new ArrayList<>();
        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            method.getRange()
                    .filter(range -> badLines.stream()
                            .anyMatch(line -> line >= range.begin.line && line <= range.end.line))
                    .ifPresent(range -> hits.add(method));
        }
        return hits;
    }

    /**
     * Annotations whose method is guaranteed to run before the tests, so an id
     * it assigns is genuinely available to every test in the class. An
     * assignment anywhere else - another {@code @Test}, a helper nobody calls -
     * carries no such guarantee.
     */
    private static final Set<String> SETUP_ANNOTATIONS = Set.of(
            "BeforeClass", "BeforeMethod", "BeforeSuite", "BeforeTest", "BeforeGroups",
            "BeforeAll", "BeforeEach", "Before");

    /** Test annotations. A method carrying one is a row in the report. */
    private static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest");

    /**
     * Names of instance fields that tests READ but nothing ever ASSIGNS.
     *
     * <p>The model sometimes shares an id across tests via a field - declaring
     * {@code private String merchantId} and using it as a path parameter -
     * expecting some other test to have filled it in. TestNG guarantees no
     * ordering, and often nothing assigns it at all, so every test using it
     * dies with "Unnamed path parameter cannot be null" before sending a
     * request: an ERROR with no request or response to diagnose from.
     *
     * <p>This cannot be repaired automatically - the fixture creation simply
     * isn't there to move - but it is worth naming loudly, because otherwise a
     * run reports dozens of identical errors that look like the target service
     * is down when the script never called it.
     */
    public List<String> fieldsReadButNeverAssigned(TestCaseResult script) {
        Optional<CompilationUnit> parsed = parse(script.testCode());
        if (parsed.isEmpty()) {
            return List.of();
        }
        CompilationUnit cu = parsed.get();
        Set<String> declared = uninitialisedFields(cu);
        if (declared.isEmpty()) {
            return List.of();
        }

        Set<String> assigned = new LinkedHashSet<>();
        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            assigned.addAll(fieldsAssignedDirectly(method, declared));
        }

        List<String> orphans = new ArrayList<>(declared);
        orphans.removeAll(assigned);
        if (!orphans.isEmpty()) {
            log.warn("In '{}': field(s) {} are used by tests but never assigned anywhere. "
                            + "Every test reading them will error before it sends a request.",
                    script.testFileName(), orphans);
        }
        return orphans;
    }

    /**
     * Removes the tests that read a shared id field no setup ever fills in.
     *
     * <p>Two shapes, both taken from real mms runs, both producing the same
     * useless red row - {@code IllegalArgumentException: path parameter at
     * index 0 is null}, with no request and no response to diagnose from:
     *
     * <ul>
     *   <li><b>Nothing assigns the field at all.</b> merchantId and capabilityId
     *       were declared, read by most of the suite, and written nowhere, so
     *       tests either errored on a null path parameter or - worse - posted
     *       the literal text {@code "merchantId": "null"} and read the target's
     *       500 as a defect.
     *   <li><b>Something assigns it, but nothing that runs first.</b>
     *       feeConfigId was assigned by a {@code private} helper nobody called
     *       and by two unrelated {@code @Test} methods. TestNG guarantees no
     *       ordering, so the five fee-tier tests reading it still saw null.
     *       Field-level "is it assigned anywhere" cannot tell these apart; the
     *       question is whether the assignment is reachable BEFORE the read.
     * </ul>
     *
     * <p>So a read is sound only when a {@code @Before*} hook assigns the field,
     * or the reading method assigns it itself - directly or through a helper it
     * calls. Everything else is dropped, to a fixpoint, because dropping the
     * test that assigned a field can strand the next one.
     *
     * <p>Only {@code @Test} methods are dropped for an unreachable assignment.
     * A {@code @AfterClass} cleanup reads these same fields, but guards with a
     * null check and never asserts, so it costs nothing to keep. Where the field
     * goes away entirely, its readers go with it or the file stops compiling.
     *
     * <p>Dropping is not hiding: every removed method is named in the response
     * summary, so the coverage gap is visible and traceable to the case that
     * produced it. {@link #salvage} runs afterwards and clears any fallout -
     * a helper removed here may leave a caller that no longer compiles.
     */
    public Salvaged dropMethodsUsingUnassignedFields(TestCaseResult script) {
        Optional<CompilationUnit> parsed = parse(script.testCode());
        if (parsed.isEmpty()) {
            return new Salvaged(script, List.of(), List.of());
        }
        CompilationUnit cu = parsed.get();

        List<String> removed = new ArrayList<>();
        // Removing a test removes its assignments too, which can strand the
        // next one. Repeat until a pass finds nothing left to remove.
        for (int pass = 1; pass <= MAX_PASSES; pass++) {
            if (!pruneOnce(cu, removed)) {
                break;
            }
        }

        if (removed.isEmpty()) {
            return new Salvaged(script, List.of(), List.of());
        }
        log.warn("Removed {} method(s) from '{}' that read an id field no setup assigns: they could not have "
                + "sent a request under any conditions.", removed.size(), script.testFileName());
        return new Salvaged(
                new TestCaseResult(script.targetClassName(), script.testFileName(), cu.toString(), script.writtenPath()),
                removed, List.of());
    }

    /** One prune pass. Returns whether it changed anything. */
    private boolean pruneOnce(CompilationUnit cu, List<String> removed) {
        Set<String> declared = uninitialisedFields(cu);
        if (declared.isEmpty()) {
            return false;
        }
        List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);
        Map<String, Set<String>> assigns = assignmentsPerMethod(methods, declared);

        // A field no method assigns cannot be repaired: it and everything that
        // reads it have to go, cleanup included, or the file stops compiling.
        Set<String> unassigned = new LinkedHashSet<>(declared);
        assigns.values().forEach(unassigned::removeAll);

        // Only a @Before* hook can be trusted to have filled a field in.
        Set<String> setupAssigned = new LinkedHashSet<>();
        methods.stream()
                .filter(m -> hasAnnotation(m, SETUP_ANNOTATIONS))
                .forEach(m -> setupAssigned.addAll(assigns.getOrDefault(key(m), Set.of())));

        List<MethodDeclaration> doomed = new ArrayList<>();
        for (MethodDeclaration method : methods) {
            Set<String> reads = fieldsRead(method, declared);
            if (reads.isEmpty()) {
                continue;
            }
            if (reads.stream().anyMatch(unassigned::contains)) {
                doomed.add(method);
                continue;
            }
            // Beyond here the field IS assigned somewhere, so only a @Test is
            // worth dropping - see the doc comment on cleanup methods.
            if (!hasAnnotation(method, TEST_ANNOTATIONS)) {
                continue;
            }
            Set<String> ownAssigns = assigns.getOrDefault(key(method), Set.of());
            if (reads.stream().anyMatch(f -> !setupAssigned.contains(f) && !ownAssigns.contains(f))) {
                doomed.add(method);
            }
        }

        if (doomed.isEmpty() && unassigned.isEmpty()) {
            return false;
        }
        doomed.forEach(m -> {
            removed.add(m.getNameAsString());
            m.remove();
        });
        // Only fields nothing assigns are removed. One a surviving setup fills
        // in is still doing its job.
        for (FieldDeclaration field : new ArrayList<>(cu.findAll(FieldDeclaration.class))) {
            field.getVariables().removeIf(v -> unassigned.contains(v.getNameAsString()));
            if (field.getVariables().isEmpty()) {
                field.remove();
            }
        }
        return true;
    }

    /** Instance fields declared without an initialiser - the id-sharing shape. */
    private Set<String> uninitialisedFields(CompilationUnit cu) {
        Set<String> declared = new LinkedHashSet<>();
        cu.findAll(FieldDeclaration.class).forEach(field ->
                field.getVariables().forEach(v -> {
                    if (v.getInitializer().isEmpty()) {
                        declared.add(v.getNameAsString());
                    }
                }));
        return declared;
    }

    /**
     * Which of {@code declared} each method assigns, following calls to other
     * methods of the same class so a {@code @BeforeClass} that delegates to a
     * {@code createMerchant()} helper still counts as assigning the id.
     *
     * <p>Overloads share a name, so a call resolves to the union of what every
     * method with that name assigns. Imprecise in the safe direction: it can
     * only make a field look MORE assigned, never less, so it costs a kept test
     * rather than a deleted one.
     */
    private Map<String, Set<String>> assignmentsPerMethod(List<MethodDeclaration> methods, Set<String> declared) {
        Map<String, Set<String>> closed = new LinkedHashMap<>();
        Map<String, List<String>> callees = new LinkedHashMap<>();
        Set<String> known = new LinkedHashSet<>();
        methods.forEach(m -> known.add(m.getNameAsString()));

        for (MethodDeclaration method : methods) {
            closed.put(key(method), new LinkedHashSet<>(fieldsAssignedDirectly(method, declared)));
            callees.put(key(method), method.findAll(MethodCallExpr.class).stream()
                    .filter(call -> call.getScope().isEmpty() || call.getScope().get() instanceof ThisExpr)
                    .map(MethodCallExpr::getNameAsString)
                    .filter(known::contains)
                    .toList());
        }

        Map<String, Set<String>> byName = new HashMap<>();
        methods.forEach(m -> byName.put(m.getNameAsString(), new LinkedHashSet<>()));
        // Bounded: each pass either grows a set or stops, and a chain of calls
        // can be at most as long as the class has methods.
        for (int pass = 0; pass <= methods.size(); pass++) {
            byName.values().forEach(Set::clear);
            methods.forEach(m -> byName.get(m.getNameAsString()).addAll(closed.get(key(m))));
            boolean grew = false;
            for (MethodDeclaration method : methods) {
                Set<String> mine = closed.get(key(method));
                for (String callee : callees.get(key(method))) {
                    grew |= mine.addAll(byName.getOrDefault(callee, Set.of()));
                }
            }
            if (!grew) {
                break;
            }
        }
        return closed;
    }

    /**
     * Fields this method assigns in its own body. Shadow-aware: a method that
     * declares its own {@code String merchantId} is writing the local, not the
     * field, so the field is no more assigned than before. {@code this.x = ..}
     * always names the field.
     */
    private Set<String> fieldsAssignedDirectly(MethodDeclaration method, Set<String> declared) {
        Set<String> assigned = new LinkedHashSet<>();
        for (AssignExpr assign : method.findAll(AssignExpr.class)) {
            String name = fieldName(method, assign.getTarget(), declared);
            if (name != null) {
                assigned.add(name);
            }
        }
        return assigned;
    }

    /**
     * Fields this method reads, ignoring any it shadows with a local of the
     * same name.
     *
     * <p>This is what keeps the prune honest. Twelve mms tests built their own
     * merchant into a local {@code String merchantId} and were self-contained
     * and correct; matching the bare name deleted every one of them alongside
     * the tests that really did read the null field.
     */
    private Set<String> fieldsRead(MethodDeclaration method, Set<String> declared) {
        Set<String> read = new LinkedHashSet<>();
        for (NameExpr name : method.findAll(NameExpr.class)) {
            String field = fieldName(method, name, declared);
            if (field != null) {
                read.add(field);
            }
        }
        for (FieldAccessExpr access : method.findAll(FieldAccessExpr.class)) {
            if (access.getScope() instanceof ThisExpr && declared.contains(access.getNameAsString())) {
                read.add(access.getNameAsString());
            }
        }
        return read;
    }

    /**
     * The field this expression names, or null when it names something else - a
     * local, a parameter, an unrelated identifier.
     */
    private String fieldName(MethodDeclaration method, Expression expr, Set<String> declared) {
        if (expr instanceof FieldAccessExpr access) {
            return access.getScope() instanceof ThisExpr && declared.contains(access.getNameAsString())
                    ? access.getNameAsString() : null;
        }
        if (!(expr instanceof NameExpr name)) {
            return null;
        }
        String id = name.getNameAsString();
        return declared.contains(id) && !shadows(method, id) ? id : null;
    }

    /**
     * Whether the method declares a local or parameter of this name anywhere in
     * its body. Deliberately ignores block scope: a method holding both a local
     * merchantId and a genuine read of the field is not a shape worth guessing
     * at, and treating it as local errs towards keeping the test.
     */
    private boolean shadows(MethodDeclaration method, String name) {
        return method.findAll(VariableDeclarator.class).stream()
                       .anyMatch(v -> v.getNameAsString().equals(name))
               || method.findAll(Parameter.class).stream()
                       .anyMatch(p -> p.getNameAsString().equals(name));
    }

    private boolean hasAnnotation(MethodDeclaration method, Set<String> names) {
        return method.getAnnotations().stream().anyMatch(a -> names.contains(a.getName().getIdentifier()));
    }

    /** Identity for the maps above - overloads must not collapse into one entry. */
    private String key(MethodDeclaration method) {
        return method.getSignature().asString();
    }

    private Optional<CompilationUnit> parse(String source) {
        ParseResult<CompilationUnit> parsed = parser().parse(source);
        return parsed.isSuccessful() ? parsed.getResult() : Optional.empty();
    }
}
