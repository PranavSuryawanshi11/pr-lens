package com.bot.bot.health;

import com.bot.bot.config.GitHubProperties;
import com.bot.bot.github.GitHubJwtGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Probes the GitHub API using the App JWT to verify:
 * <ul>
 *   <li>Network connectivity to api.github.com</li>
 *   <li>JWT generator produces valid tokens</li>
 *   <li>GitHub App credentials are valid</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitHubApiHealthIndicator implements HealthIndicator {

    private final GitHubProperties gitHubProperties;
    private final GitHubJwtGenerator jwtGenerator;
    private final WebClient webClient;

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Override
    public Health health() {
        try {
            if (gitHubProperties.hasAppCredentials()) {
                String jwt = jwtGenerator.generateAppToken();
                String url = gitHubProperties.getApiUrl() + "/app";

                String response = webClient.get()
                        .uri(url)
                        .header("Authorization", "Bearer " + jwt)
                        .header("Accept", "application/vnd.github.v3+json")
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(TIMEOUT)
                        .block();

                if (response != null && response.contains("\"id\"")) {
                    return Health.up()
                            .withDetail("apiUrl", gitHubProperties.getApiUrl())
                            .withDetail("mode", "GitHub App")
                            .withDetail("status", "GitHub App authenticated successfully")
                            .build();
                }
            } else if (gitHubProperties.hasToken()) {
                String url = gitHubProperties.getApiUrl() + "/user";

                String response = webClient.get()
                        .uri(url)
                        .header("Authorization", "Bearer " + gitHubProperties.getToken().trim())
                        .header("Accept", "application/vnd.github.v3+json")
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(TIMEOUT)
                        .block();

                if (response != null && response.contains("\"login\"")) {
                    return Health.up()
                            .withDetail("apiUrl", gitHubProperties.getApiUrl())
                            .withDetail("mode", "Personal Access Token")
                            .withDetail("status", "Token authenticated successfully")
                            .build();
                }
            } else {
                // Public / tokenless mode: verify GitHub API is reachable
                String url = gitHubProperties.getApiUrl() + "/zen";

                String response = webClient.get()
                        .uri(url)
                        .header("Accept", "text/plain")
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(TIMEOUT)
                        .block();

                if (response != null && !response.isBlank()) {
                    return Health.up()
                            .withDetail("apiUrl", gitHubProperties.getApiUrl())
                            .withDetail("mode", "Public GitHub API (tokenless)")
                            .withDetail("status", "GitHub API reachable")
                            .build();
                }
            }

            return Health.down()
                    .withDetail("apiUrl", gitHubProperties.getApiUrl())
                    .withDetail("reason", "Unexpected response format")
                    .build();

        } catch (Exception e) {
            log.warn("GitHub API health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("apiUrl", gitHubProperties.getApiUrl())
                    .withDetail("error", e.getClass().getSimpleName() + ": " + e.getMessage())
                    .build();
        }
    }
}
