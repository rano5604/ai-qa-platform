package com.company.aiqa.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties({OpenAIProperties.class, PipelineProperties.class, GitHubProperties.class,
        GitLabProperties.class, GitProperties.class, LlmProperties.class, GeminiProperties.class,
        RouterProperties.class, com.company.aiqa.replay.ReplayProperties.class,
        com.company.aiqa.openapi.OpenApiProperties.class})
public class AppConfig {

    /**
     * Checks aiqa.llm.provider before anything is wired, so a misconfigured
     * process says why instead of reporting the symptom.
     *
     * <p>static and a BeanFactoryPostProcessor for one reason: ordering. Every
     * later mechanism loses the race. A {@code @PostConstruct} on LlmProperties
     * fires only when that bean is built, by which time LlmClientStartupReport
     * or QaPipelineService has already failed to inject LlmClient and Spring
     * has reported "No qualifying bean of type 'LlmClient' available" - which
     * says nothing about whether the property was mistyped or the whole
     * configuration file was missing from the classpath. A BFPP runs before any
     * regular bean is instantiated, so this message is the one the reader sees.
     */
    @Bean
    public static BeanFactoryPostProcessor llmProviderValidator(Environment environment) {
        return beanFactory -> LlmProperties.validateProvider(environment.getProperty("aiqa.llm.provider"));
    }

    @Bean
    public RestClient restClient() {
        // A short CONNECT timeout matters a lot for AiRouterService specifically:
        // if a provider's host is unreachable (network-blocked, DNS failure,
        // firewall silently dropping packets), the JVM's default behavior is to
        // hang for ~40s before giving up - multiplied across several candidates
        // in the chain, that's minutes of dead time before falling through to
        // one that actually works. 10s is enough for any real TCP handshake and
        // fails fast on a genuinely unreachable host. READ timeout stays
        // generous (90s) since a real, slow LLM response (long prompt, thinking
        // mode) shouldn't be mistaken for a hung connection.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(90));

        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
