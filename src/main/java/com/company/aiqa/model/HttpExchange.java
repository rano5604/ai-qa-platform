package com.company.aiqa.model;

import java.util.Map;

/**
 * One HTTP request/response pair captured while a generated test ran.
 *
 * <p>This is the evidence a QA reviewer actually needs: a report saying
 * "expected 200 but was 400" is unactionable without the payload that caused
 * it. Capture happens inside the execution subprocess (see HttpCaptureListener),
 * is written to a JSON side-file, and is joined back onto the parsed TestNG
 * results by test class + method name.
 *
 * <p>A single test method may perform several exchanges - a POST to create,
 * then a GET to verify - so these are held as a list per test, in the order
 * they were issued.
 *
 * <p><b>Headers are redacted.</b> Anything carrying a credential
 * (Authorization, Cookie, API keys) comes back as a placeholder rather than its
 * real value: these records are returned over the API and written to disk, and
 * a bearer token that leaks into a report is a bearer token that leaks.
 *
 * <p>Bodies are truncated past a configurable limit, with the corresponding
 * truncated flag set, so one endpoint returning a 40 MB payload can't make the
 * response unusable.
 */
public record HttpExchange(

        /** Test that issued this call - "com.company.aiqa.generated.Foo". */
        String testClassName,

        /** Method that issued it. A setup method's calls are attributed to that method. */
        String testMethodName,

        String requestMethod,

        /** Fully resolved URI, path and query params substituted, as actually sent. */
        String requestUri,

        Map<String, String> requestHeaders,

        /** Null when the request had no body (typically GET/DELETE). */
        String requestBody,

        boolean requestBodyTruncated,

        int responseStatusCode,

        /** e.g. "HTTP/1.1 400 Bad Request" - null if the client never got a status line. */
        String responseStatusLine,

        Map<String, String> responseHeaders,

        String responseBody,

        boolean responseBodyTruncated,

        /** Wall-clock time for this one call, measured around the filter chain. */
        long durationMillis
) {
}
