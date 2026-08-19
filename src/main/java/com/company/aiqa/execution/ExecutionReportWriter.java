package com.company.aiqa.execution;

import com.company.aiqa.model.HttpExchange;
import com.company.aiqa.model.TestExecutionResult;
import com.company.aiqa.model.TestExecutionSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the run's HTML report: every test verdict together with the actual
 * HTTP requests and responses that produced it.
 *
 * <p>TestNG already emits its own index.html, but it only knows about
 * pass/fail - the captured traffic lives in a separate http-exchanges.json
 * that nothing joins back for a human. So a report would say
 * "expected 200 but was 404" and the person reading it still had to go
 * find the payload themselves, or re-run the whole thing by hand. The point
 * of this file is that a reviewer can answer "what did we send, what came
 * back, and why did it fail" without leaving the page.
 *
 * <p>Self-contained by design: inline CSS, no scripts, no external assets.
 * The report gets emailed, attached to tickets and opened off a share, and
 * anything referencing a CDN renders as a broken page in exactly those places.
 */
public class ExecutionReportWriter {

    private static final Logger log = LoggerFactory.getLogger(ExecutionReportWriter.class);

    /** Report file name, alongside TestNG's own output. */
    public static final String REPORT_FILE = "aiqa-report.html";

    /** Bodies longer than this are clipped in the HTML - the JSON side-file keeps the full text. */
    private static final int MAX_BODY_CHARS_IN_REPORT = 20_000;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper mapper = new ObjectMapper();

    /** Where the page should send a replay, and the token that authorizes it; empty when the proxy is off. */
    private final String replayUrl;
    private final String replayToken;

    /** No proxy: the button falls back to a direct call from the browser. */
    public ExecutionReportWriter() {
        this("", "");
    }

    public ExecutionReportWriter(String replayUrl, String replayToken) {
        this.replayUrl = replayUrl == null ? "" : replayUrl;
        this.replayToken = replayToken == null ? "" : replayToken;
    }

    /**
     * Writes the report into {@code reportDir} and returns its path, or null if
     * it could not be written.
     *
     * <p>Never throws: a run that produced real results must not be reported as
     * a failure because the cosmetic report didn't save.
     */
    public Path write(TestExecutionSummary summary, Path reportDir, String projectName, String commitHash) {
        Path target = reportDir.resolve(REPORT_FILE);
        try {
            Files.createDirectories(reportDir);
            Files.writeString(target, render(summary, projectName, commitHash), StandardCharsets.UTF_8);
            log.info("Wrote execution report with request/response evidence to {}", target.toAbsolutePath());
            return target;
        } catch (IOException e) {
            log.warn("Could not write the execution report to {}: {}", target, e.getMessage());
            return null;
        }
    }

    private String render(TestExecutionSummary s, String projectName, String commitHash) {
        StringBuilder h = new StringBuilder(1 << 16);
        h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>Test execution report - ").append(esc(projectName)).append("</title>")
                .append("<style>").append(css()).append("</style></head><body>");

        h.append("<h1>Test execution report</h1>");
        h.append("<table class=\"meta\">");
        row(h, "Project", projectName);
        row(h, "Commit", commitHash);
        row(h, "Base URI", s.baseUri());
        row(h, "Generated", LocalDateTime.now().format(STAMP));
        h.append("</table>");

        h.append("<div class=\"tiles\">");
        tile(h, "Total", s.totalTests(), "total");
        tile(h, "Passed", s.passed(), "pass");
        tile(h, "Failed", s.failed(), "fail");
        tile(h, "Errors", s.errorCount(), "err");
        tile(h, "Skipped", s.skipped(), "skip");
        h.append("</div>");

        h.append("<p class=\"sub\">Scripts attempted ").append(s.scriptsAttempted())
                .append(", compiled ").append(s.scriptsCompiled()).append(".</p>");

        if (!s.errors().isEmpty()) {
            h.append("<div class=\"banner\"><strong>Run problems</strong><ul>");
            for (String e : s.errors()) {
                h.append("<li>").append(esc(e)).append("</li>");
            }
            h.append("</ul></div>");
        }

        // What the run as a whole proves, before any individual verdict is
        // read - see RunDiagnosis. A reader who starts at test 1 of 31 has
        // already drawn the wrong conclusion by the time the pattern is
        // visible, so this goes above them.
        List<String> diagnosis = RunDiagnosis.of(s);
        if (!diagnosis.isEmpty()) {
            h.append("<div class=\"banner warn\"><strong>Read this before the results below</strong><ul>");
            for (String d : diagnosis) {
                h.append("<li>").append(esc(d)).append("</li>");
            }
            h.append("</ul></div>");
        }

        // A run where nothing reached the wire is the case this report exists
        // for: without the exchange below, "5 skipped" looks like a flaky suite
        // rather than a target that wasn't listening.
        long withoutTraffic = s.results().stream().filter(r -> r.exchanges().isEmpty()).count();
        boolean setupDied = s.results().stream().anyMatch(TestExecutionResult::isSetupFailure);
        if (withoutTraffic > 0 && setupDied) {
            // Naming three possible causes when we can SEE which one it was is
            // worse than saying nothing: two of the three are contradicted by
            // the setup's own exchanges, which are right there in the report.
            h.append("<div class=\"banner warn\"><strong>").append(withoutTraffic)
                    .append(" test(s) made no HTTP calls because the class setup failed before them.</strong> "
                            + "TestNG skips every test in a class whose setup fails, so these never ran. "
                            + "This is not a sign the target is unreachable - the setup reached it and was "
                            + "answered; see its exchanges above.</div>");
        } else if (withoutTraffic > 0) {
            h.append("<div class=\"banner warn\"><strong>").append(withoutTraffic)
                    .append(" test(s) made no HTTP calls at all.</strong> They failed before reaching the network - "
                            + "usually a setup step, a wrong base URI, or the target service not running. "
                            + "Check the exchanges on the tests that did get through.</div>");
        }

        for (TestExecutionResult r : s.results()) {
            renderTest(h, r, setupDied);
        }

        if (s.results().isEmpty()) {
            h.append("<p class=\"sub\">No test methods ran.</p>");
        }

        return h.append(script()).append("</body></html>").toString();
    }

    private void renderTest(StringBuilder h, TestExecutionResult r, boolean setupDied) {
        String cls = switch (r.status()) {
            case PASSED -> "pass";
            case FAILED -> "fail";
            case ERROR -> "err";
            case SKIPPED -> "skip";
        };
        h.append("<section class=\"test ").append(cls).append("\">");
        h.append("<h2><span class=\"badge ").append(cls).append("\">").append(r.status()).append("</span> ")
                .append(esc(r.testMethodName())).append("</h2>");
        h.append("<div class=\"where\">").append(esc(r.testClassName()))
                .append(" &middot; ").append(r.durationMillis()).append(" ms</div>");

        // TestNG copies the configuration exception onto every test it skips,
        // so a dead setup renders 34 identical "expectation failed" blocks and
        // each test reads as though it asserted something and was wrong. One
        // failure reported thirty-four times is not more information; it is a
        // reader scrolling past the only thing that matters.
        boolean skippedBySetup = setupDied
                && r.status() == TestExecutionResult.Status.SKIPPED
                && r.exchanges().isEmpty();

        if (skippedBySetup) {
            h.append("<p class=\"none\">Never ran - the class setup failed, so TestNG skipped this test. ")
                    .append("The message it carries is the setup's, not this test's.</p>");
        } else if (r.failureMessage() != null && !r.failureMessage().isBlank()) {
            h.append("<div class=\"failure\"><strong>")
                    .append(esc(r.failureType() == null ? "Failure" : r.failureType()))
                    .append("</strong><pre>").append(esc(r.failureMessage())).append("</pre></div>");
        }

        if (r.exchanges().isEmpty()) {
            if (!skippedBySetup) {
                h.append("<p class=\"none\">No HTTP calls were made by this test.</p>");
            }
        } else {
            int i = 1;
            for (HttpExchange x : r.exchanges()) {
                renderExchange(h, x, i++);
            }
        }
        h.append("</section>");
    }

    private void renderExchange(StringBuilder h, HttpExchange x, int index) {
        String statusClass = x.responseStatusCode() >= 200 && x.responseStatusCode() < 300 ? "pass"
                : x.responseStatusCode() >= 400 ? "fail" : "skip";
        h.append("<details class=\"xch\"").append(statusClass.equals("fail") ? " open" : "").append(">");
        h.append("<summary><span class=\"n\">#").append(index).append("</span> ")
                .append("<span class=\"verb\">").append(esc(x.requestMethod())).append("</span> ")
                .append("<span class=\"uri\">").append(esc(x.requestUri())).append("</span> ")
                .append("<span class=\"badge ").append(statusClass).append("\">")
                .append(x.responseStatusCode()).append("</span> ")
                .append("<span class=\"ms\">").append(x.durationMillis()).append(" ms</span></summary>");

        h.append("<div class=\"cols\">");

        h.append("<div class=\"col\"><h3>Request</h3>");
        headers(h, x.requestHeaders());
        body(h, x.requestBody(), x.requestBodyTruncated(), "No request body.");
        h.append("</div>");

        h.append("<div class=\"col\"><h3>Response");
        if (x.responseStatusLine() != null) {
            h.append(" <span class=\"line\">").append(esc(x.responseStatusLine())).append("</span>");
        }
        h.append("</h3>");
        headers(h, x.responseHeaders());
        body(h, x.responseBody(), x.responseBodyTruncated(), "No response body.");
        h.append("</div>");

        h.append("</div>");
        renderSendControls(h, x);
        h.append("</details>");
    }

    /**
     * The Send button, an editor for the request behind it, the panel a result
     * lands in, and the request itself as inert JSON for the page script.
     *
     * <p>Triaging a failure otherwise means rebuilding the request by hand in
     * Postman or curl - retyping a payload that is already on the screen, and
     * usually getting one field wrong on the first try. Re-sending the exact
     * bytes answers the question the reader actually has: is this still
     * failing, and does it fail the same way?
     *
     * <p>The next question is always "what if this one field were different" -
     * a valid merchant id instead of the one the fixture failed to create, a
     * corrected enum value, one field removed to see which one the 400 is
     * about. That loop is the whole job of triage, and every iteration of it
     * used to mean leaving the report. So the request is editable in place:
     * method, URL, headers and body, sent through the same path.
     *
     * <p>The fields start empty and are filled by the script from the captured
     * JSON. That is deliberate - rendering a payload into a {@code value=""}
     * attribute or a textarea means escaping it correctly in a second context,
     * and a body that is already full of quotes and angle brackets is exactly
     * where that goes wrong.
     *
     * <p>The JSON is carried in a {@code <script type="application/json">},
     * which the browser stores and never executes. {@code <} is escaped so a
     * body containing {@code </script>} cannot close the tag early and turn a
     * captured payload into markup.
     */
    private void renderSendControls(StringBuilder h, HttpExchange x) {
        boolean redacted = x.requestHeaders() != null
                && x.requestHeaders().values().stream().anyMatch(v -> v != null && v.contains("<redacted>"));

        h.append("<div class=\"send\">");
        h.append("<button type=\"button\" class=\"send-btn\">Send again</button>");
        h.append("<button type=\"button\" class=\"edit-btn\">Edit request</button>");
        h.append("<span class=\"send-note\">Sends this request from your browser - as captured, ")
                .append("or with your edits.");
        if (redacted) {
            h.append(" A credential header was redacted from this report, so it goes out without it - ")
                    .append("put it back in the editor if the call needs it.");
        }
        h.append("</span>");

        h.append("<div class=\"send-edit\" hidden>");
        h.append("<div class=\"edit-line\">");
        h.append("<label class=\"edit-method-l\">Method<select class=\"edit-method\">");
        for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")) {
            h.append("<option>").append(method).append("</option>");
        }
        h.append("</select></label>");
        h.append("<label class=\"edit-uri-l\">URL<input type=\"text\" class=\"edit-uri\" ")
                .append("spellcheck=\"false\"></label>");
        h.append("</div>");
        h.append("<label>Headers <em>one per line, as Name: value</em>")
                .append("<textarea class=\"edit-headers\" rows=\"3\" spellcheck=\"false\"></textarea></label>");
        h.append("<label>Body<textarea class=\"edit-body\" rows=\"8\" spellcheck=\"false\"></textarea></label>");
        h.append("<button type=\"button\" class=\"reset-btn\">Reset to captured</button>");
        h.append("</div>");

        h.append("<div class=\"send-out\" hidden></div>");
        h.append("<script type=\"application/json\" class=\"send-req\">")
                .append(requestJson(x))
                .append("</script>");
        h.append("</div>");
    }

    /** The request as the page script needs it: method, uri, sendable headers, body. */
    private String requestJson(HttpExchange x) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", x.requestMethod() == null ? "GET" : x.requestMethod());
        request.put("uri", x.requestUri());
        Map<String, String> headers = new LinkedHashMap<>();
        if (x.requestHeaders() != null) {
            x.requestHeaders().forEach((name, value) -> {
                // Redacted values would be sent literally as "<redacted>", and
                // the browser refuses to set the rest itself - see FORBIDDEN in
                // the page script for why they are dropped there too.
                if (value != null && !value.contains("<redacted>")) {
                    headers.put(name, value);
                }
            });
        }
        request.put("headers", headers);
        request.put("body", x.requestBody());
        try {
            // Emit the six characters \u003c, which JSON.parse turns back into
            // "<" while the HTML parser never sees a "<" at all. Note the
            // DOUBLE backslash. A single one is consumed by the compiler's own
            // unicode preprocessing, BEFORE the string literal exists, so
            // replace("<", "\u003c") compiles to replace("<", "<") - a no-op
            // that reads exactly like the fix and passes review. A captured body
            // containing </script> then closed this block early: the JSON was
            // truncated, JSON.parse threw, that panel's button never bound, and the
            // rest of the payload landed in the page as markup.
            return mapper.writeValueAsString(request).replace("<", "\\u003c");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }

    private void headers(StringBuilder h, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        h.append("<table class=\"hdr\">");
        headers.forEach((k, v) -> h.append("<tr><td>").append(esc(k)).append("</td><td>")
                .append(esc(v)).append("</td></tr>"));
        h.append("</table>");
    }

    private void body(StringBuilder h, String raw, boolean truncated, String emptyText) {
        if (raw == null || raw.isBlank()) {
            h.append("<p class=\"none\">").append(emptyText).append("</p>");
            return;
        }
        String text = prettyJsonOrRaw(raw);
        boolean clipped = false;
        if (text.length() > MAX_BODY_CHARS_IN_REPORT) {
            text = text.substring(0, MAX_BODY_CHARS_IN_REPORT);
            clipped = true;
        }
        h.append("<pre>").append(esc(text)).append("</pre>");
        if (truncated || clipped) {
            h.append("<p class=\"none\">Body shown is truncated. The full text is in ")
                    .append("http-exchanges.json next to this report.</p>");
        }
    }

    /**
     * Pretty-prints a JSON body so it is readable, falling back to the raw text
     * for anything that isn't JSON (HTML error pages, plain text, form bodies).
     */
    private String prettyJsonOrRaw(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || !(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return raw;
        }
        try {
            Object tree = mapper.readValue(trimmed, Object.class);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
        } catch (Exception e) {
            return raw;
        }
    }

    private void row(StringBuilder h, String k, String v) {
        h.append("<tr><td>").append(esc(k)).append("</td><td>")
                .append(v == null ? "-" : esc(v)).append("</td></tr>");
    }

    private void tile(StringBuilder h, String label, int n, String cls) {
        h.append("<div class=\"tile ").append(cls).append("\"><div class=\"n\">").append(n)
                .append("</div><div class=\"l\">").append(label).append("</div></div>");
    }

    /**
     * Escapes text for HTML. Response bodies are attacker-influenced in the
     * general case - an API can echo back whatever was sent - so unescaped
     * output here would let a payload inject markup into the report someone
     * opens later.
     */
    private String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * The page's only script: it replays one captured request when the reader
     * asks, and does nothing otherwise.
     *
     * <p>Inline, like the CSS - this report gets emailed, attached to tickets
     * and opened off a share, where anything referencing a CDN renders as a
     * broken page.
     *
     * <p>A replay can fail for a reason that has nothing to do with the API:
     * opened from disk the page's origin is {@code null}, so unless the service
     * sends permissive CORS headers the browser blocks the call before it
     * leaves. That is indistinguishable from "the service is down" in a
     * fetch() rejection, so the failure branch says so and prints the
     * equivalent curl command - which has no such restriction.
     */
    private String script() {
        return """
                <script>
                var AIQA_REPLAY_URL = "%s";
                var AIQA_REPLAY_TOKEN = "%s";
                </script>
                """.formatted(esc(replayUrl), esc(replayToken)) + """
                <script>
                (function () {
                  // The browser sets these itself and rejects any attempt to
                  // override them; sending them back verbatim would fail the
                  // whole request rather than being ignored.
                  var FORBIDDEN = ['host','content-length','connection','accept-encoding',
                                   'transfer-encoding','origin','referer','cookie'];

                  function esc(text) {
                    return String(text).replace(/[&<>]/g, function (c) {
                      return { '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c];
                    });
                  }

                  function pretty(text) {
                    try { return JSON.stringify(JSON.parse(text), null, 2); } catch (e) { return text; }
                  }

                  function curlFor(req) {
                    var parts = ["curl -i -X " + req.method + " '" + req.uri + "'"];
                    Object.keys(req.headers || {}).forEach(function (name) {
                      parts.push("-H '" + name + ": " + req.headers[name] + "'");
                    });
                    if (req.body) { parts.push("-d '" + String(req.body).replace(/'/g, "'\\\\''") + "'"); }
                    return parts.join(" ");
                  }

                  // What will actually go on the wire: the browser sets the
                  // forbidden headers itself and rejects any attempt to override
                  // them, so a request carrying them back fails outright rather
                  // than having them ignored. Dropping them here means the
                  // editor shows exactly what gets sent, with nothing in it that
                  // silently will not.
                  function sendable(req) {
                    var headers = {};
                    Object.keys(req.headers || {}).forEach(function (name) {
                      if (FORBIDDEN.indexOf(name.toLowerCase()) < 0) { headers[name] = req.headers[name]; }
                    });
                    return { method: req.method || 'GET', uri: req.uri || '', headers: headers,
                             body: req.body || '' };
                  }

                  function fillEditor(panel, req) {
                    panel.querySelector('.edit-method').value = req.method;
                    panel.querySelector('.edit-uri').value = req.uri;
                    panel.querySelector('.edit-headers').value = Object.keys(req.headers)
                      .map(function (name) { return name + ': ' + req.headers[name]; }).join('\\n');
                    panel.querySelector('.edit-body').value = req.body;
                  }

                  // A header line is "Name: value". Anything without a colon is
                  // half-typed rather than meaningful, so it is skipped instead
                  // of being sent as a header with an empty name.
                  function readEditor(panel) {
                    var headers = {};
                    panel.querySelector('.edit-headers').value.split(/[\\r\\n]+/).forEach(function (line) {
                      var at = line.indexOf(':');
                      if (at > 0) {
                        var name = line.slice(0, at).trim();
                        if (name && FORBIDDEN.indexOf(name.toLowerCase()) < 0) {
                          headers[name] = line.slice(at + 1).trim();
                        }
                      }
                    });
                    return {
                      method: panel.querySelector('.edit-method').value,
                      uri: panel.querySelector('.edit-uri').value.trim(),
                      headers: headers,
                      body: panel.querySelector('.edit-body').value
                    };
                  }

                  // Header order is not meaningful and the editor does not
                  // preserve it, so compare on sorted names - otherwise merely
                  // opening the editor would report the request as edited.
                  function canonical(req) {
                    var names = Object.keys(req.headers).sort();
                    return JSON.stringify([req.method, req.uri, names.map(function (n) {
                      return [n, req.headers[n]];
                    }), req.body]);
                  }

                  // Two ways to send. The proxy is this platform re-issuing the
                  // call server-side, where no same-origin policy exists at all;
                  // the direct call is the browser doing it, which works only
                  // when the service under test allows this page's origin - and
                  // a report opened from disk has none. Proxy first, direct as
                  // the fallback, curl as the answer when neither can.
                  function viaProxy(req) {
                    return fetch(AIQA_REPLAY_URL, {
                      method: 'POST',
                      headers: { 'Content-Type': 'application/json', 'X-Aiqa-Replay-Token': AIQA_REPLAY_TOKEN },
                      body: JSON.stringify({ method: req.method, uri: req.uri, headers: req.headers,
                                             body: req.body })
                    }).then(function (response) {
                      // A proxy that is down or refuses the token says nothing
                      // about the service - fall through to the direct call
                      // rather than reporting it as an answer.
                      if (!response.ok) { throw new Error('replay endpoint answered ' + response.status); }
                      return response.json();
                    }).then(function (result) {
                      // The proxy DID run the call and it failed. That is a real
                      // answer about the service, so report it as one.
                      if (result.error) { return { failed: true, message: result.error, ms: result.durationMillis }; }
                      return { status: result.status, text: result.body || '', ms: result.durationMillis,
                               via: 'via this platform' };
                    });
                  }

                  function direct(req, started) {
                    return fetch(req.uri, {
                      method: req.method,
                      headers: req.headers,
                      body: (req.method === 'GET' || req.method === 'HEAD') ? undefined : req.body
                    }).then(function (response) {
                      return response.text().then(function (text) {
                        return { status: response.status, text: text, ms: Date.now() - started,
                                 via: 'direct from your browser' };
                      });
                    });
                  }

                  document.querySelectorAll('.send').forEach(function (panel) {
                    var button = panel.querySelector('.send-btn');
                    var editButton = panel.querySelector('.edit-btn');
                    var resetButton = panel.querySelector('.reset-btn');
                    var editor = panel.querySelector('.send-edit');
                    var out = panel.querySelector('.send-out');
                    var captured = sendable(JSON.parse(panel.querySelector('.send-req').textContent));

                    fillEditor(panel, captured);

                    editButton.addEventListener('click', function () {
                      editor.hidden = !editor.hidden;
                      editButton.textContent = editor.hidden ? 'Edit request' : 'Hide editor';
                    });

                    resetButton.addEventListener('click', function () {
                      fillEditor(panel, captured);
                    });

                    button.addEventListener('click', function () {
                      var req = readEditor(panel);
                      var edited = canonical(req) !== canonical(captured);

                      if (!req.uri) {
                        editor.hidden = false;
                        editButton.textContent = 'Hide editor';
                        out.hidden = false;
                        out.innerHTML = '<p class="none">Give the request a URL before sending it.</p>';
                        return;
                      }

                      button.disabled = true;
                      button.textContent = 'Sending...';
                      out.hidden = false;
                      out.innerHTML = '<p class="none">Waiting for a response...</p>';
                      var started = Date.now();

                      var attempt = AIQA_REPLAY_URL
                        ? viaProxy(req).catch(function () { return direct(req, started); })
                        : direct(req, started);

                      attempt.then(function (result) {
                        // An edited request is a different request. Saying so is
                        // the whole point: a green 200 from a payload you fixed
                        // by hand is not evidence that the test above passes, and
                        // a reader scrolling past the badge will assume it is.
                        var title = edited ? 'Result of your edited request' : 'Replay';
                        var caution = edited
                          ? '<p class="edited-warn">' + esc(req.method + ' ' + req.uri) +
                            ' - this is not the request the test sent, so it neither confirms nor ' +
                            'clears the verdict above.</p>'
                          : '';
                        if (result.failed) {
                          out.innerHTML =
                            '<h3>' + title + ' failed <span class="ms">' + result.ms + ' ms</span></h3>' +
                            caution + '<pre>' + esc(result.message) + '</pre>';
                          return;
                        }
                        var cls = result.status < 300 ? 'pass' : (result.status >= 400 ? 'fail' : 'skip');
                        out.innerHTML =
                          '<h3>' + title + ' <span class="badge ' + cls + '">' + result.status + '</span>' +
                          '<span class="ms">' + result.ms + ' ms, ' + result.via + '</span></h3>' +
                          caution + '<pre>' + esc(pretty(result.text)) + '</pre>';
                      }).catch(function (error) {
                        out.innerHTML =
                          '<h3>Request blocked</h3>' +
                          '<p class="none">' + esc(error.message) + ' - the request never left the browser. ' +
                          'Opened from a file this page has no origin, so a direct call needs the service to ' +
                          'allow cross-origin requests' + (AIQA_REPLAY_URL
                            ? ', and this platform was not reachable at ' + esc(AIQA_REPLAY_URL) + ' to send it for you.'
                            : '. Start the platform to have it sent server-side instead.') +
                          ' Run this meanwhile:</p>' +
                          '<pre>' + esc(curlFor(req)) + '</pre>';
                      }).then(function () {
                        button.disabled = false;
                        button.textContent = 'Send again';
                      });
                    });
                  });
                })();
                </script>
                """;
    }

    private String css() {
        return """
                *{box-sizing:border-box}
                body{font:14px/1.5 -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;
                     margin:0;padding:28px;color:#1a1a1a;background:#f6f7f9}
                h1{margin:0 0 16px;font-size:22px}
                h2{margin:0 0 4px;font-size:15px}
                h3{margin:0 0 6px;font-size:12px;text-transform:uppercase;letter-spacing:.05em;color:#666}
                table.meta{border-collapse:collapse;margin-bottom:18px;background:#fff;border:1px solid #e2e5e9;border-radius:6px}
                table.meta td{padding:6px 12px;border-bottom:1px solid #eef0f3}
                table.meta td:first-child{color:#666;white-space:nowrap}
                table.meta tr:last-child td{border-bottom:0}
                .tiles{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:8px}
                .tile{background:#fff;border:1px solid #e2e5e9;border-radius:6px;padding:10px 18px;min-width:88px}
                .tile .n{font-size:22px;font-weight:600}
                .tile .l{font-size:11px;text-transform:uppercase;letter-spacing:.05em;color:#666}
                .tile.pass .n{color:#127a3d}.tile.fail .n{color:#b3261e}
                .tile.err .n{color:#8a4b00}.tile.skip .n{color:#5a6472}
                .sub{color:#666;margin:6px 0 18px}
                .banner{background:#fff4e5;border:1px solid #f0c894;border-radius:6px;padding:10px 14px;margin-bottom:16px}
                .banner.warn{background:#fdf0ef;border-color:#f0b3ae}
                .banner ul{margin:6px 0 0 18px;padding:0}
                section.test{background:#fff;border:1px solid #e2e5e9;border-left-width:4px;
                             border-radius:6px;padding:14px 16px;margin-bottom:12px}
                section.test.pass{border-left-color:#2f9e5f}
                section.test.fail{border-left-color:#d93025}
                section.test.err{border-left-color:#e08600}
                section.test.skip{border-left-color:#9aa3ad}
                .where{color:#777;font-size:12px;margin-bottom:10px}
                .badge{display:inline-block;padding:1px 7px;border-radius:10px;font-size:11px;
                       font-weight:600;color:#fff;vertical-align:middle}
                .badge.pass{background:#2f9e5f}.badge.fail{background:#d93025}
                .badge.err{background:#e08600}.badge.skip{background:#9aa3ad}
                .failure{background:#fdf0ef;border:1px solid #f3c2be;border-radius:5px;padding:8px 10px;margin:8px 0}
                details.xch{border:1px solid #e6e9ed;border-radius:5px;margin-top:8px;background:#fbfcfd}
                details.xch summary{cursor:pointer;padding:7px 10px;font-size:13px;user-select:none}
                details.xch[open] summary{border-bottom:1px solid #e6e9ed}
                .n{color:#8b95a1;margin-right:4px}
                .verb{font-weight:700;font-family:ui-monospace,Consolas,monospace}
                .uri{font-family:ui-monospace,Consolas,monospace;word-break:break-all}
                .ms{color:#8b95a1;font-size:11px;margin-left:4px}
                .line{font-weight:400;text-transform:none;letter-spacing:0;color:#888}
                .cols{display:flex;gap:14px;padding:10px;flex-wrap:wrap}
                .col{flex:1 1 340px;min-width:0}
                table.hdr{border-collapse:collapse;width:100%;margin-bottom:6px;font-size:11px}
                table.hdr td{padding:2px 6px;border-bottom:1px solid #eef0f3;
                             font-family:ui-monospace,Consolas,monospace;word-break:break-all}
                table.hdr td:first-child{color:#777;white-space:nowrap;width:1%}
                pre{background:#f4f6f8;border:1px solid #e6e9ed;border-radius:4px;padding:8px 10px;
                    margin:0 0 6px;overflow-x:auto;white-space:pre-wrap;word-break:break-word;
                    font-family:ui-monospace,Consolas,monospace;font-size:12px;max-height:420px}
                .none{color:#8b95a1;font-size:12px;font-style:italic;margin:0 0 6px}
                .send{display:flex;align-items:center;gap:10px;flex-wrap:wrap;
                      padding:8px 10px;border-top:1px solid #eef0f3;background:#fff}
                .send-btn{font:inherit;font-size:12px;font-weight:600;color:#fff;background:#3060c0;
                          border:0;border-radius:4px;padding:5px 14px;cursor:pointer}
                .send-btn:hover{background:#254c9c}
                .send-btn:disabled{background:#9aa3ad;cursor:default}
                .send-note{color:#8b95a1;font-size:11px}
                .send-out{flex:1 1 100%;margin-top:2px}
                .send-out h3{margin-top:6px}
                .edit-btn,.reset-btn{font:inherit;font-size:12px;font-weight:600;color:#3060c0;
                          background:#fff;border:1px solid #c6d0dc;border-radius:4px;
                          padding:5px 12px;cursor:pointer}
                .edit-btn:hover,.reset-btn:hover{background:#f0f4fa;border-color:#3060c0}
                .send-edit{flex:1 1 100%;display:flex;flex-direction:column;gap:8px;
                           margin-top:4px;padding:10px;background:#f8f9fb;
                           border:1px solid #e2e5e9;border-radius:6px}
                .send-edit label{display:flex;flex-direction:column;gap:3px;
                                 font-size:11px;font-weight:600;text-transform:uppercase;
                                 letter-spacing:.05em;color:#666}
                .send-edit em{font-weight:400;font-style:normal;text-transform:none;
                              letter-spacing:0;color:#8b95a1}
                .send-edit input,.send-edit select,.send-edit textarea{
                              font-family:ui-monospace,Consolas,monospace;font-size:12px;
                              padding:6px 8px;border:1px solid #d5dae0;border-radius:4px;
                              background:#fff;color:#1a1a1a;width:100%}
                .send-edit textarea{resize:vertical;white-space:pre;overflow-wrap:normal;overflow-x:auto}
                .send-edit input:focus,.send-edit select:focus,.send-edit textarea:focus{
                              outline:2px solid #3060c0;outline-offset:-1px;border-color:#3060c0}
                .edit-line{display:flex;gap:8px;align-items:flex-end}
                .edit-method-l{flex:0 0 110px}
                .edit-uri-l{flex:1 1 auto}
                .reset-btn{align-self:flex-start}
                .edited-warn{margin:0 0 6px;padding:6px 9px;font-size:12px;
                             color:#7a4a00;background:#fff6e5;border:1px solid #f0d9a8;
                             border-radius:4px;font-family:ui-monospace,Consolas,monospace}
                """;
    }
}
