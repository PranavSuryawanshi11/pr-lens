package com.bot.bot.health;

import com.bot.bot.config.GitHubProperties;
import com.bot.bot.github.GitHubJwtGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitHubApiHealthIndicatorTest {

    private GitHubProperties gitHubProperties;
    private GitHubJwtGenerator jwtGenerator;

    @BeforeEach
    void setUp() {
        gitHubProperties = new GitHubProperties();
        jwtGenerator = mock(GitHubJwtGenerator.class);
    }

    @Test
    @DisplayName("Health is UP when GitHub App mode is configured and API returns app id")
    void healthUpGitHubAppMode(@TempDir Path tempDir) throws IOException {
        Path keyFile = tempDir.resolve("key.pem");
        Files.writeString(keyFile, "key");

        gitHubProperties.setAppId("12345");
        gitHubProperties.setPrivateKeyPath(keyFile.toString());
        gitHubProperties.setApiUrl("https://api.github.com");

        when(jwtGenerator.generateAppToken()).thenReturn("mock-jwt-token");

        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    assertEquals("Bearer mock-jwt-token", request.headers().getFirst("Authorization"));
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body("{\"id\": 12345, \"name\": \"PR Triage Bot\"}")
                            .build());
                })
                .build();

        GitHubApiHealthIndicator indicator = new GitHubApiHealthIndicator(gitHubProperties, jwtGenerator, webClient);
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("GitHub App", health.getDetails().get("mode"));
        assertEquals("GitHub App authenticated successfully", health.getDetails().get("status"));
    }

    @Test
    @DisplayName("Health is DOWN when GitHub App response is malformed or missing id")
    void healthDownGitHubAppMalformedResponse(@TempDir Path tempDir) throws IOException {
        Path keyFile = tempDir.resolve("key.pem");
        Files.writeString(keyFile, "key");

        gitHubProperties.setAppId("12345");
        gitHubProperties.setPrivateKeyPath(keyFile.toString());

        when(jwtGenerator.generateAppToken()).thenReturn("mock-jwt");

        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .body("{\"message\": \"Not Found\"}")
                        .build()))
                .build();

        GitHubApiHealthIndicator indicator = new GitHubApiHealthIndicator(gitHubProperties, jwtGenerator, webClient);
        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("Unexpected response format", health.getDetails().get("reason"));
    }

    @Test
    @DisplayName("Health is UP when Personal Access Token is configured and /user returns login")
    void healthUpPatMode() {
        gitHubProperties.setToken("ghp_validToken12345");
        gitHubProperties.setApiUrl("https://api.github.com");

        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    assertEquals("Bearer ghp_validToken12345", request.headers().getFirst("Authorization"));
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .body("{\"login\": \"octocat\", \"id\": 1}")
                            .build());
                })
                .build();

        GitHubApiHealthIndicator indicator = new GitHubApiHealthIndicator(gitHubProperties, jwtGenerator, webClient);
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("Personal Access Token", health.getDetails().get("mode"));
        assertEquals("Token authenticated successfully", health.getDetails().get("status"));
    }

    @Test
    @DisplayName("Health is DOWN when Personal Access Token returns response without login")
    void healthDownPatModeMissingLogin() {
        gitHubProperties.setToken("ghp_invalid");

        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .body("{\"error\": \"Bad credentials\"}")
                        .build()))
                .build();

        GitHubApiHealthIndicator indicator = new GitHubApiHealthIndicator(gitHubProperties, jwtGenerator, webClient);
        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("Unexpected response format", health.getDetails().get("reason"));
    }

    @Test
    @DisplayName("Health is UP when in public tokenless mode and /zen returns zen text")
    void healthUpTokenlessMode() {
        gitHubProperties.setToken(null);
        gitHubProperties.setAppId(null);
        gitHubProperties.setApiUrl("https://api.github.com");

        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .body("Favor focus over features.")
                        .build()))
                .build();

        GitHubApiHealthIndicator indicator = new GitHubApiHealthIndicator(gitHubProperties, jwtGenerator, webClient);
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("Public GitHub API (tokenless)", health.getDetails().get("mode"));
        assertEquals("GitHub API reachable", health.getDetails().get("status"));
    }

    @Test
    @DisplayName("Health is DOWN when public tokenless mode returns blank response")
    void healthDownTokenlessModeBlankResponse() {
        gitHubProperties.setToken(null);
        gitHubProperties.setAppId(null);

        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .body("   ")
                        .build()))
                .build();

        GitHubApiHealthIndicator indicator = new GitHubApiHealthIndicator(gitHubProperties, jwtGenerator, webClient);
        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("Unexpected response format", health.getDetails().get("reason"));
    }

    @Test
    @DisplayName("Health is DOWN when network exception occurs")
    void healthDownOnNetworkException() {
        gitHubProperties.setToken("ghp_tok");

        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.error(new RuntimeException("Connection refused")))
                .build();

        GitHubApiHealthIndicator indicator = new GitHubApiHealthIndicator(gitHubProperties, jwtGenerator, webClient);
        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertTrue(health.getDetails().get("error").toString().contains("Connection refused"));
    }
}
