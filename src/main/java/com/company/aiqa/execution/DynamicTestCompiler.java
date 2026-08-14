package com.company.aiqa.execution;

import com.company.aiqa.model.TestCaseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles generated REST Assured scripts with the JDK's own in-memory
 * compiler, so the platform can tell you whether what it produced actually
 * builds - rather than handing you source and hoping.
 *
 * <p>Compilation is safe to do in-process: nothing in the generated code runs
 * during it. Executing the result is a separate, deliberately isolated step -
 * see {@link RestAssuredTestExecutionService}.
 *
 * <p>Requires the platform to run on a JDK, not a JRE
 * ({@code ToolProvider.getSystemJavaCompiler()} returns null otherwise).
 */
@Service
public class DynamicTestCompiler {

    private static final Logger log = LoggerFactory.getLogger(DynamicTestCompiler.class);

    /** Fixed package every generated script declares - see PromptBuilder's contract. */
    public static final String GENERATED_PACKAGE = "com.company.aiqa.generated";

    public record CompilationResult(
            /** Null when nothing compiled. */
            Path classesDir,
            /** Fully-qualified names whose .class file actually exists on disk. */
            List<String> compiledClassNames,
            /** Human-readable compile errors, one per diagnostic. */
            List<String> errors
    ) {
    }

    public CompilationResult compile(List<TestCaseResult> scripts) {
        List<String> errors = new ArrayList<>();
        if (scripts.isEmpty()) {
            return new CompilationResult(null, List.of(), errors);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            errors.add("No system Java compiler available - the platform must run on a JDK (not a JRE) "
                    + "to compile generated automation.");
            return new CompilationResult(null, List.of(), errors);
        }

        Path workDir;
        Path packageDir;
        Path classesDir;
        try {
            workDir = Files.createTempDirectory("aiqa-automation-");
            packageDir = workDir.resolve("src").resolve(GENERATED_PACKAGE.replace('.', '/'));
            classesDir = workDir.resolve("classes");
            Files.createDirectories(packageDir);
            Files.createDirectories(classesDir);
        } catch (IOException e) {
            errors.add("Could not create a temp workspace for compilation: " + e.getMessage());
            return new CompilationResult(null, List.of(), errors);
        }

        Map<Path, String> classNameBySource = new LinkedHashMap<>();
        for (TestCaseResult script : scripts) {
            String className = stripExtension(script.testFileName());
            if (className == null || className.isBlank()) {
                errors.add(script.testFileName() + ": could not determine a class name");
                continue;
            }
            try {
                Path source = packageDir.resolve(className + ".java");
                Files.writeString(source, script.testCode(), StandardCharsets.UTF_8);
                classNameBySource.put(source, className);
            } catch (IOException e) {
                errors.add(script.testFileName() + ": could not write source: " + e.getMessage());
            }
        }

        if (classNameBySource.isEmpty()) {
            return new CompilationResult(null, List.of(), errors);
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classesDir.toFile()));

            // One pass over the whole batch. Scripts are self-contained by
            // contract, so one file's error doesn't stop the others producing
            // a usable .class - which is why success is judged from the
            // filesystem below rather than from task.call()'s single boolean.
            compiler.getTask(new StringWriter(), fileManager, diagnostics,
                    List.of("-cp", runtimeClasspath()), null,
                    fileManager.getJavaFileObjectsFromPaths(classNameBySource.keySet())
            ).call();
        } catch (IOException e) {
            errors.add("Compilation failed: " + e.getMessage());
            return new CompilationResult(null, List.of(), errors);
        }

        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                String source = d.getSource() != null ? Path.of(d.getSource().getName()).getFileName().toString() : "?";
                errors.add("%s:%d: %s".formatted(source, d.getLineNumber(), d.getMessage(null)));
            }
        }

        List<String> compiled = new ArrayList<>();
        for (String className : classNameBySource.values()) {
            Path classFile = classesDir.resolve(GENERATED_PACKAGE.replace('.', '/')).resolve(className + ".class");
            if (Files.exists(classFile)) {
                compiled.add(GENERATED_PACKAGE + "." + className);
            }
        }

        log.info("Compiled {}/{} generated script(s); {} error(s).",
                compiled.size(), classNameBySource.size(), errors.size());
        return new CompilationResult(compiled.isEmpty() ? null : classesDir, compiled, errors);
    }

    private String stripExtension(String fileName) {
        if (fileName == null) return null;
        return fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - 5) : fileName;
    }

    /** This JVM's classpath - carries rest-assured/testng for the generated code to compile against. */
    String runtimeClasspath() {
        return System.getProperty("java.class.path");
    }
}
