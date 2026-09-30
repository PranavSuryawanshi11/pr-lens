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
     * GitHub App slug (e.g. "my-pr-lens-bot"), used to form direct installation URLs.
     */
    private String appSlug = "";

    /**
     * GitHub API base URL.
     */
    private String apiUrl = "https://api.github.com";

    /**
     * Returns the direct installation URL for this GitHub App, or null if not yet configured.
     */
    public String getInstallUrl() {
        if (appSlug != null && !appSlug.isBlank()) {
            return "https://github.com/apps/" + appSlug.trim() + "/installations/new";
        }
        return null;
    }

    /**
     * Returns the public listing URL for this GitHub App, or null if not yet configured.
     */
    public String getPublicAppUrl() {
        if (appSlug != null && !appSlug.isBlank()) {
            return "https://github.com/apps/" + appSlug.trim();
        }
        return null;
    }

    /**
     * Check if a GitHub token (PAT/OAuth) is configured.
     */
    public boolean hasToken() {
        return token != null && !token.isBlank();
    }

    /**
     * Resolves the private key path across working directory variations (./, ./bot/, ../).
     */
    public java.nio.file.Path resolvePrivateKeyPath() {
        if (privateKeyPath == null || privateKeyPath.isBlank()) {
            return null;
        }
        java.nio.file.Path p = java.nio.file.Paths.get(privateKeyPath);
        if (java.nio.file.Files.isRegularFile(p)) {
            return p;
        }
        java.nio.file.Path inBot = java.nio.file.Paths.get("bot", privateKeyPath);
        if (java.nio.file.Files.isRegularFile(inBot)) {
            return inBot;
        }
        java.nio.file.Path inParent = java.nio.file.Paths.get("..", privateKeyPath);
        if (java.nio.file.Files.isRegularFile(inParent)) {
            return inParent;
        }
        return null;
    }

    /**
     * Check if GitHub App credentials (appId and valid private key file) are configured.
     */
    public boolean hasAppCredentials() {
        if (appId == null || appId.isBlank()) {
            return false;
        }
        return resolvePrivateKeyPath() != null;
    }
}
