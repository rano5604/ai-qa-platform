package com.company.aiqa.replay;

import java.util.Map;

/** One captured request, as the report hands it back to be re-issued. */
public record ReplayRequest(String method, String uri, Map<String, String> headers, String body) {
}
