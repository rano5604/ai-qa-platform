package com.company.aiqa.ai.router;

import com.company.aiqa.ai.LlmClient;
import com.company.aiqa.config.RouterProperties;
import com.company.aiqa.model.LlmKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Step: "Send Context to LLM" - rotating multi-provider implementation,
 * ported from the ai-router Python package (ai_router.router.AIRouter).
 *
 * Tries every provider in aiqa.router.chain, in order, and returns the
 * first one that succeeds - a provider "succeeding" means it was
 * available() (configured) AND complete() didn't throw. Any failure
 * (missing key, network error, rate limit, malformed response) falls
 * through to the next provider rather than failing the whole request,
 * which is what makes this "rotating": as long as at least one candidate
 * in the chain is up, a merge still gets processed.
 *
 * Two credential sources are supported:
 *   - server-configured (aiqa.router.*, normally from environment
 *     variables) - used by complete(system, user), the chain built once at
 *     startup;
 *   - per-request (see LlmKeys) - used by complete(system, user, keys),
 *     which builds a chain containing ONLY the providers that request
 *     activated. A provider the caller didn't supply a key for is never
 *     constructed and never called.
 *
 * Active when aiqa.llm.provider=router - see LlmProperties. This sits
 * alongside (not instead of) the existing single-provider OpenAIService /
 * GeminiService beans, which remain available via
 * aiqa.llm.provider=openai / =gemini for anyone who wants a single fixed
 * backend instead of a fallback chain.
 */
@Service
@ConditionalOnProperty(prefix = "aiqa.llm", name = "provider", havingValue = "router")
public class AiRouterService implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AiRouterService.class);

    /** Maps the short names used in aiqa.router.chain to their provider constructors - mirrors ai_router.router.REGISTRY. */
    private static final Map<String, BiFunction<RestClient, RouterProperties, AiProvider>> REGISTRY = Map.of(
            "gemini", GeminiRouterProvider::new,
            "groq", GroqProvider::new,
            "mistral", MistralProvider::new,
            "github", GitHubModelsProvider::new,
            "ollama", OllamaProvider::new
    );

    private final RestClient restClient;
    private final RouterProperties properties;
    private final ProviderCooldownRegistry cooldownRegistry;
    private final List<AiProvider> providers;

    public AiRouterService(RestClient restClient, RouterProperties properties,
                            ProviderCooldownRegistry cooldownRegistry) {
        this.restClient = restClient;
        this.properties = properties;
        this.cooldownRegistry = cooldownRegistry;
        this.providers = buildChain(restClient, properties, properties.getChain());
        log.info("AI router chain configured: {}. Providers available for per-request keys: {}",
                providers.stream().map(AiProvider::name).toList(), LlmProviderCatalog.knownProviders());
    }

    private List<AiProvider> buildChain(RestClient restClient, RouterProperties props, List<String> names) {
        List<AiProvider> chain = new ArrayList<>();
        for (String name : names) {
            BiFunction<RestClient, RouterProperties, AiProvider> factory = REGISTRY.get(name.trim().toLowerCase());
            if (factory == null) {
                log.warn("Unknown provider '{}' in aiqa.router.chain - skipping. Known providers: {}",
                        name, REGISTRY.keySet());
                continue;
            }
            chain.add(factory.apply(restClient, props));
        }
        return chain;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        return runChain(providers, systemPrompt, userPrompt, NO_KEYS_HINT);
    }

    /** Appended to the failure message when a run had no request-supplied credentials to work with. */
    private static final String NO_KEYS_HINT =
            "No \"llmKeys\" were supplied in the request and no server-side keys are configured. "
                    + "Add the models you want used to the request body, e.g. "
                    + "\"llmKeys\": { \"gemini\": \"...\", \"groq\": \"...\" } - "
                    + "any model you leave out is treated as inactive and never called.";

    /**
     * Per-request credentials: only the providers this request activated are
     * in play. Falls back to the server-configured chain when keys is null or
     * activates nothing at all, so callers that don't care about per-request
     * credentials behave exactly as before.
     */
    @Override
    public String complete(String systemPrompt, String userPrompt, LlmKeys keys) {
        if (keys == null || keys.isEmpty()) {
            return complete(systemPrompt, userPrompt);
        }

        List<AiProvider> requestChain = buildRequestChain(keys);
        if (requestChain.isEmpty()) {
            // Only reachable when every activated name is unknown to the
            // REGISTRY - a real caller error worth failing loudly on rather
            // than silently falling back to server keys the caller was
            // deliberately overriding.
            throw new IllegalArgumentException(
                    "llmKeys was supplied but activated no usable model. Known providers: "
                            + LlmProviderCatalog.knownProviders()
                            + ". For anything not listed, use \"custom\": "
                            + "[{\"name\":..., \"baseUrl\":..., \"model\":..., \"apiKey\":...}].");
        }

        log.info("AI router (per-request keys): active provider(s) {} - all others treated as inactive and skipped.",
                requestChain.stream().map(AiProvider::name).toList());
        return runChain(requestChain, systemPrompt, userPrompt, null);
    }

    /**
     * Builds a chain from ONLY the providers this request supplied credentials
     * for. Ordering follows the configured aiqa.router.chain so a deployment's
     * preferred fallback order still applies; any activated provider that
     * isn't in the configured chain is appended afterwards, so supplying a key
     * is always sufficient to get that provider used.
     */
    private List<AiProvider> buildRequestChain(LlmKeys keys) {
        Set<String> activated = new LinkedHashSet<>(keys.activatedProviderNames());

        // Configured chain order first (so a deployment's preferred fallback
        // order still applies), then anything else the caller activated.
        List<String> ordered = new ArrayList<>();
        for (String configured : properties.getChain()) {
            String name = configured.trim().toLowerCase();
            if (activated.contains(name)) {
                ordered.add(name);
            }
        }
        for (String name : activated) {
            if (!ordered.contains(name)) {
                ordered.add(name);
            }
        }

        List<AiProvider> chain = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String name : ordered) {
            AiProvider provider = buildFromCatalog(name, keys);
            if (provider != null) {
                chain.add(provider);
            } else {
                unknown.add(name);
            }
        }

        // Caller-supplied OpenAI-compatible endpoints that aren't in the catalog.
        for (LlmKeys.CustomProvider custom : keys.getCustom()) {
            if (custom == null || custom.getBaseUrl() == null || custom.getBaseUrl().isBlank()) {
                continue;
            }
            String name = (custom.getName() == null || custom.getName().isBlank()) ? "custom" : custom.getName();
            chain.add(new CatalogOpenAiProvider(restClient, name, custom.getBaseUrl(),
                    custom.getApiKey(), custom.getModel() == null ? "" : custom.getModel()));
        }

        if (!unknown.isEmpty()) {
            log.warn("Ignoring unrecognized model name(s) in llmKeys: {}. Known providers: {}. "
                    + "For anything else, use \"custom\": [{{\"name\":..., \"baseUrl\":..., \"model\":..., \"apiKey\":...}}].",
                    unknown, LlmProviderCatalog.knownProviders());
        }
        return chain;
    }

    /**
     * Builds one provider from the catalog using the caller's key. Model ids
     * come from server config where one is set for that provider, otherwise
     * from the catalog default - a caller supplying credentials isn't also
     * choosing model ids.
     */
    private AiProvider buildFromCatalog(String name, LlmKeys keys) {
        LlmProviderCatalog.Entry entry = LlmProviderCatalog.find(name).orElse(null);
        if (entry == null) {
            return null;
        }
        String suppliedValue = keys.get(name);
        String model = configuredModelFor(name, entry.defaultModel());

        return switch (entry.wireFormat()) {
            case OPENAI_COMPAT -> new CatalogOpenAiProvider(
                    restClient, name, configuredBaseUrlFor(name, entry.baseUrl()), suppliedValue, model);
            case ANTHROPIC -> new AnthropicProvider(
                    restClient, entry.baseUrl(), suppliedValue, model);
            case GEMINI -> {
                RouterProperties p = geminiProps(suppliedValue, model);
                yield new GeminiRouterProvider(restClient, p);
            }
            case OLLAMA -> {
                // For Ollama the supplied value is a HOST, not a key.
                RouterProperties p = new RouterProperties();
                p.setOllamaHost(suppliedValue == null || suppliedValue.isBlank()
                        ? entry.baseUrl() : suppliedValue);
                p.setOllamaModel(model);
                yield new OllamaProvider(restClient, p);
            }
        };
    }

    private RouterProperties geminiProps(String apiKey, String model) {
        RouterProperties p = new RouterProperties();
        p.setGeminiApiKey(apiKey == null ? "" : apiKey);
        p.setGeminiModel(model);
        p.setGeminiBaseUrl(properties.getGeminiBaseUrl());
        return p;
    }

    /** Server-configured model override for a provider, falling back to the catalog default. */
    private String configuredModelFor(String name, String catalogDefault) {
        String configured = switch (name) {
            case "gemini" -> properties.getGeminiModel();
            case "groq" -> properties.getGroqModel();
            case "mistral" -> properties.getMistralModel();
            case "github" -> properties.getGithubModel();
            case "ollama" -> properties.getOllamaModel();
            default -> null;
        };
        return (configured == null || configured.isBlank()) ? catalogDefault : configured;
    }

    private String configuredBaseUrlFor(String name, String catalogDefault) {
        return catalogDefault;
    }

    /**
     * The shared rotation loop - identical behavior whichever chain it's handed.
     *
     * <p>Skips models currently benched by {@link ProviderCooldownRegistry}
     * (typically because they exhausted their quota on an earlier request), so
     * rotation persists across calls instead of re-trying a dead model every
     * time. If EVERY model is benched, the bench is ignored for one pass -
     * better to attempt a possibly-recovered model than to fail outright
     * while credentials are perfectly valid.
     */
    private String runChain(List<AiProvider> chain, String systemPrompt, String userPrompt, String failureHint) {
        List<String> errors = new ArrayList<>();

        boolean allBenched = chain.stream().allMatch(p -> cooldownRegistry.isCoolingDown(p.name()));
        if (allBenched && !chain.isEmpty()) {
            log.warn("Every model in the chain is cooling down - attempting them anyway rather than failing outright.");
        }

        for (AiProvider provider : chain) {
            if (!provider.available()) {
                errors.add(provider.name() + ": no key supplied");
                continue;
            }
            if (!allBenched && cooldownRegistry.isCoolingDown(provider.name())) {
                errors.add(provider.name() + ": cooling down until " + cooldownRegistry.cooldownEndsAt(provider.name()));
                log.debug("AI router: skipping '{}' - benched until {}",
                        provider.name(), cooldownRegistry.cooldownEndsAt(provider.name()));
                continue;
            }
            try {
                String text = provider.complete(systemPrompt, userPrompt, 0.2, properties.getMaxTokens());
                cooldownRegistry.recordSuccess(provider.name());
                log.info("AI router: '{}' (model={}) answered successfully.", provider.name(), provider.model());
                return text;
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                // Benches the model - a quota/rate-limit failure for a long
                // window, anything else briefly - so the next request rotates
                // straight past it instead of repeating the same dead call.
                cooldownRegistry.recordFailure(provider.name(), e);
                log.warn("AI router: model '{}' failed ({}); rotating to next.", provider.name(),
                        message.length() > 200 ? message.substring(0, 200) : message);
                errors.add(provider.name() + ": " + message);
            }
        }

        throw new AllProvidersFailedException(errors, failureHint);
    }
}
