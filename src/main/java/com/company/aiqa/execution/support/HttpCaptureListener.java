package com.company.aiqa.execution.support;

import com.company.aiqa.model.HttpExchange;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.restassured.builder.ResponseBuilder;
import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.http.Header;
import io.restassured.http.Headers;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import org.testng.IExecutionListener;
import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ITestResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Records every HTTP request and response the generated tests make, attributed
 * to the test method that made it.
 *
 * <p><b>Runs in the execution subprocess, not in the platform.</b> It is passed
 * to {@code org.testng.TestNG} with {@code -listener}, and communicates its
 * findings back through a JSON file whose path arrives as a system property.
 * There is no Spring context here and there must be no dependency on one.
 *
 * <p>Capture is attached by this listener rather than by the generated code
 * itself, deliberately. Asking the LLM to add a logging filter to every script
 * would make the feature only as reliable as the model's memory of one more
 * contract rule - and a script that silently forgot it would produce results
 * with no evidence attached. Installing a global REST Assured filter from
 * outside means capture works on any script, including ones generated before
 * this existed.
 *
 * <p>Credential-bearing headers are redacted before anything is written: these
 * records travel out over the API and land on disk, so a real bearer token in
 * one is a leaked bearer token.
 */
public class HttpCaptureListener implements IExecutionListener, IInvokedMethodListener {

    /** Where to write the captured exchanges. Capture is disabled entirely when unset. */
    public static final String CAPTURE_FILE_PROPERTY = "aiqa.capture.file";

    /** Per-body character cap; bodies past it are truncated and flagged. */
    public static final String MAX_BODY_CHARS_PROPERTY = "aiqa.capture.maxBodyChars";

    /** Hard ceiling on recorded exchanges, so a runaway loop can't fill the disk. */
    public static final String MAX_EXCHANGES_PROPERTY = "aiqa.capture.maxExchanges";

    private static final int DEFAULT_MAX_BODY_CHARS = 8_000;
    private static final int DEFAULT_MAX_EXCHANGES = 500;

    /** Matched case-insensitively against header names. */
    private static final Set<String> REDACTED_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie",
            "x-api-key", "api-key", "x-auth-token", "x-access-token", "authentication");

    private static final String REDACTED = "<redacted>";

    private static final List<HttpExchange> EXCHANGES = Collections.synchronizedList(new ArrayList<>());

    /**
     * The test method currently executing on this thread. A ThreadLocal because
     * TestNG can be told to run methods in parallel, in which case several
     * tests are on the wire at once and a single "current method" field would
     * attribute their calls to each other.
     */
    private static final ThreadLocal<String[]> CURRENT_METHOD = new ThreadLocal<>();

    private static volatile boolean limitReported;

    @Override
    public void onExecutionStart() {
        if (captureFile() == null) {
            return;
        }
        installFilter();
    }

    @Override
    public void beforeInvocation(IInvokedMethod method, ITestResult testResult) {
        CURRENT_METHOD.set(new String[]{
                testResult.getTestClass() != null ? testResult.getTestClass().getName() : "unknown",
                testResult.getMethod() != null ? testResult.getMethod().getMethodName() : "unknown"
        });
        if (captureFile() != null) {
            // Re-assert on every method: a generated script is free to call
            // RestAssured.reset(), which would quietly drop the global filter
            // and leave every subsequent test with no evidence attached.
            installFilter();
        }
    }

    @Override
    public void afterInvocation(IInvokedMethod method, ITestResult testResult) {
        CURRENT_METHOD.remove();
    }

    @Override
    public void onExecutionFinish() {
        String target = captureFile();
        if (target == null) {
            return;
        }
        List<HttpExchange> snapshot;
        synchronized (EXCHANGES) {
            snapshot = new ArrayList<>(EXCHANGES);
        }
        try {
            Path path = Path.of(target);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(), snapshot);
        } catch (Exception e) {
            // Nothing here may fail the run: the tests have already executed and
            // their pass/fail verdicts stand on their own. Losing the captured
            // traffic degrades the report, it doesn't invalidate the result.
            System.err.println("[aiqa] Could not write captured HTTP exchanges to " + target + ": " + e);
        }
    }

    private static synchronized void installFilter() {
        boolean present = RestAssured.filters().stream().anyMatch(f -> f instanceof CapturingFilter);
        if (!present) {
            RestAssured.filters(new CapturingFilter());
        }
    }

    private static String captureFile() {
        String value = System.getProperty(CAPTURE_FILE_PROPERTY);
        return value == null || value.isBlank() ? null : value;
    }

    private static int intProperty(String key, int fallback) {
        try {
            String value = System.getProperty(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Rethrows the original throwable unchanged, checked or not.
     *
     * <p>Wrapping it in a RuntimeException instead would change the exception
     * type TestNG records, and the runner classifies FAILED vs ERROR from
     * exactly that type - a wrapped ConnectException would stop looking like a
     * transport error.
     */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    /**
     * Sits in REST Assured's global filter chain and records each exchange as
     * it completes.
     */
    private static final class CapturingFilter implements Filter {

        @Override
        public Response filter(FilterableRequestSpecification requestSpec,
                               FilterableResponseSpecification responseSpec,
                               FilterContext ctx) {

            // Read the request BEFORE sending: REST Assured mutates parts of the
            // spec as it builds the actual call.
            String method = requestSpec.getMethod();
            String uri = requestSpec.getURI();
            Map<String, String> requestHeaders = toMap(requestSpec.getHeaders());
            String requestBody = bodyAsString(requestSpec.getBody());

            long startedNanos = System.nanoTime();
            Response response;
            try {
                response = ctx.next(requestSpec, responseSpec);
            } catch (Throwable t) {
                // The call never reached a response - connection refused, DNS
                // failure, timeout. Record the ATTEMPT anyway and rethrow: the
                // exception alone says "Connection refused" without naming the
                // URI, which is the one thing you need to know to fix it.
                //
                // Throwable, not RuntimeException: REST Assured propagates the
                // underlying transport failure as-is, so what arrives here is a
                // CHECKED java.net.ConnectException that a RuntimeException
                // catch silently misses.
                recordFailedAttempt(method, uri, requestHeaders, requestBody, t,
                        (System.nanoTime() - startedNanos) / 1_000_000L);
                throw sneakyThrow(t);
            }
            long durationMillis = (System.nanoTime() - startedNanos) / 1_000_000L;

            String responseBody;
            try {
                responseBody = response.getBody() == null ? null : response.getBody().asString();
            } catch (Exception e) {
                responseBody = "<body could not be read: " + e.getMessage() + ">";
            }

            record(method, uri, requestHeaders, requestBody, response, responseBody, durationMillis);

            // Reading the body above consumes the underlying stream. Handing the
            // original Response back would leave the test's own assertions
            // reading an exhausted stream and seeing an empty body - so return a
            // clone carrying the already-materialised content.
            return new ResponseBuilder()
                    .clone(response)
                    .setBody(responseBody == null ? "" : responseBody)
                    .build();
        }

        /**
         * A request that never got a response. Status 0 is the marker; the
         * transport exception stands in for the body.
         */
        private void recordFailedAttempt(String method, String uri, Map<String, String> requestHeaders,
                                         String requestBody, Throwable cause, long durationMillis) {
            if (!hasCapacity()) {
                return;
            }
            int maxBody = intProperty(MAX_BODY_CHARS_PROPERTY, DEFAULT_MAX_BODY_CHARS);
            String[] current = CURRENT_METHOD.get();
            String trimmedRequest = truncate(requestBody, maxBody);

            EXCHANGES.add(new HttpExchange(
                    current != null ? current[0] : "unknown",
                    current != null ? current[1] : "unknown",
                    method, uri, requestHeaders, trimmedRequest,
                    isTruncated(requestBody, trimmedRequest),
                    0, null, Map.of(),
                    "<no response: " + cause.getClass().getName() + ": " + cause.getMessage() + ">",
                    false, durationMillis));
        }

        private void record(String method, String uri, Map<String, String> requestHeaders, String requestBody,
                            Response response, String responseBody, long durationMillis) {

            if (!hasCapacity()) {
                return;
            }

            int maxBody = intProperty(MAX_BODY_CHARS_PROPERTY, DEFAULT_MAX_BODY_CHARS);
            String[] current = CURRENT_METHOD.get();
            String testClass = current != null ? current[0] : "unknown";
            String testMethod = current != null ? current[1] : "unknown";

            String trimmedRequest = truncate(requestBody, maxBody);
            String trimmedResponse = truncate(responseBody, maxBody);

            Map<String, String> responseHeaders;
            int statusCode;
            String statusLine;
            try {
                responseHeaders = toMap(response.getHeaders());
                statusCode = response.getStatusCode();
                statusLine = response.getStatusLine();
            } catch (Exception e) {
                responseHeaders = Map.of();
                statusCode = 0;
                statusLine = null;
            }

            EXCHANGES.add(new HttpExchange(
                    testClass, testMethod,
                    method, uri, requestHeaders, trimmedRequest,
                    isTruncated(requestBody, trimmedRequest),
                    statusCode, statusLine, responseHeaders, trimmedResponse,
                    isTruncated(responseBody, trimmedResponse),
                    durationMillis));
        }

        /** False once the recording ceiling is hit - tests carry on, only the evidence stops. */
        private boolean hasCapacity() {
            int maxExchanges = intProperty(MAX_EXCHANGES_PROPERTY, DEFAULT_MAX_EXCHANGES);
            if (EXCHANGES.size() < maxExchanges) {
                return true;
            }
            if (!limitReported) {
                limitReported = true;
                System.err.println("[aiqa] Capture limit of " + maxExchanges
                        + " HTTP exchanges reached; further calls run normally but are not recorded.");
            }
            return false;
        }

        /** Duplicate header names are joined rather than silently overwriting each other. */
        private Map<String, String> toMap(Headers headers) {
            Map<String, String> map = new LinkedHashMap<>();
            if (headers == null) {
                return map;
            }
            for (Header header : headers) {
                String name = header.getName();
                String value = REDACTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))
                        ? REDACTED
                        : header.getValue();
                map.merge(name, value, (existing, added) -> existing + ", " + added);
            }
            return map;
        }

        private String bodyAsString(Object body) {
            if (body == null) {
                return null;
            }
            if (body instanceof byte[] bytes) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            return body.toString();
        }

        private String truncate(String value, int max) {
            if (value == null || value.length() <= max) {
                return value;
            }
            return value.substring(0, max) + "... [truncated, " + value.length() + " chars total]";
        }

        private boolean isTruncated(String original, String stored) {
            return original != null && stored != null && !original.equals(stored);
        }
    }
}
