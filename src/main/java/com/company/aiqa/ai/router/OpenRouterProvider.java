package com.company.aiqa.ai.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenRouter (https://openrouter.ai) speaks the same /chat/completions shape
 * as every other OPENAI_COMPAT catalog entry, but is itself a router across
 * many underlying providers/models: it has its own model-level fallback (the
 * "models" array) and provider-level failover ("provider.order" /
 * "provider.allow_fallbacks"). Neither has an equivalent in
 * AbstractOpenAiCompatProvider's plain {model, temperature, max_tokens,
 * messages} body, so this subclass adds them via extraBodyFields() instead
 * of duplicating the HTTP call every other catalog provider already shares.
 *
 * <p>This sits on top of - not instead of - AiRouterService's own chain and
 * ProviderCooldownRegistry: OpenRouter's fallback happens inside a single
 * HTTP call (fast, no cooldown bookkeeping needed), while the platform's own
 * chain still rotates to a completely different chain entry if OpenRouter
 * itself is unreachable.
 *
 * <p><b>Free-only by design.</b> This platform is meant to run against
 * OpenRouter's zero-cost catalog only - never a model that bills per token.
 * A model id is trusted as free only by OpenRouter's own naming convention
 * (a ":free" suffix, or its self-maintained "openrouter/free" alias that
 * always resolves to whatever is currently free) - see isFreeModelId(). This
 * is enforced here, not just documented in application.yml, because a
 * config edit that adds one wrong id would otherwise start billing silently:
 * the primary model fails startup outright (loud, matches OpenAIService's
 * own fail-fast style for a missing model), and a bad fallback entry is
 * dropped with a warning rather than removing the whole list.
 *
 * <p>Free-tier model ids on OpenRouter churn - GET
 * https://openrouter.ai/api/v1/models and filter for pricing.prompt ==
 * pricing.completion == "0" to see what's live before changing the
 * defaults, the same caution this platform already applies to Cerebras'
 * model ids.
 *
 * <p><b>The "models" array is capped at 3.</b> Verified live: a 5-entry list
 * failed every call with HTTP 400 {@code "'models' array must have 3 items or
 * fewer."} - undocumented in the request-shape guidance, only discoverable by
 * calling it. Capped here rather than left as a configuration convention, for
 * the same reason the free-only check is code and not just a comment: a config
 * edit that adds a fourth entry should degrade to "the extra ones are unused",
 * never to every request failing.
 */
public class OpenRouterProvider extends AbstractOpenAiCompatProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterProvider.class);

    static final String CHAT_COMPLETIONS_URL = "https://openrouter.ai/api/v1/chat/completions";

    /** OpenRouter's own alias that always resolves to a currently-free model - the most churn-resistant choice. */
    private static final String FREE_ROUTER_ALIAS = "openrouter/free";

    /** OpenRouter rejects the whole request when "models" holds more than this - verified live, see class javadoc. */
    static final int MAX_FALLBACK_MODELS = 3;

    private final List<String> fallbackModels;
    private final List<String> providerOrder;
    private final boolean allowFallbacks;

    public OpenRouterProvider(RestClient restClient, String apiKey, String model,
                               List<String> fallbackModels, List<String> providerOrder,
                               boolean allowFallbacks) {
        super(restClient, CHAT_COMPLETIONS_URL, apiKey, requireFree(model));
        this.fallbackModels = filterFree(fallbackModels);
        this.providerOrder = providerOrder == null ? List.of() : providerOrder;
        this.allowFallbacks = allowFallbacks;
    }

    /** True for OpenRouter's own free-router alias, or any id carrying its documented ":free" suffix. */
    static boolean isFreeModelId(String modelId) {
        if (modelId == null) {
            return false;
        }
        String id = modelId.trim();
        return id.equals(FREE_ROUTER_ALIAS) || id.endsWith(":free");
    }

    private static String requireFree(String model) {
        if (!isFreeModelId(model)) {
            throw new IllegalStateException(
                    "aiqa.router.models.openrouter is '" + model + "', which is not a free-tier model id. "
                            + "This platform only calls OpenRouter's zero-cost catalog - use a \":free\"-suffixed "
                            + "id, or '" + FREE_ROUTER_ALIAS + "' to always route to whatever OpenRouter currently "
                            + "has free. See https://openrouter.ai/models?max_price=0 for the current list.");
        }
        return model;
    }

    /** Drops (with a warning, not a startup failure) any fallback entry that isn't free - a list should degrade, not vanish. */
    private static List<String> filterFree(List<String> models) {
        if (models == null || models.isEmpty()) {
            return List.of();
        }
        List<String> kept = new ArrayList<>();
        for (String id : models) {
            if (isFreeModelId(id)) {
                kept.add(id);
            } else {
                log.warn("Dropping non-free model '{}' from aiqa.router.openrouter-fallback-models - "
                        + "this platform only calls OpenRouter's free-tier catalog.", id);
            }
        }
        if (kept.size() > MAX_FALLBACK_MODELS) {
            List<String> dropped = kept.subList(MAX_FALLBACK_MODELS, kept.size());
            log.warn("aiqa.router.openrouter-fallback-models has {} free model(s) configured, but OpenRouter "
                    + "rejects the whole request when \"models\" holds more than {} - dropping the lowest-priority "
                    + "entr{}: {}.", kept.size(), MAX_FALLBACK_MODELS, dropped.size() == 1 ? "y" : "ies", dropped);
            kept = new ArrayList<>(kept.subList(0, MAX_FALLBACK_MODELS));
        }
        return kept;
    }

    @Override
    public String name() {
        return "openrouter";
    }

    @Override
    protected Map<String, Object> extraBodyFields() {
        Map<String, Object> extra = new LinkedHashMap<>();

        // Model-level fallback + priority: OpenRouter tries "model" first,
        // then each entry here in order, until one responds successfully.
        // Free-model routing is just a matter of listing ":free"-suffixed
        // model ids here (or as the primary model) - no separate mechanism.
        if (!fallbackModels.isEmpty()) {
            extra.put("models", new ArrayList<>(fallbackModels));
        }

        // Provider-level failover: which underlying backends may serve the
        // chosen model, in what priority, and whether OpenRouter may fall
        // back to a different backend if the preferred one errors or is out
        // of capacity.
        Map<String, Object> provider = new LinkedHashMap<>();
        if (!providerOrder.isEmpty()) {
            provider.put("order", new ArrayList<>(providerOrder));
        }
        provider.put("allow_fallbacks", allowFallbacks);
        extra.put("provider", provider);

        return extra;
    }
}
