package com.company.aiqa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds the "aiqa.router" section of application.yml - the Java
 * counterpart to ai_router.config.Config from the ai-router Python package
 * this was ported from. Only used when aiqa.llm.provider=router.
 *
 * The router tries each provider in "chain" order and falls through to the
 * next on any failure (missing key, network error, rate limit, bad
 * response) - see AiRouterService. You don't need every key filled in:
 * a provider with no key configured is simply skipped (available()=false).
 */
@ConfigurationProperties(prefix = "aiqa.router")
public class RouterProperties {

    /** Order to try providers in - first that succeeds wins. Unknown names are skipped with a warning. */
    private List<String> chain = new ArrayList<>(List.of("gemini", "groq", "mistral", "github", "ollama"));

    /** Per-request timeout, applied to every provider. */
    private int timeoutSeconds = 60;

    /** Max tokens requested per completion - PromptBuilder's JSON-array responses can run long, so this defaults higher than ai-router's own 1024. */
    private int maxTokens = 4096;

    // --- Gemini --- https://aistudio.google.com/app/apikey
    private String geminiApiKey = "";
    private String geminiModel = "gemini-2.5-flash";
    private String geminiBaseUrl = "https://generativelanguage.googleapis.com/v1beta";

    // --- Groq --- https://console.groq.com/keys
    private String groqApiKey = "";
    private String groqModel = "llama-3.3-70b-versatile";

    // --- Mistral --- https://console.mistral.ai/api-keys
    private String mistralApiKey = "";
    private String mistralModel = "mistral-large-latest";

    // --- GitHub Models --- a GitHub personal-access token, https://github.com/settings/tokens
    private String githubToken = "";
    private String githubModel = "openai/gpt-4o-mini";

    // --- Ollama (local, no key) --- install from https://ollama.com and run `ollama serve`
    private String ollamaHost = "http://localhost:11434";
    private String ollamaModel = "qwen2.5-coder:7b";

    public List<String> getChain() { return chain; }
    public void setChain(List<String> chain) { this.chain = chain; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public String getGeminiApiKey() { return geminiApiKey; }
    public void setGeminiApiKey(String geminiApiKey) { this.geminiApiKey = geminiApiKey; }

    public String getGeminiModel() { return geminiModel; }
    public void setGeminiModel(String geminiModel) { this.geminiModel = geminiModel; }

    public String getGeminiBaseUrl() { return geminiBaseUrl; }
    public void setGeminiBaseUrl(String geminiBaseUrl) { this.geminiBaseUrl = geminiBaseUrl; }

    public String getGroqApiKey() { return groqApiKey; }
    public void setGroqApiKey(String groqApiKey) { this.groqApiKey = groqApiKey; }

    public String getGroqModel() { return groqModel; }
    public void setGroqModel(String groqModel) { this.groqModel = groqModel; }

    public String getMistralApiKey() { return mistralApiKey; }
    public void setMistralApiKey(String mistralApiKey) { this.mistralApiKey = mistralApiKey; }

    public String getMistralModel() { return mistralModel; }
    public void setMistralModel(String mistralModel) { this.mistralModel = mistralModel; }

    public String getGithubToken() { return githubToken; }
    public void setGithubToken(String githubToken) { this.githubToken = githubToken; }

    public String getGithubModel() { return githubModel; }
    public void setGithubModel(String githubModel) { this.githubModel = githubModel; }

    public String getOllamaHost() { return ollamaHost; }
    public void setOllamaHost(String ollamaHost) { this.ollamaHost = ollamaHost; }

    public String getOllamaModel() { return ollamaModel; }
    public void setOllamaModel(String ollamaModel) { this.ollamaModel = ollamaModel; }
}
