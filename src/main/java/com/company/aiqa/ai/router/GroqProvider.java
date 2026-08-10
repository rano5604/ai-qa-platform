package com.company.aiqa.ai.router;

import com.company.aiqa.config.RouterProperties;
import org.springframework.web.client.RestClient;

/** Groq's OpenAI-compatible /chat/completions endpoint - see ai_router.providers.GroqProvider. */
public class GroqProvider extends AbstractOpenAiCompatProvider {

    private static final String ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";

    public GroqProvider(RestClient restClient, RouterProperties props) {
        super(restClient, ENDPOINT, props.getGroqApiKey(), props.getGroqModel());
    }

    @Override
    public String name() { return "groq"; }
}
