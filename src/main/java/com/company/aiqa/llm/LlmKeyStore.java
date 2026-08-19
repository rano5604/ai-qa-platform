package com.company.aiqa.llm;

import com.company.aiqa.ai.router.LlmProviderCatalog;
import com.company.aiqa.model.LlmKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The LLM credentials configured once and reused by every generation call.
 *
 * <p>Without this every request has to carry its own keys, so the same secrets
 * end up pasted into scripts, CI jobs and shell history over and over. Setting
 * them once and referring to them by nothing at all is both safer and less
 * tedious.
 *
 * <p><b>Kept in memory only, on purpose.</b> Writing them to disk would put
 * live API keys in a file nobody remembers to protect, and this process already
 * has everything it needs while it runs. The cost is that they must be set
 * again after a restart, which is the right trade for a credential.
 *
 * <p>Values are never returned by any endpoint and never logged - not even at
 * debug. Callers can ask WHICH providers are configured, never what their keys
 * are.
 */
@Service
public class LlmKeyStore {

    private static final Logger log = LoggerFactory.getLogger(LlmKeyStore.class);

    /** Guarded by {@code this}; read far more often than written. */
    private volatile LlmKeys configured = new LlmKeys();

    /**
     * Replaces the stored credentials wholesale.
     *
     * <p>Replace rather than merge: a caller sending a set of keys is stating
     * what should be active, and silently keeping a provider they left out
     * would mean a model they thought they had removed still gets called.
     *
     * @return the provider names now active
     */
    public synchronized List<String> configure(LlmKeys keys) {
        LlmKeys replacement = keys == null ? new LlmKeys() : keys;
        this.configured = replacement;
        List<String> active = activeProviders();
        log.info("LLM keys configured for provider(s): {} (values are never logged or returned).", active);
        return active;
    }

    /** Forgets every stored credential. */
    public synchronized void clear() {
        this.configured = new LlmKeys();
        log.info("Stored LLM keys cleared.");
    }

    /** True when at least one usable credential is held. */
    public boolean isConfigured() {
        return !configured.isEmpty();
    }

    /** The stored keys, or empty when none are set. Never null. */
    public LlmKeys current() {
        return configured;
    }

    /**
     * The keys a request should actually run with.
     *
     * <p>A request that carries its own keys wins - that is an explicit
     * per-call override, useful for pinning one run to one model or billing it
     * to a particular key. Otherwise the stored set applies.
     */
    public LlmKeys resolveFor(LlmKeys requestKeys) {
        if (requestKeys != null && !requestKeys.isEmpty()) {
            return requestKeys;
        }
        return configured;
    }

    /** Provider names currently active, including any custom entries. */
    public List<String> activeProviders() {
        List<String> names = new ArrayList<>(configured.activatedProviderNames());
        configured.getCustom().stream()
                .map(LlmKeys.CustomProvider::getName)
                .filter(n -> n != null && !n.isBlank())
                .forEach(names::add);
        return names;
    }

    /**
     * Provider name -> a masked hint, so a caller can confirm WHICH key is
     * stored without the value being recoverable. Shows only the last four
     * characters, which is enough to tell two keys apart and useless on its own.
     */
    public Map<String, String> maskedSummary() {
        Map<String, String> masked = new LinkedHashMap<>();
        configured.getKeys().forEach((provider, value) -> masked.put(provider, mask(value)));
        configured.getCustom().forEach(custom -> {
            if (custom.getName() != null && !custom.getName().isBlank()) {
                masked.put(custom.getName(), mask(custom.getApiKey()));
            }
        });
        return masked;
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) {
            return "(none)";
        }
        String trimmed = value.trim();
        return trimmed.length() <= 4 ? "****" : "****" + trimmed.substring(trimmed.length() - 4);
    }

    /** Provider names this build understands, for a caller building a request. */
    public List<String> knownProviders() {
        return new ArrayList<>(LlmProviderCatalog.knownProviders());
    }

    /** Rejects a provider name the catalog does not know, before it silently never runs. */
    public Optional<String> firstUnknownProvider(LlmKeys keys) {
        if (keys == null) {
            return Optional.empty();
        }
        return keys.getKeys().keySet().stream()
                .filter(name -> LlmProviderCatalog.find(name).isEmpty())
                .findFirst();
    }
}
