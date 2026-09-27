package com.mbworldwideapps.aiorchestration.config;

import java.net.URI;
import java.time.Duration;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
@Profile({ "prod", "linux-on-prem" })
public class OllamaPreflight {

    private final AiOrchestrationProperties properties;
    private final RestTemplate restTemplate;
    private final URI tagsUri;

    public OllamaPreflight(AiOrchestrationProperties properties, RestTemplateBuilder restTemplateBuilder,
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl) {
        this.properties = properties;
        this.restTemplate = restTemplateBuilder
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(5))
                .build();
        this.tagsUri = URI.create(baseUrl.replaceAll("/+$", "") + "/api/tags");
    }

    @PostConstruct
    void verifyOllamaIsReachable() {
        if (!properties.ollama().preflightEnabled()) {
            return;
        }
        try {
            restTemplate.getForObject(tagsUri, String.class);
        } catch (RestClientException e) {
            throw new IllegalStateException("Ollama preflight failed for " + tagsUri
                    + ". Start host-native Ollama or set OLLAMA_BASE_URL.", e);
        }
    }
}
