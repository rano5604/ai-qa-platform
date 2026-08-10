package com.company.aiqa.ai.router;

import java.util.List;

/**
 * Raised by AiRouterService when every provider in aiqa.router.chain either
 * wasn't configured or failed - the Java counterpart to Python
 * ai_router.router.AllProvidersFailed. Carries every provider's individual
 * failure reason so the caller (ultimately QaPipelineService's try/catch
 * around llmClient.complete) logs something actionable instead of just
 * "the last provider's error".
 */
public class AllProvidersFailedException extends RuntimeException {

    public AllProvidersFailedException(List<String> providerErrors) {
        this(providerErrors, null);
    }

    /**
     * @param hint optional actionable next step appended after the per-provider
     *             reasons - e.g. telling the caller to supply "llmKeys" when the
     *             run had no credentials to work with in the first place, which
     *             is far more useful than a list of "not configured" lines.
     */
    public AllProvidersFailedException(List<String> providerErrors, String hint) {
        super("All providers failed:\n  " + String.join("\n  ", providerErrors)
                + (hint == null || hint.isBlank() ? "" : "\n" + hint));
    }
}
