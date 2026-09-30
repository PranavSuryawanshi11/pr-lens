package com.bot.bot.github;

import com.bot.bot.config.GitHubProperties;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class GitHubApiClientTokenTest {

    @Test
    void resolveAuthTokenUsesPersonalAccessTokenWithoutInstallationId() {
        GitHubProperties props = new GitHubProperties();
        props.setToken("ghp_secret_token_123");
        GitHubJwtGenerator jwt = mock(GitHubJwtGenerator.class);

        GitHubApiClient client = new GitHubApiClient(props, jwt, WebClient.builder().build(), new Gson());

        StepVerifier.create(client.resolveAuthToken(0))
                .assertNext(token -> assertEquals("ghp_secret_token_123", token))
                .verifyComplete();
    }

    @Test
    void propertiesDetectTokenAndAppCredentialsCorrectly() {
        GitHubProperties props = new GitHubProperties();
        assertEquals(false, props.hasToken());
        assertEquals(false, props.hasAppCredentials());

        props.setToken("test_token");
        assertEquals(true, props.hasToken());
        assertEquals(false, props.hasAppCredentials());
    }
}
