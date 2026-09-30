package com.bot.bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import lombok.Data;

import java.nio.file.Files;
import java.nio.file.Paths;

@Data
@Component
@ConfigurationProperties(prefix = "github")
public class GitHubProperties {

    /**
     * Optional personal access token (PAT), fine-grained token, or GitHub Actions token.
     * When set, allows the bot to fetch diffs and post reviews on any repository without needing a GitHub App.
     */
    private String token;

    /**
     * GitHub App ID (optional, only used when running as a GitHub App).
     */
    private String appId;

    /**
     * GitHub App Client ID (optional).
     */
    private String clientId;

    /**
     * GitHub Webhook secret (optional; if not set, webhook signature check is skipped).
     */
    private String webhookSecret;

    /**
     * Path to GitHub App private key PEM file (optional).
     */
    private String privateKeyPath = "certs/github-app.pem";

    /**
     * GitHub API base URL.
     */
    private String apiUrl = "https://api.github.com";

    /**
     * Check if a GitHub token (PAT/OAuth) is configured.
     */
    public boolean hasToken() {
        return token != null && !token.isBlank();
    }

    /**
     * Check if GitHub App credentials (appId and valid private key file) are configured.
     */
    public boolean hasAppCredentials() {
        if (appId == null || appId.isBlank()) {
            return false;
        }
        if (privateKeyPath == null || privateKeyPath.isBlank()) {
            return false;
        }
        return Files.isRegularFile(Paths.get(privateKeyPath));
    }
}
