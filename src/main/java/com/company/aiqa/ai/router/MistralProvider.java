package com.company.aiqa.ai.router;

import com.company.aiqa.config.RouterProperties;
import org.springframework.web.client.RestClient;

/** Mistral's OpenAI-compatible /chat/completions endpoint - see ai_router.providers.MistralProvider. */
public class MistralProvider extends AbstractOpenAiCompatProvider {

    private static final String ENDPOINT = "https://api.mistral.ai/v1/chat/completions";

    public MistralProvider(RestClient restClient, RouterProperties props) {
        super(restClient, ENDPOINT, props.getMistralApiKey(), props.getMistralModel());
    }

    @Override
    public String name() { return "mistral"; }
}
