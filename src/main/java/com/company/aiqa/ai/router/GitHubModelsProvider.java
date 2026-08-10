package com.company.aiqa.ai.router;

import com.company.aiqa.config.RouterProperties;
import org.springframework.web.client.RestClient;

/**
 * GitHub Models' OpenAI-compatible /chat/completions endpoint, authenticated
 * with a GitHub personal-access token - see ai_router.providers.GitHubModelsProvider.
 */
public class GitHubModelsProvider extends AbstractOpenAiCompatProvider {

    private static final String ENDPOINT = "https://models.github.ai/inference/chat/completions";

    public GitHubModelsProvider(RestClient restClient, RouterProperties props) {
        super(restClient, ENDPOINT, props.getGithubToken(), props.getGithubModel());
    }

    @Override
    public String name() { return "github"; }
}
