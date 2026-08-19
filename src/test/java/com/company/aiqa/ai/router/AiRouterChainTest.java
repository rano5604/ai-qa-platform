package com.company.aiqa.ai.router;

import com.company.aiqa.config.RouterProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chain used to accept only the five providers with dedicated properties,
 * so naming any other catalog provider - cerebras, deepseek, openrouter - got
 * it dropped with a warning at startup and no credential path at all.
 */
class AiRouterChainTest {

    private AiRouterService router(RouterProperties props) {
        return new AiRouterService(RestClient.create(), props, new ProviderCooldownRegistry());
    }

    @Test
    void aCatalogProviderInTheChainIsUsable() {
        RouterProperties props = new RouterProperties();
        props.setChain(List.of("cerebras"));
        props.setKeys(Map.of("cerebras", "csk-test-key"));

        assertTrue(router(props).hasServerSideCredentials());
    }

    @Test
    void aCatalogProviderWithNoKeyIsInactiveRatherThanFatal() {
        RouterProperties props = new RouterProperties();
        props.setChain(List.of("cerebras"));

        assertFalse(router(props).hasServerSideCredentials());
    }

    @Test
    void aNameTheCatalogDoesNotKnowIsStillSkipped() {
        RouterProperties props = new RouterProperties();
        props.setChain(List.of("not-a-real-provider"));
        props.setKeys(Map.of("not-a-real-provider", "x"));

        assertFalse(router(props).hasServerSideCredentials());
    }

    @Test
    void theDedicatedPropertiesStillWork() {
        RouterProperties props = new RouterProperties();
        props.setChain(List.of("groq"));
        props.setGroqApiKey("gsk-test-key");

        assertTrue(router(props).hasServerSideCredentials());
    }
}
