package com.bot.bot.llm;

import com.bot.bot.config.LLMProperties;
import com.bot.bot.config.ProviderConfig;
import com.google.gson.Gson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LLMFallbackChainExtendedTest {

    @Test
    @DisplayName("Returns error when no providers are configured")
    void errorsWhenNoProvidersConfigured() {
        LLMProperties props = new LLMProperties();
        props.setProviders(Collections.emptyList());

        LLMFallbackChain chain = new LLMFallbackChain(props, mock(WebClient.class), new Gson());
        chain.afterPropertiesSet();

        StepVerifier.create(chain.generateCodeReview("test diff"))
                .expectErrorMatches(err -> err instanceof IllegalStateException
                        && err.getMessage().contains("No LLM providers configured"))
                .verify();
    }

    @Test
    @DisplayName("Primary provider succeeds and does not invoke fallback")
    void primaryProviderSucceeds() {
        LLMProperties props = new LLMProperties();
        ProviderConfig p1 = new ProviderConfig();
        p1.setName("primary");
        ProviderConfig p2 = new ProviderConfig();
        p2.setName("fallback");
        props.setProviders(List.of(p1, p2));

        LLMFallbackChain chain = new LLMFallbackChain(props, mock(WebClient.class), new Gson());
        OpenAiCompatibleClient mockP1 = mock(OpenAiCompatibleClient.class);
        OpenAiCompatibleClient mockP2 = mock(OpenAiCompatibleClient.class);

        when(mockP1.generateCodeReview("diff")).thenReturn(Mono.just("Primary review: LGTM"));
        when(mockP2.generateCodeReview(anyString())).thenReturn(Mono.just("Fallback unused"));

        chain.delegates.add(mockP1);
        chain.delegates.add(mockP2);

        StepVerifier.create(chain.generateCodeReview("diff"))
                .expectNext("Primary review: LGTM")
                .verifyComplete();

        verify(mockP1).generateCodeReview("diff");
    }

    @Test
    @DisplayName("Primary provider fails, secondary provider succeeds")
    void primaryFailsSecondarySucceeds() {
        LLMProperties props = new LLMProperties();
        ProviderConfig p1 = new ProviderConfig();
        p1.setName("p1");
        ProviderConfig p2 = new ProviderConfig();
        p2.setName("p2");
        props.setProviders(List.of(p1, p2));

        LLMFallbackChain chain = new LLMFallbackChain(props, mock(WebClient.class), new Gson());
        OpenAiCompatibleClient mockP1 = mock(OpenAiCompatibleClient.class);
        OpenAiCompatibleClient mockP2 = mock(OpenAiCompatibleClient.class);

        when(mockP1.generateCodeReview("diff")).thenReturn(Mono.error(new RuntimeException("P1 500 Server Error")));
        when(mockP2.generateCodeReview("diff")).thenReturn(Mono.just("Secondary review: looks good"));

        chain.delegates.add(mockP1);
        chain.delegates.add(mockP2);

        StepVerifier.create(chain.generateCodeReview("diff"))
                .expectNext("Secondary review: looks good")
                .verifyComplete();

        verify(mockP1).generateCodeReview("diff");
        verify(mockP2).generateCodeReview("diff");
    }

    @Test
    @DisplayName("All providers fail, returns error naming all attempted providers")
    void allProvidersFail() {
        LLMProperties props = new LLMProperties();
        ProviderConfig p1 = new ProviderConfig();
        p1.setName("prov1");
        ProviderConfig p2 = new ProviderConfig();
        p2.setName("prov2");
        props.setProviders(List.of(p1, p2));

        LLMFallbackChain chain = new LLMFallbackChain(props, mock(WebClient.class), new Gson());
        OpenAiCompatibleClient mockP1 = mock(OpenAiCompatibleClient.class);
        OpenAiCompatibleClient mockP2 = mock(OpenAiCompatibleClient.class);

        when(mockP1.generateCodeReview("diff")).thenReturn(Mono.error(new RuntimeException("P1 failed")));
        when(mockP2.generateCodeReview("diff")).thenReturn(Mono.error(new RuntimeException("P2 failed")));

        chain.delegates.add(mockP1);
        chain.delegates.add(mockP2);

        StepVerifier.create(chain.generateCodeReview("diff"))
                .expectErrorMatches(err -> err instanceof IllegalStateException
                        && err.getMessage().contains("All LLM providers failed: [prov1, prov2]"))
                .verify();
    }
}
