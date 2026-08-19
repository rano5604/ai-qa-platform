package com.company.aiqa.ai.router;

/**
 * Keeps provider failures to one line in the log.
 *
 * <p>Error bodies arrive as pretty-printed JSON - Gemini's runs to twenty lines
 * for a single "API key not valid". The router tries every provider for every
 * batch, so one bad credential produced hundreds of lines of the same document,
 * and the run's actual outcome scrolled far out of view. The status code and
 * the message are what a reader needs; the indentation is not.
 */
public final class ProviderLogs {

    private ProviderLogs() {
    }

    /**
     * Squeezes a failure into one readable line.
     *
     * <p>AllProvidersFailedException's message is multi-line by construction:
     * the first line is the fixed header "All providers failed:" and every
     * provider's actual reason sits on the lines below it, each carrying that
     * provider's raw response body. Any caller that reported "the first line"
     * therefore reported the header - a screen of "TC-001: All providers
     * failed:" that names no provider, no status and no reason.
     */
    public static String compactFailure(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        message.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                // The bodies are pretty-printed JSON; their structural lines
                // repeat identically for every provider and say nothing.
                .filter(line -> !line.startsWith("{") && !line.startsWith("}")
                        && !line.startsWith("\"") && !line.startsWith("[") && !line.startsWith("]"))
                .forEach(line -> {
                    if (parts.size() < 6) {
                        parts.add(line.length() > 160 ? line.substring(0, 160) + "..." : line);
                    }
                });
        return parts.isEmpty() ? e.getClass().getSimpleName() : String.join(" | ", parts);
    }

    /** Default room for a body - enough for a real message, short enough to scan. */
    static final int DEFAULT_MAX = 240;

    /**
     * Collapses a response body onto one line, preserving the readable text and
     * dropping the whitespace that makes it tall.
     */
    static String oneLine(Object body) {
        return oneLine(body, DEFAULT_MAX);
    }

    static String oneLine(Object body, int max) {
        if (body == null) {
            return "(no body)";
        }
        // Deliberately .lines() rather than a whitespace regex: writing that
        // regex needs a double backslash, and Java 15+ reads a single-backslash
        // \s inside a string literal as a SPACE escape - so a one-backslash slip
        // compiles happily into " +", which collapses spaces but leaves every
        // newline in place. That silently defeated the whole point of this
        // method. .lines() splits on line terminators natively, no escapes.
        String flat = String.join(" ", body.toString().lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList());
        if (flat.isEmpty()) {
            return "(empty body)";
        }
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }
}
