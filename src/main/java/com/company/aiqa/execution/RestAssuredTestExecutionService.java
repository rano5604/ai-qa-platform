package com.company.aiqa.execution;

import com.company.aiqa.config.PipelineProperties;
import com.company.aiqa.execution.support.HttpCaptureListener;
import com.company.aiqa.model.HttpExchange;
import com.company.aiqa.model.TestCaseResult;
import com.company.aiqa.model.TestExecutionResult;
import com.company.aiqa.model.TestExecutionSummary;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Compiles and then RUNS generated automation against a live target, using
 * TestNG as both the runner and the reporter.
 *
 * <p>Execution happens in a separate {@code java org.testng.TestNG} SUBPROCESS,
 * never in-process. The platform is about to run LLM-generated code that makes
 * real network calls; in-process, a crash, hang, resource leak or stray
 * {@code System.exit()} in that code would take the whole server down with it.
 * A child process caps the blast radius at a killed or timed-out process.
 *
 * <p>This is process containment, NOT a security sandbox - the subprocess runs
 * with the same OS permissions as the platform. It protects against the child
 * misbehaving, not against a hostile payload.
 *
 * <p>Two artefacts come back from the child: TestNG's own
 * {@code testng-results.xml} (per-method verdicts, timings, exceptions) and a
 * JSON file of HTTP exchanges written by {@link HttpCaptureListener}. They are
 * joined on test class + method so each reported result carries the traffic
 * that produced it.
 */
@Service
public class RestAssuredTestExecutionService {

    private static final Logger log = LoggerFactory.getLogger(RestAssuredTestExecutionService.class);

    /** TestNG's fixed report file name inside the -d output directory. */
    private static final String RESULTS_XML = "testng-results.xml";

    /** Written by the capture listener, kept alongside TestNG's own reports. */
    private static final String CAPTURE_FILE = "http-exchanges.json";

    /** Assertion failures mean "the API answered wrongly"; anything else means the test never got to judge. */
    private static final List<String> ASSERTION_TYPES = List.of("assertionerror", "comparisonfailure", "assertionfailederror");

    private final DynamicTestCompiler compiler;
    private final PipelineProperties pipelineProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RestAssuredTestExecutionService(DynamicTestCompiler compiler, PipelineProperties pipelineProperties) {
        this.compiler = compiler;
        this.pipelineProperties = pipelineProperties;
    }

    /**
     * @param scripts   the automation to compile and run
     * @param baseUri   target the scripts point at unless they override it
     * @param reportDir where TestNG's reports and the captured traffic are left.
     *                  Unlike the compilation workspace this is NOT deleted
     *                  afterwards - the HTML report is a deliverable, not a temp file.
     */
    public TestExecutionSummary execute(List<TestCaseResult> scripts, String baseUri, Path reportDir) {
        DynamicTestCompiler.CompilationResult compilation = compiler.compile(scripts);
        if (compilation.classesDir() == null) {
            return new TestExecutionSummary(baseUri, scripts.size(), 0, compilation.errors(),
                    0, 0, 0, 0, 0, null, List.of());
        }

        List<String> runErrors = new ArrayList<>(compilation.errors());
        try {
            Files.createDirectories(reportDir);
        } catch (IOException e) {
            runErrors.add("Could not create the report directory " + reportDir + ": " + e.getMessage());
            deleteRecursively(compilation.classesDir().getParent());
            return new TestExecutionSummary(baseUri, scripts.size(), compilation.compiledClassNames().size(),
                    runErrors, 0, 0, 0, 0, 0, null, List.of());
        }

        // Clear the previous run's artefacts BEFORE launching. Without this, a
        // subprocess that dies on startup leaves the old testng-results.xml in
        // place and the parse below reports the last run's verdicts as if they
        // were this one's - the most misleading failure mode this endpoint has.
        Path captureFile = reportDir.resolve(CAPTURE_FILE);
        deleteIfPresent(reportDir.resolve(RESULTS_XML));
        deleteIfPresent(captureFile);

        runSubprocess(buildCommand(compilation, baseUri, reportDir, captureFile), runErrors);

        List<TestExecutionResult> results = parseResults(reportDir, readCapturedExchanges(captureFile));

        // Only the compilation workspace is temporary. The reports stay.
        deleteRecursively(compilation.classesDir().getParent());

        if (results.isEmpty() && runErrors.isEmpty()) {
            runErrors.add("The run produced no " + RESULTS_XML + " in " + reportDir
                    + " - the subprocess most likely failed to start. See the platform log for its output.");
        }

        return new TestExecutionSummary(baseUri, scripts.size(), compilation.compiledClassNames().size(), runErrors,
                results.size(),
                count(results, TestExecutionResult.Status.PASSED),
                count(results, TestExecutionResult.Status.FAILED),
                count(results, TestExecutionResult.Status.ERROR),
                count(results, TestExecutionResult.Status.SKIPPED),
                reportDir.toAbsolutePath().toString(),
                results);
    }

    private List<String> buildCommand(DynamicTestCompiler.CompilationResult compilation, String baseUri,
                                      Path reportDir, Path captureFile) {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path") + File.pathSeparator + compilation.classesDir());

        // The scripts read baseUri via System.getProperty, so the same compiled
        // classes can be pointed at a different environment without regenerating.
        cmd.add("-DbaseUri=" + baseUri);
        cmd.add("-D" + HttpCaptureListener.CAPTURE_FILE_PROPERTY + "=" + captureFile);
        cmd.add("-D" + HttpCaptureListener.MAX_BODY_CHARS_PROPERTY + "="
                + pipelineProperties.getMaxCapturedBodyChars());
        cmd.add("-D" + HttpCaptureListener.MAX_EXCHANGES_PROPERTY + "="
                + pipelineProperties.getMaxCapturedExchanges());

        cmd.add("org.testng.TestNG");
        cmd.add("-d");
        cmd.add(reportDir.toString());
        // Default listeners stay ON: they are what writes testng-results.xml and
        // the browsable index.html this endpoint hands back.
        cmd.add("-listener");
        cmd.add(HttpCaptureListener.class.getName());
        cmd.add("-testclass");
        cmd.add(String.join(",", compilation.compiledClassNames()));
        return cmd;
    }

    private void runSubprocess(List<String> command, List<String> errorsOut) {
        long timeoutSeconds = Math.max(1, pipelineProperties.getExecutionTimeoutSeconds());
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            errorsOut.add("Failed to start the execution subprocess: " + e.getMessage());
            return;
        }

        String output = "";
        try {
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Output is diagnostic only; the XML report is the source of truth.
        }

        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                errorsOut.add("Execution timed out after " + timeoutSeconds + "s and was killed. "
                        + "Raise aiqa.pipeline.execution-timeout-seconds if the suite legitimately runs this long.");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            errorsOut.add("Interrupted while waiting for the execution subprocess.");
            return;
        }

        // A non-zero exit just means some test failed - a normal outcome, not
        // an error in itself. The parsed report carries the detail.
        log.info("TestNG subprocess exited {}.\n{}", process.exitValue(), output);
    }

    /** Reads back what the capture listener recorded, keyed by "class#method". */
    private Map<String, List<HttpExchange>> readCapturedExchanges(Path captureFile) {
        Map<String, List<HttpExchange>> byTest = new LinkedHashMap<>();
        if (!Files.isRegularFile(captureFile)) {
            return byTest;
        }
        try {
            List<HttpExchange> exchanges =
                    objectMapper.readValue(captureFile.toFile(), new TypeReference<List<HttpExchange>>() { });
            for (HttpExchange exchange : exchanges) {
                byTest.computeIfAbsent(key(exchange.testClassName(), exchange.testMethodName()),
                        k -> new ArrayList<>()).add(exchange);
            }
            log.info("Captured {} HTTP exchange(s) across {} test method(s).", exchanges.size(), byTest.size());
        } catch (Exception e) {
            // Losing the traffic degrades the report; it doesn't invalidate the verdicts.
            log.warn("Could not read captured HTTP exchanges from {}: {}", captureFile, e.getMessage());
        }
        return byTest;
    }

    private List<TestExecutionResult> parseResults(Path reportDir, Map<String, List<HttpExchange>> exchangesByTest) {
        Path resultsXml = reportDir.resolve(RESULTS_XML);
        if (!Files.isRegularFile(resultsXml)) {
            log.warn("No {} found in {}.", RESULTS_XML, reportDir);
            return List.of();
        }
        return parseTestNgResults(resultsXml, exchangesByTest);
    }

    /**
     * Parses TestNG's {@code testng-results.xml}.
     *
     * <p>Configuration methods ({@code @BeforeClass} and friends) appear here
     * too, marked {@code is-config="true"}. A passing one is scaffolding and is
     * dropped; a FAILING one is reported as an ERROR, because a setup that blew
     * up is the reason every test after it did nothing - and its captured
     * traffic (an auth call that 401'd, say) is usually the whole explanation.
     */
    private List<TestExecutionResult> parseTestNgResults(Path xmlFile, Map<String, List<HttpExchange>> exchangesByTest) {
        List<TestExecutionResult> results = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(xmlFile.toFile());

            NodeList methods = doc.getElementsByTagName("test-method");
            for (int i = 0; i < methods.getLength(); i++) {
                Element method = (Element) methods.item(i);

                boolean isConfig = "true".equalsIgnoreCase(method.getAttribute("is-config"));
                String testNgStatus = method.getAttribute("status");
                boolean failed = "FAIL".equalsIgnoreCase(testNgStatus);

                if (isConfig && !failed) {
                    continue;
                }

                String className = className(method);
                String methodName = method.getAttribute("name");

                String failureType = null;
                String failureMessage = null;
                NodeList exceptions = method.getElementsByTagName("exception");
                if (exceptions.getLength() > 0) {
                    Element exception = (Element) exceptions.item(0);
                    failureType = emptyToNull(exception.getAttribute("class"));
                    failureMessage = emptyToNull(childText(exception, "message"));
                }

                TestExecutionResult.Status status;
                if (failed) {
                    status = isConfig || !isAssertionFailure(failureType)
                            ? TestExecutionResult.Status.ERROR
                            : TestExecutionResult.Status.FAILED;
                } else if ("SKIP".equalsIgnoreCase(testNgStatus)) {
                    status = TestExecutionResult.Status.SKIPPED;
                } else {
                    status = TestExecutionResult.Status.PASSED;
                }

                if (isConfig && failureMessage != null) {
                    failureMessage = TestExecutionResult.setupFailureMessage(methodName, failureMessage);
                }

                results.add(new TestExecutionResult(
                        className,
                        methodName,
                        status,
                        parseLong(method.getAttribute("duration-ms")),
                        failureMessage,
                        failureType,
                        exchangesByTest.getOrDefault(key(className, methodName), List.of())));
            }
        } catch (Exception e) {
            log.warn("Could not parse {}: {}", xmlFile, e.getMessage());
        }
        return results;
    }

    /** The owning &lt;class name="..."&gt; element, which is test-method's parent. */
    private String className(Element method) {
        Node parent = method.getParentNode();
        if (parent instanceof Element element && "class".equals(element.getTagName())) {
            return element.getAttribute("name");
        }
        return "unknown";
    }

    private String childText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        String text = nodes.item(0).getTextContent();
        return text == null ? null : text.trim();
    }

    private boolean isAssertionFailure(String exceptionType) {
        if (exceptionType == null) {
            return false;
        }
        String lower = exceptionType.toLowerCase(Locale.ROOT);
        return ASSERTION_TYPES.stream().anyMatch(lower::contains);
    }

    private String key(String className, String methodName) {
        return className + "#" + methodName;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private long parseLong(String s) {
        try {
            return s == null || s.isBlank() ? 0L : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private int count(List<TestExecutionResult> results, TestExecutionResult.Status status) {
        return (int) results.stream().filter(r -> r.status() == status).count();
    }

    private void deleteIfPresent(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not remove stale {}: {}", file, e.getMessage());
        }
    }

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Temp cleanup is best-effort; the OS reclaims it anyway.
                }
            });
        } catch (IOException ignored) {
            // Same - never fail a run over temp-file cleanup.
        }
    }
}
