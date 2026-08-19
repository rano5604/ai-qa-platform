package com.company.aiqa.controller;

import com.company.aiqa.llm.LlmKeyStore;
import com.company.aiqa.model.LlmKeys;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configure the LLM credentials once, then leave them out of every generation
 * request.
 *
 * <p>Keys live in memory for the lifetime of the process and are never written
 * to disk, echoed back, or logged. Reading them back is deliberately impossible
 * - you can ask which providers are configured, not what their keys are.
 */
@RestController
@RequestMapping("/api/v1/llm-keys")
public class LlmKeyController {

    private final LlmKeyStore keyStore;

    public LlmKeyController(LlmKeyStore keyStore) {
        this.keyStore = keyStore;
    }

    /**
     * Sets the credentials used by /generate-tests,
     * /generate-tests-from-branch, /generate-tests-from-branch/backfill and
     * /generate-automation.
     *
     * <p>Any provider in the catalog is accepted - gemini, groq, mistral,
     * openrouter, deepseek, together, cerebras, openai, anthropic, and the rest
     * - plus "custom" entries for anything OpenAI-compatible that isn't listed.
     * GET this endpoint to see the full set of names.
     *
     * <p>This REPLACES what was there: send the complete set you want active,
     * since a provider you leave out is switched off rather than kept.
     *
     * Example:
     * POST /api/v1/llm-keys
     * {
     *   "gemini": "AIza...",
     *   "mistral": "...",
     *   "openrouter": "sk-or-...",
     *   "custom": [
     *     { "name": "my-llm", "baseUrl": "https://llm.internal/v1", "model": "qwen2.5", "apiKey": "..." }
     *   ]
     * }
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> configure(@RequestBody LlmKeys keys) {
        // Catch a typo now: an unrecognised name would otherwise be stored,
        // never matched by the router, and look like a provider that simply
        // never answers.
        var unknown = keyStore.firstUnknownProvider(keys);
        if (unknown.isPresent()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "Unknown provider '" + unknown.get() + "'.");
            body.put("knownProviders", keyStore.knownProviders());
            body.put("hint", "Use one of knownProviders, or add it under \"custom\" with its baseUrl and model.");
            return ResponseEntity.badRequest().body(body);
        }

        // Validate BEFORE storing. configure() replaces the whole set, so doing
        // this the other way round meant an empty or malformed body wiped the
        // working keys and only then returned an error - a request that failed
        // and still broke everything after it.
        if (keys == null || keys.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "No usable keys were supplied - every value was empty.");
            body.put("knownProviders", keyStore.knownProviders());
            body.put("hint", "Existing keys were left untouched. Use DELETE to clear them deliberately.");
            return ResponseEntity.badRequest().body(body);
        }

        var active = keyStore.configure(keys);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configured", true);
        body.put("activeProviders", active);
        body.put("keys", keyStore.maskedSummary());
        body.put("note", "Stored in memory only - set these again after a restart. "
                + "Generation requests no longer need an \"llmKeys\" field.");
        return ResponseEntity.ok(body);
    }

    /** Which providers are configured, masked - never the key values. */
    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configured", keyStore.isConfigured());
        body.put("activeProviders", keyStore.activeProviders());
        body.put("keys", keyStore.maskedSummary());
        body.put("knownProviders", keyStore.knownProviders());
        return body;
    }

    /** Forgets every stored key. Generation then fails until they are set again. */
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> clear() {
        keyStore.clear();
        return ResponseEntity.status(HttpStatus.OK)
                .body(Map.of("configured", false, "activeProviders", java.util.List.of()));
    }
}
