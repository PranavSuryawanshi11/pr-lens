package com.bot.bot.health;

import com.bot.bot.config.LLMProperties;
import com.bot.bot.config.ProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LLMHealthIndicatorTest {

    private LLMProperties llmProperties;

    @BeforeEach
    void setUp() {
        llmProperties = new LLMProperties();
        llmProperties.setEnabled(true);
        llmProperties.setProviders(new ArrayList<>());
    }

    @Test
    @DisplayName("Health is UP when LLM is disabled by configuration")
    void healthUpWhenLlmDisabled() {
        llmProperties.setEnabled(false);

        WebClient webClient = WebClient.builder().build();
        LLMHealthIndicator indicator = new LLMHealthIndicator(llmProperties, webClient);
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("LLM disabled by configuration", health.getDetails().get("status"));
    }

    @Test
    @DisplayName("Health is DOWN when providers list is empty")
    void healthDownWhenNoProviders() {
        llmProperties.setEnabled(true);
        llmProperties.setProviders(Collections.emptyList());

        WebClient webClient = WebClient.builder().build();
        LLMHealthIndicator indicator = new LLMHealthIndicator(llmProperties, webClient);
        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("No LLM providers configured", health.getDetails().get("status"));
    }

    @Test
    @DisplayName("Health is DOWN when providers list is null")
    void healthDownWhenProvidersNull() {
        llmProperties.setEnabled(true);
        llmProperties.setProviders(null);

        WebClient webClient = WebClient.builder().build();
        LLMHealthIndicator indicator = new LLMHealthIndicator(llmProperties, webClient);
        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("No LLM providers configured", health.getDetails().get("status"));
    }

    @Test
    @DisplayName("Health is UP (reachable) when provider responds to /v1/models with API key")
    void healthUpReachableWithApiKey() {
        ProviderConfig provider = new ProviderConfig();
        provider.setName("openai");
        provider.setBaseUrl("https://api.openai.com");
        provider.setApiKey("test-key-123");
        llmProperties.setProviders(List.of(provider));

        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    assertEquals("https://api.openai.com/v1/models", request.url().toString());
                    assertEquals("Bearer test-key-123", request.headers().getFirst("Authorization"));
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .body("{\"data\": []}")
                            .build());
                })
                .build();

        LLMHealthIndicator indicator = new LLMHealthIndicator(llmProperties, webClient);
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("openai", health.getDetails().get("provider"));
        assertEquals("reachable", health.getDetails().get("status"));
    }

    @Test
    @DisplayName("Appends /models correctly when baseUrl already ends with /v1")
    void handlesBaseUrlEndingWithV1() {
        ProviderConfig provider = new ProviderConfig();
        provider.setName("local-nim");
        provider.setBaseUrl("http://localhost:8000/v1/");
        llmProperties.setProviders(List.of(provider));

        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    assertEquals("http://localhost:8000/v1/models", request.url().toString());
                    return Mono.just(ClientResponse.create(HttpStatus.OK).body("{}").build());
                })
                .build();

        LLMHealthIndicator indicator = new LLMHealthIndicator(llmProperties, webClient);
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("reachable", health.getDetails().get("status"));
    }

    @Test
    @DisplayName("Health is UP with heuristics-fallback mode when provider is unreachable/offline")
    void healthUpWithHeuristicsFallbackWhenOffline() {
        ProviderConfig provider = new ProviderConfig();
        provider.setName("ollama");
        provider.setBaseUrl("http://localhost:11434");
        llmProperties.setProviders(List.of(provider));

        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.error(new RuntimeException("Connection refused")))
                .build();

        LLMHealthIndicator indicator = new LLMHealthIndicator(llmProperties, webClient);
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("ollama", health.getDetails().get("provider"));
        assertEquals("offline", health.getDetails().get("status"));
        assertEquals("heuristics-fallback", health.getDetails().get("mode"));
    }
}
