package com.company.aiqa.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Binds "aiqa.llm.provider" - which LlmClient implementation is active.
 * Valid values: "openai", "gemini", or "router" (tries every candidate
 * configured under aiqa.router.chain in order, falling through to the next
 * whenever the current one is unconfigured or fails - see
 * ai.router.AiRouterService).
 *
 * <p>There is deliberately NO default. The field used to default to "openai",
 * which was harmless on its own - nothing injects this class - but it made the
 * documentation and the wiring disagree, and the wiring was what mattered:
 * OpenAIService carried {@code matchIfMissing = true}, so an unresolvable
 * property silently selected OpenAI rather than failing. The validation below
 * turns the two cases that produced that outcome into a startup failure that
 * names the cause.
 */
@ConfigurationProperties(prefix = "aiqa.llm")
public class LlmProperties {

    /** The implementations that have a bean and a @ConditionalOnProperty match. */
    static final List<String> VALID_PROVIDERS = List.of("openai", "gemini", "router");

    private String provider = "";

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    /**
     * Fails startup when the property is missing or names something no bean
     * matches.
     *
     * <p>Without this the same two mistakes both end as Spring's generic
     * "required a bean of type 'LlmClient' that could not be found", which is
     * true but says nothing about which of the two happened. The blank case in
     * particular is almost never a deliberate choice - it means the
     * configuration file was not on the classpath at all, and the message says
     * so, because that is the part nobody thinks to check. A run in that state
     * looks entirely normal in the logs until the first LLM call.
     */
    @PostConstruct
    void validate() {
        validateProvider(provider);
    }

    /**
     * Static so it can run from a BeanFactoryPostProcessor, before any bean
     * that injects LlmClient is created.
     *
     * <p>That ordering is the whole point and it was wrong on the first
     * attempt: with the check living only in {@code @PostConstruct}, startup
     * still failed - but Spring got there first, through
     * LlmClientStartupReport's own constructor, and reported "No qualifying
     * bean of type 'LlmClient' available". True, generic, and silent about
     * which of the two causes applied. A check that fires after the generic
     * failure adds nothing.
     */
    static void validateProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalStateException(
                    "aiqa.llm.provider is not set, so no LLM client can be selected. "
                            + "Set it to one of " + VALID_PROVIDERS + ". "
                            + "If you did set it in application.yml, that file is most likely not on the "
                            + "classpath - check that target/classes/application.yml exists "
                            + "(mvn process-resources recreates it) and restart.");
        }
        String normalized = provider.trim().toLowerCase();
        if (!VALID_PROVIDERS.contains(normalized)) {
            throw new IllegalStateException(
                    "aiqa.llm.provider is '" + provider + "', which matches no LlmClient implementation. "
                            + "Valid values are " + VALID_PROVIDERS + ". "
                            + "Note that a provider name such as 'groq' or 'cerebras' is a chain ENTRY for "
                            + "aiqa.router.chain, not a value for this property - use 'router' and add the "
                            + "name to the chain.");
        }
    }
}
