package com.company.aiqa.replay;

import java.util.Map;

/**
 * What the replayed call produced.
 *
 * @param status HTTP status, or 0 when the call never completed
 * @param error  why it never completed; null when it did
 */
public record ReplayResult(int status, Map<String, String> headers, String body, long durationMillis, String error) {
}
