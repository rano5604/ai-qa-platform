package com.company.aiqa.ai;

import com.company.aiqa.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Says, in the first second of every startup, which LLM client is actually
 * wired and whether it can call anything.
 *
 * <p>Written after a session lost to reading a healthy-looking log. The server
 * had booted with no application.yml on the classpath; PipelineProperties
 * supplies the same defaults the yml does, so the output directory, the batch
 * size and the category list were all identical to a working run. The only
 * difference was which LlmClient existed, and nothing said. The mistake was
 * found by inspecting the running process's classpath, not by reading its logs.
 *
 * <p>Runs on ApplicationReadyEvent, so it reports only on a startup that
 * succeeded - a misconfigured provider fails earlier, in LlmProperties.
 */
@Component
public class LlmClientStartupReport {

    private static final Logger log = LoggerFactory.getLogger(LlmClientStartupReport.class);

    private final LlmClient llmClient;
    private final LlmProperties llmProperties;

    public LlmClientStartupReport(LlmClient llmClient, LlmProperties llmProperties) {
        this.llmClient = llmClient;
        this.llmProperties = llmProperties;
    }

    /**
     * Never logs a key or any part of one - only whether one is present.
     * API keys have already leaked through screenshots of this platform's
     * output once; a log line is a worse leak, because it is kept.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        boolean usable = llmClient.hasServerSideCredentials();
        log.info("LLM client: {} (aiqa.llm.provider={}), server-side credentials {}.",
                llmClient.getClass().getSimpleName(), llmProperties.getProvider(),
                usable ? "present" : "ABSENT");

        if (!usable) {
            log.warn("No server-side LLM credentials are configured, so every generation request will be "
                    + "refused up front unless it carries its own \"llmKeys\". Set the provider's key "
                    + "environment variable and restart, or configure keys once with POST /api/v1/llm-keys.");
        }
    }
}
