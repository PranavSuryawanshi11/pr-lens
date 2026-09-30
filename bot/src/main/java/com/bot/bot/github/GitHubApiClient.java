package com.bot.bot.github;

import com.bot.bot.config.GitHubProperties;
import com.bot.bot.config.WebClientConfig;
import com.bot.bot.domain.PullRequestContext;
import com.bot.bot.domain.ReviewComment;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client for the GitHub REST API.
 * <p>
 * All authenticated API calls use an installation access token obtained by
 * exchanging the App JWT. The token is cached per installation ID for 55 minutes.
 * <p>
 * Transient failures (5xx, network timeouts) are retried automatically
 * via {@link WebClientConfig#buildRetrySpec(String)} with exponential backoff.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubApiClient {

    private final GitHubProperties gitHubProperties;
    private final GitHubJwtGenerator jwtGenerator;
    private final WebClient webClient;
    private final Gson gson;

    // Per-installation cache: a GitHub App often serves multiple orgs, so a
    // single-slot token cache would let concurrent requests clobber each other.
    private final Map<Long, CachedToken> installationTokenCache = new ConcurrentHashMap<>();
    private static final Duration TOKEN_CACHE_TTL = Duration.ofMinutes(55);

    private record CachedToken(String token, Instant expiry) {
        boolean isValid() {
            return Instant.now().isBefore(expiry);
        }
    }

    /**
     * Parse PR metadata from webhook payload (no API calls).
     * Extracts installation ID for subsequent authenticated calls.
     */
    public PullRequestContext fetchPullRequestContext(JsonObject prData) {
        JsonObject repo = prData.getAsJsonObject("repository");
        JsonObject pr = prData.getAsJsonObject("pull_request");
        if (repo == null || pr == null) {
            throw new IllegalArgumentException("Missing repository or pull_request in webhook payload");
        }

        JsonObject repoOwner = repo.getAsJsonObject("owner");
        if (repoOwner == null) {
            throw new IllegalArgumentException("Missing repository.owner in webhook payload");
        }

        // Extract installation ID from webhook payload
        long installationId = 0;
        JsonObject installation = prData.getAsJsonObject("installation");
        if (installation != null && installation.has("id") && !installation.get("id").isJsonNull()) {
            installationId = installation.get("id").getAsLong();
        }

        String owner = repoOwner.get("login").getAsString();
        String repoName = repo.get("name").getAsString();
        int prNumber = pr.get("number").getAsInt();
        String title = pr.get("title").getAsString();
        String description = pr.has("body") && !pr.get("body").isJsonNull() ? pr.get("body").getAsString() : "";
        JsonObject prUser = pr.has("user") && !pr.get("user").isJsonNull() ? pr.getAsJsonObject("user") : null;
        String authorLogin = prUser != null && prUser.has("login") && !prUser.get("login").isJsonNull()
                ? prUser.get("login").getAsString() : "unknown";

        JsonObject base = pr.getAsJsonObject("base");
        JsonObject head = pr.getAsJsonObject("head");
        if (base == null || head == null) {
            throw new IllegalArgumentException("Missing pull_request.base or pull_request.head in webhook payload");
        }

        String baseRef = base.get("ref").getAsString();
        String headRef = head.get("ref").getAsString();
        String commitSha = head.get("sha").getAsString();

        String authorAssociation = pr.has("author_association") && !pr.get("author_association").isJsonNull()
                ? pr.get("author_association").getAsString() : "NONE";
        AuthorReputationInfo rep = resolveAuthorReputation(owner, authorLogin, authorAssociation);

        String repoContext = "";
        if (repo.has("description") && !repo.get("description").isJsonNull()) {
            repoContext = repo.get("description").getAsString();
        }

        return PullRequestContext.builder()
                .owner(owner)
                .repo(repoName)
                .prNumber(prNumber)
                .title(title)
                .description(description)
                .authorLogin(authorLogin)
                .baseRef(baseRef)
                .headRef(headRef)
                .commitSha(commitSha)
                .installationId(installationId)
                .targetUser(owner)
                .authorAssociation(rep.association())
                .authorReputation(rep.reputation())
                .authorReputationDetail(rep.detail())
                .repoContext(repoContext)
                .build();
    }

    /**
     * Exchange the App JWT for an installation access token.
     * Cached per installation ID for 55 minutes (tokens expire after 1 hour).
     */
    public Mono<String> getInstallationToken(long installationId) {
        if (installationId <= 0) {
            return Mono.error(new IllegalArgumentException("Invalid installation ID: " + installationId));
        }

        // Return cached token if still valid for this installation
        CachedToken cached = installationTokenCache.get(installationId);
        if (cached != null && cached.isValid()) {
            return Mono.just(cached.token());
        }

        String url = gitHubProperties.getApiUrl() + "/app/installations/" + installationId + "/access_tokens";

        return webClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + jwtGenerator.generateAppToken())
                .header("Accept", "application/vnd.github.v3+json")
                .retrieve()
                .bodyToMono(String.class)
                .map(response -> {
                    try {
                        JsonObject json = gson.fromJson(response, JsonObject.class);
                        String token = json.get("token").getAsString();
                        Instant expiry = Instant.now().plus(TOKEN_CACHE_TTL);
                        installationTokenCache.put(installationId, new CachedToken(token, expiry));
                        log.info("Obtained installation access token for installation {} (cached until {})",
                                installationId, expiry);
                        return token;
                    } catch (Exception e) {
                        log.error("Failed to parse installation token response", e);
                        throw new RuntimeException("Failed to get installation token", e);
                    }
                })
                .retryWhen(WebClientConfig.buildRetrySpec("get-installation-token"))
                .doOnError(e -> log.error("Error fetching installation token for installation {} after retries",
                        installationId, e));
    }

    /**
     * Resolves the authentication token to use for GitHub API calls:
     * 1. Personal Access Token (PAT) / GITHUB_TOKEN if configured.
     * 2. GitHub App installation token if installationId > 0.
     * 3. Error if neither is available when an authenticated operation is strictly required.
     */
    public Mono<String> resolveAuthToken(long installationId) {
        if (gitHubProperties.hasToken()) {
            return Mono.just(gitHubProperties.getToken().trim());
        }
        if (installationId > 0 && gitHubProperties.hasAppCredentials()) {
            return getInstallationToken(installationId);
        }
        return Mono.error(new IllegalStateException("No GitHub token or App credentials configured for authenticated API call"));
    }

    /**
     * Fetch unified diff for a PR using PAT, installation token, or unauthenticated for public repos.
     */
    public Mono<String> fetchDiff(String owner, String repo, int prNumber, long installationId) {
        String url = String.format("%s/repos/%s/%s/pulls/%d",
                gitHubProperties.getApiUrl(), owner, repo, prNumber);

        Mono<String> tokenMono;
        if (gitHubProperties.hasToken()) {
            tokenMono = Mono.just(gitHubProperties.getToken().trim());
        } else if (installationId > 0 && gitHubProperties.hasAppCredentials()) {
            tokenMono = getInstallationToken(installationId).onErrorReturn("");
        } else {
            tokenMono = Mono.just("");
        }

        return tokenMono.flatMap(token -> {
            var request = webClient.get()
                    .uri(url)
                    .header("Accept", "application/vnd.github.v3.diff");
            if (!token.isBlank()) {
                request.header("Authorization", "Bearer " + token);
            }
            return request.retrieve()
                    .bodyToMono(String.class);
        })
        .retryWhen(WebClientConfig.buildRetrySpec("fetch-diff"))
        .doOnError(e -> log.error("Error fetching diff for {}/{}/PR#{} after retries",
                owner, repo, prNumber, e));
    }

    /**
     * Post a PR review with summary body and optional inline comments.
     */
    public Mono<Void> submitReview(String owner, String repo, int prNumber,
                                    String body, String event, List<ReviewComment> comments,
                                    long installationId) {
        String url = String.format("%s/repos/%s/%s/pulls/%d/reviews",
                gitHubProperties.getApiUrl(), owner, repo, prNumber);

        JsonObject reviewBody = new JsonObject();
        reviewBody.addProperty("body", body);
        reviewBody.addProperty("event", event);

        if (comments != null && !comments.isEmpty()) {
            JsonArray commentArray = new JsonArray();
            for (ReviewComment c : comments) {
                if (c.getPath() == null || c.getBody() == null) continue;
                JsonObject comment = new JsonObject();
                comment.addProperty("path", c.getPath());
                comment.addProperty("body", c.getBody());
                if (c.getLine() > 0) {
                    comment.addProperty("line", c.getLine());
                    comment.addProperty("side", c.getSide() != null ? c.getSide() : "RIGHT");
                }
                if (c.getStartLine() > 0 && c.getStartLine() != c.getLine()) {
                    comment.addProperty("start_line", c.getStartLine());
                }
                commentArray.add(comment);
            }
            reviewBody.add("comments", commentArray);
        }

        return resolveAuthToken(installationId)
                .flatMap(token -> webClient.post()
                        .uri(url)
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/vnd.github.v3+json")
                        .bodyValue(reviewBody.toString())
                        .retrieve()
                        .toBodilessEntity()
                        .then())
                .retryWhen(WebClientConfig.buildRetrySpec("submit-review"))
                .doOnError(e -> log.error("Error submitting review for {}/{}/PR#{} after retries",
                        owner, repo, prNumber, e));
    }

    /**
     * Close a pull request (state=closed). Does NOT merge.
     */
    public Mono<Void> closePullRequest(String owner, String repo, int prNumber, long installationId) {
        String url = String.format("%s/repos/%s/%s/pulls/%d",
                gitHubProperties.getApiUrl(), owner, repo, prNumber);

        JsonObject body = new JsonObject();
        body.addProperty("state", "closed");

        return resolveAuthToken(installationId)
                .flatMap(token -> webClient.patch()
                        .uri(url)
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/vnd.github.v3+json")
                        .bodyValue(body.toString())
                        .retrieve()
                        .toBodilessEntity()
                        .then())
                .retryWhen(WebClientConfig.buildRetrySpec("close-pr"))
                .doOnError(e -> log.error("Error closing PR {}/{}/PR#{} after retries",
                        owner, repo, prNumber, e));
    }

    /**
     * Post a regular issue comment on a PR.
     */
    public Mono<Void> postComment(String owner, String repo, int prNumber, String body, long installationId) {
        String url = String.format("%s/repos/%s/%s/issues/%d/comments",
                gitHubProperties.getApiUrl(), owner, repo, prNumber);

        JsonObject comment = new JsonObject();
        comment.addProperty("body", body);

        return resolveAuthToken(installationId)
                .flatMap(token -> webClient.post()
                        .uri(url)
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/vnd.github.v3+json")
                        .bodyValue(comment.toString())
                        .retrieve()
                        .toBodilessEntity()
                        .then())
                .retryWhen(WebClientConfig.buildRetrySpec("post-comment"))
                .doOnError(e -> log.error("Error posting comment for {}/{}/PR#{} after retries",
                        owner, repo, prNumber, e));
    }

    /**
     * Add labels to a PR (issues API).
     */
    public Mono<Void> addLabels(String owner, String repo, int prNumber, List<String> labels, long installationId) {
        String url = String.format("%s/repos/%s/%s/issues/%d/labels",
                gitHubProperties.getApiUrl(), owner, repo, prNumber);

        JsonArray labelArray = new JsonArray();
        for (String label : labels) {
            labelArray.add(label);
        }

        return resolveAuthToken(installationId)
                .flatMap(token -> webClient.post()
                        .uri(url)
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/vnd.github.v3+json")
                        .bodyValue(labelArray.toString())
                        .retrieve()
                        .toBodilessEntity()
                        .then())
                .retryWhen(WebClientConfig.buildRetrySpec("add-labels"))
                .doOnError(e -> log.error("Error adding labels for {}/{}/PR#{} after retries",
                        owner, repo, prNumber, e));
    }

    /**
     * Fetch PR metadata from GitHub REST API (for on-demand triage without webhook).
     * Works on any repository / account unauthenticated (public) or with GITHUB_TOKEN.
     */
    public Mono<PullRequestContext> fetchPullRequestDetails(String owner, String repo, int prNumber) {
        String url = String.format("%s/repos/%s/%s/pulls/%d",
                gitHubProperties.getApiUrl(), owner, repo, prNumber);

        Mono<String> tokenMono;
        if (gitHubProperties.hasToken()) {
            tokenMono = Mono.just(gitHubProperties.getToken().trim());
        } else {
            tokenMono = Mono.just("");
        }

        return tokenMono.flatMap(token -> {
            var request = webClient.get()
                    .uri(url)
                    .header("Accept", "application/vnd.github.v3+json");
            if (!token.isBlank()) {
                request.header("Authorization", "Bearer " + token);
            }
            return request.retrieve().bodyToMono(String.class);
        })
        .map(responseJson -> {
            JsonObject pr = gson.fromJson(responseJson, JsonObject.class);
            String title = pr.has("title") && !pr.get("title").isJsonNull() ? pr.get("title").getAsString() : "";
            String body = pr.has("body") && !pr.get("body").isJsonNull() ? pr.get("body").getAsString() : "";

            JsonObject user = pr.has("user") && !pr.get("user").isJsonNull() ? pr.getAsJsonObject("user") : null;
            String author = user != null && user.has("login") && !user.get("login").isJsonNull()
                    ? user.get("login").getAsString() : "unknown";

            JsonObject base = pr.has("base") && !pr.get("base").isJsonNull() ? pr.getAsJsonObject("base") : null;
            JsonObject head = pr.has("head") && !pr.get("head").isJsonNull() ? pr.getAsJsonObject("head") : null;

            String baseRef = base != null && base.has("ref") && !base.get("ref").isJsonNull()
                    ? base.get("ref").getAsString() : "main";
            String headRef = head != null && head.has("ref") && !head.get("ref").isJsonNull()
                    ? head.get("ref").getAsString() : "unknown";
            String commitSha = head != null && head.has("sha") && !head.get("sha").isJsonNull()
                    ? head.get("sha").getAsString() : "";

            String authorAssociation = pr.has("author_association") && !pr.get("author_association").isJsonNull()
                    ? pr.get("author_association").getAsString() : "NONE";
            AuthorReputationInfo rep = resolveAuthorReputation(owner, author, authorAssociation);

            String repoContext = "";
            if (base != null && base.has("repo") && base.get("repo").isJsonObject()) {
                JsonObject baseRepo = base.getAsJsonObject("repo");
                String desc = baseRepo.has("description") && !baseRepo.get("description").isJsonNull()
                        ? baseRepo.get("description").getAsString() : "";
                String lang = baseRepo.has("language") && !baseRepo.get("language").isJsonNull()
                        ? baseRepo.get("language").getAsString() : "";
                if (!desc.isBlank() && !lang.isBlank()) {
                    repoContext = desc + " (" + lang + ")";
                } else if (!desc.isBlank()) {
                    repoContext = desc;
                } else if (!lang.isBlank()) {
                    repoContext = "Primary language: " + lang;
                }
            }

            return PullRequestContext.builder()
                    .owner(owner)
                    .repo(repo)
                    .prNumber(prNumber)
                    .title(title)
                    .description(body)
                    .authorLogin(author)
                    .baseRef(baseRef)
                    .headRef(headRef)
                    .commitSha(commitSha)
                    .installationId(0)
                    .targetUser(owner)
                    .authorAssociation(rep.association())
                    .authorReputation(rep.reputation())
                    .authorReputationDetail(rep.detail())
                    .repoContext(repoContext)
                    .build();
        })
        .retryWhen(WebClientConfig.buildRetrySpec("fetch-pr-details"))
        .doOnError(e -> log.error("Error fetching PR details for {}/{}/PR#{}", owner, repo, prNumber, e));
    }

    public record AuthorReputationInfo(String reputation, String detail, String association) {}

    public static AuthorReputationInfo resolveAuthorReputation(String owner, String author, String association) {
        String assoc = (association != null && !association.isBlank()) ? association.toUpperCase().trim() : "NONE";
        if (owner != null && author != null && owner.equalsIgnoreCase(author.trim())) {
            assoc = "OWNER";
        }

        return switch (assoc) {
            case "OWNER" -> new AuthorReputationInfo("TRUSTED_MAINTAINER", "Repository Owner / Core Maintainer", assoc);
            case "MEMBER" -> new AuthorReputationInfo("TRUSTED_MAINTAINER", "Organization Member / Trusted Maintainer", assoc);
            case "COLLABORATOR" -> new AuthorReputationInfo("COLLABORATOR", "Repository Collaborator with write access", assoc);
            case "CONTRIBUTOR" -> new AuthorReputationInfo("RETURNING_CONTRIBUTOR", "Returning Contributor with merged PRs", assoc);
            case "FIRST_TIME_CONTRIBUTOR", "FIRST_TIMER" -> new AuthorReputationInfo("FIRST_TIME_CONTRIBUTOR", "First-time Contributor to this repository", assoc);
            default -> new AuthorReputationInfo("FIRST_TIME_CONTRIBUTOR", "External Contributor (standard review priority)", assoc);
        };
    }

    /**
     * Fetch open pull requests for a repository.
     */
    public Mono<List<JsonObject>> fetchOpenPullRequests(String owner, String repo) {
        String url = String.format("%s/repos/%s/%s/pulls?state=open&sort=updated&direction=desc&per_page=10",
                gitHubProperties.getApiUrl(), owner, repo);

        Mono<String> tokenMono = gitHubProperties.hasToken()
                ? Mono.just(gitHubProperties.getToken().trim())
                : Mono.just("");

        return tokenMono.flatMap(token -> {
            var request = webClient.get()
                    .uri(url)
                    .header("Accept", "application/vnd.github.v3+json");
            if (!token.isBlank()) {
                request.header("Authorization", "Bearer " + token);
            }
            return request.retrieve().bodyToMono(String.class);
        })
        .map(responseJson -> {
            List<JsonObject> list = new ArrayList<>();
            JsonArray array = gson.fromJson(responseJson, JsonArray.class);
            if (array != null) {
                for (var el : array) {
                    if (el.isJsonObject()) {
                        list.add(el.getAsJsonObject());
                    }
                }
            }
            return list;
        })
        .onErrorResume(e -> {
            log.warn("Could not fetch open PRs for {}/{}: {}", owner, repo, e.getMessage());
            return Mono.just(new ArrayList<>());
        });
    }

    /**
     * Fetch recent public repositories for a GitHub user/organization.
     */
    public Mono<List<String>> fetchUserRepositories(String username) {
        String url = String.format("%s/users/%s/repos?sort=pushed&per_page=10",
                gitHubProperties.getApiUrl(), username);

        Mono<String> tokenMono = gitHubProperties.hasToken()
                ? Mono.just(gitHubProperties.getToken().trim())
                : Mono.just("");

        return tokenMono.flatMap(token -> {
            var request = webClient.get()
                    .uri(url)
                    .header("Accept", "application/vnd.github.v3+json");
            if (!token.isBlank()) {
                request.header("Authorization", "Bearer " + token);
            }
            return request.retrieve().bodyToMono(String.class);
        })
        .map(responseJson -> {
            List<String> list = new ArrayList<>();
            JsonArray array = gson.fromJson(responseJson, JsonArray.class);
            if (array != null) {
                for (var el : array) {
                    if (el.isJsonObject() && el.getAsJsonObject().has("name")) {
                        list.add(el.getAsJsonObject().get("name").getAsString());
                    }
                }
            }
            return list;
        })
        .onErrorResume(e -> {
            log.warn("Could not fetch repositories for user {}: {}", username, e.getMessage());
            return Mono.just(new ArrayList<>());
        });
    }

    /**
     * Merge / Accept a pull request on GitHub (PUT /repos/{owner}/{repo}/pulls/{prNumber}/merge).
     */
    public Mono<Void> mergePullRequest(String owner, String repo, int prNumber,
                                      String commitTitle, String commitMessage,
                                      long installationId) {
        String url = String.format("%s/repos/%s/%s/pulls/%d/merge",
                gitHubProperties.getApiUrl(), owner, repo, prNumber);

        JsonObject body = new JsonObject();
        if (commitTitle != null && !commitTitle.isBlank()) {
            body.addProperty("commit_title", commitTitle);
        }
        if (commitMessage != null && !commitMessage.isBlank()) {
            body.addProperty("commit_message", commitMessage);
        }
        body.addProperty("merge_method", "merge");

        return resolveAuthToken(installationId)
                .flatMap(token -> webClient.put()
                        .uri(url)
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/vnd.github.v3+json")
                        .bodyValue(body.toString())
                        .retrieve()
                        .toBodilessEntity()
                        .then())
                .retryWhen(WebClientConfig.buildRetrySpec("merge-pr"))
                .doOnError(e -> log.error("Error merging PR {}/{}/PR#{} after retries",
                        owner, repo, prNumber, e));
    }

    /**
     * Cache for resolved GitHub user emails so lookups are fast and rate-limit friendly.
     */
    private final Map<String, String> resolvedEmailCache = new ConcurrentHashMap<>();

    /**
     * Fetch authenticated user details from GitHub REST API (GET /user).
     */
    public Mono<JsonObject> fetchAuthenticatedUser() {
        if (!gitHubProperties.hasToken()) {
            return Mono.empty();
        }
        String url = gitHubProperties.getApiUrl() + "/user";
        return webClient.get()
                .uri(url)
                .header("Authorization", "Bearer " + gitHubProperties.getToken().trim())
                .header("Accept", "application/vnd.github.v3+json")
                .retrieve()
                .bodyToMono(String.class)
                .map(res -> gson.fromJson(res, JsonObject.class))
                .onErrorResume(e -> {
                    log.debug("Could not fetch authenticated user: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Automatically resolves the email address registered or linked with a GitHub account.
     * Checks authenticated user emails, public profile, recent commits, and public push events.
     */
    public Mono<String> resolveUserEmail(String username, String owner, String repo, Integer prNumber) {
        String targetUser = (username != null && !username.isBlank()) ? username.trim() : (owner != null ? owner.trim() : "");
        if (targetUser.isBlank()) {
            return resolveAuthenticatedUserEmail();
        }

        String cacheKey = targetUser.toLowerCase();
        String cached = resolvedEmailCache.get(cacheKey);
        if (cached != null && !cached.isBlank()) {
            return Mono.just(cached);
        }

        // 1. Try public profile GET /users/{username}
        return fetchPublicUserEmail(targetUser)
                .flatMap(email -> {
                    if (isValidEmail(email)) {
                        resolvedEmailCache.put(cacheKey, email);
                        return Mono.just(email);
                    }
                    return Mono.empty();
                })
                // 2. Try fetching from repository commits (PR repo or user's top repos)
                .switchIfEmpty(Mono.defer(() -> fetchEmailFromCommits(targetUser, owner, repo)))
                // 3. Try fetching from PR commit history
                .switchIfEmpty(Mono.defer(() -> fetchEmailFromPrCommits(owner, repo, prNumber, targetUser)))
                // 4. Try public events (PushEvent commits)
                .switchIfEmpty(Mono.defer(() -> fetchEmailFromPublicEvents(targetUser)))
                // 5. Fallback to authenticated user email ONLY IF targetUser is blank or matches authenticated user
                .switchIfEmpty(Mono.defer(() -> {
                    if (targetUser.isBlank()) {
                        return resolveAuthenticatedUserEmail();
                    }
                    return fetchAuthenticatedUser()
                            .flatMap(userObj -> {
                                if (userObj != null && userObj.has("login") && !userObj.get("login").isJsonNull()) {
                                    String authLogin = userObj.get("login").getAsString();
                                    if (authLogin.equalsIgnoreCase(targetUser)) {
                                        return resolveAuthenticatedUserEmail();
                                    }
                                }
                                return Mono.empty();
                            });
                }))
                .map(foundEmail -> {
                    if (isValidEmail(foundEmail)) {
                        resolvedEmailCache.put(cacheKey, foundEmail);
                    }
                    return foundEmail;
                })
                .defaultIfEmpty("");
    }

    public Mono<String> resolveUserEmail(String username) {
        return resolveUserEmail(username, null, null, null);
    }

    public Mono<String> resolveUserEmail(String owner, String repo, Integer prNumber) {
        return resolveUserEmail(owner, owner, repo, prNumber);
    }

    public Mono<String> resolveAuthenticatedUserEmail() {
        if (!gitHubProperties.hasToken()) {
            return Mono.just("");
        }
        // 1. Try /user/emails (specifically designed by GitHub to return verified emails even if profile is private)
        return fetchPrimaryUserEmail()
                .flatMap(email -> {
                    if (isValidEmail(email)) return Mono.just(email);
                    return Mono.empty();
                })
                // 2. Try /user
                .switchIfEmpty(Mono.defer(() -> fetchAuthenticatedUser().flatMap(userObj -> {
                    if (userObj.has("email") && !userObj.get("email").isJsonNull()) {
                        String email = userObj.get("email").getAsString();
                        if (isValidEmail(email)) {
                            return Mono.just(email);
                        }
                    }
                    // If /user has null email, check user repositories for their commit email
                    if (userObj.has("login") && !userObj.get("login").isJsonNull()) {
                        String login = userObj.get("login").getAsString();
                        return fetchEmailFromCommits(login, null, null);
                    }
                    return Mono.just("");
                })))
                .defaultIfEmpty("");
    }

    /**
     * Fetch primary/verified email for the authenticated user (GET /user/emails).
     * This endpoint returns verified emails even if the user profile and emails are set to private.
     */
    public Mono<String> fetchPrimaryUserEmail() {
        if (!gitHubProperties.hasToken()) {
            return Mono.empty();
        }
        String url = gitHubProperties.getApiUrl() + "/user/emails";
        return webClient.get()
                .uri(url)
                .header("Authorization", "Bearer " + gitHubProperties.getToken().trim())
                .header("Accept", "application/vnd.github.v3+json")
                .retrieve()
                .bodyToMono(String.class)
                .map(res -> {
                    try {
                        JsonArray arr = gson.fromJson(res, JsonArray.class);
                        if (arr != null) {
                            String firstVerified = "";
                            for (var el : arr) {
                                if (!el.isJsonObject()) continue;
                                JsonObject obj = el.getAsJsonObject();
                                String email = obj.has("email") && !obj.get("email").isJsonNull() ? obj.get("email").getAsString() : "";
                                boolean primary = obj.has("primary") && obj.get("primary").getAsBoolean();
                                boolean verified = obj.has("verified") && obj.get("verified").getAsBoolean();
                                if (isValidEmail(email)) {
                                    if (primary && verified) return email;
                                    if (primary) return email;
                                    if (verified && firstVerified.isEmpty()) firstVerified = email;
                                }
                            }
                            if (!firstVerified.isEmpty()) return firstVerified;
                        }
                    } catch (Exception ignored) {}
                    return "";
                })
                .onErrorReturn("");
    }

    private Mono<String> fetchPublicUserEmail(String username) {
        String url = String.format("%s/users/%s", gitHubProperties.getApiUrl(), username);
        var req = webClient.get().uri(url).header("Accept", "application/vnd.github.v3+json");
        if (gitHubProperties.hasToken()) {
            req.header("Authorization", "Bearer " + gitHubProperties.getToken().trim());
        }
        return req.retrieve()
                .bodyToMono(String.class)
                .map(res -> {
                    JsonObject obj = gson.fromJson(res, JsonObject.class);
                    if (obj != null && obj.has("email") && !obj.get("email").isJsonNull()) {
                        String em = obj.get("email").getAsString();
                        return em != null ? em.trim() : "";
                    }
                    return "";
                })
                .onErrorReturn("");
    }

    private Mono<String> fetchEmailFromCommits(String username, String owner, String repo) {
        // If owner and repo are specified, check that repo first
        if (owner != null && !owner.isBlank() && repo != null && !repo.isBlank()) {
            return fetchEmailFromRepoCommits(owner, repo, username)
                    .flatMap(email -> {
                        if (isValidEmail(email)) return Mono.just(email);
                        return Mono.empty();
                    })
                    .switchIfEmpty(Mono.defer(() -> fetchEmailFromUserRepos(username)));
        }
        return fetchEmailFromUserRepos(username);
    }

    private Mono<String> fetchEmailFromUserRepos(String username) {
        return fetchUserRepositories(username)
                .flatMapMany(Flux::fromIterable)
                .take(5)
                .concatMap(r -> fetchEmailFromRepoCommits(username, r, username))
                .filter(this::isValidEmail)
                .next();
    }

    private Mono<String> fetchEmailFromRepoCommits(String owner, String repo, String username) {
        String url = (username != null && !username.isBlank())
                ? String.format("%s/repos/%s/%s/commits?author=%s&per_page=10", gitHubProperties.getApiUrl(), owner, repo, username.trim())
                : String.format("%s/repos/%s/%s/commits?per_page=10", gitHubProperties.getApiUrl(), owner, repo);
        var req = webClient.get().uri(url).header("Accept", "application/vnd.github.v3+json");
        if (gitHubProperties.hasToken()) {
            req.header("Authorization", "Bearer " + gitHubProperties.getToken().trim());
        }
        return req.retrieve()
                .bodyToMono(String.class)
                .map(res -> extractEmailFromCommitsJson(res, username))
                .onErrorReturn("");
    }

    private Mono<String> fetchEmailFromPrCommits(String owner, String repo, Integer prNumber, String username) {
        if (owner == null || repo == null || prNumber == null || prNumber <= 0) {
            return Mono.empty();
        }
        String url = String.format("%s/repos/%s/%s/pulls/%d/commits?per_page=10",
                gitHubProperties.getApiUrl(), owner, repo, prNumber);
        var req = webClient.get().uri(url).header("Accept", "application/vnd.github.v3+json");
        if (gitHubProperties.hasToken()) {
            req.header("Authorization", "Bearer " + gitHubProperties.getToken().trim());
        }
        return req.retrieve()
                .bodyToMono(String.class)
                .map(res -> extractEmailFromCommitsJson(res, username))
                .onErrorReturn("");
    }

    private Mono<String> fetchEmailFromPublicEvents(String username) {
        String url = String.format("%s/users/%s/events/public?per_page=10",
                gitHubProperties.getApiUrl(), username);
        var req = webClient.get().uri(url).header("Accept", "application/vnd.github.v3+json");
        if (gitHubProperties.hasToken()) {
            req.header("Authorization", "Bearer " + gitHubProperties.getToken().trim());
        }
        return req.retrieve()
                .bodyToMono(String.class)
                .map(res -> {
                    JsonArray events = gson.fromJson(res, JsonArray.class);
                    if (events == null) return "";
                    for (var evEl : events) {
                        if (!evEl.isJsonObject()) continue;
                        JsonObject ev = evEl.getAsJsonObject();
                        if ("PushEvent".equals(ev.get("type") != null ? ev.get("type").getAsString() : "")) {
                            JsonObject payload = ev.getAsJsonObject("payload");
                            if (payload != null && payload.has("commits")) {
                                JsonArray commits = payload.getAsJsonArray("commits");
                                if (commits != null) {
                                    for (var cEl : commits) {
                                        JsonObject c = cEl.getAsJsonObject();
                                        JsonObject author = c.getAsJsonObject("author");
                                        if (author != null && author.has("email")) {
                                            String em = author.get("email").getAsString();
                                            if (isValidEmail(em) && !em.contains("noreply.github.com")) {
                                                return em;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    return "";
                })
                .onErrorReturn("");
    }

    private String extractEmailFromCommitsJson(String json, String targetUsername) {
        try {
            JsonArray commits = gson.fromJson(json, JsonArray.class);
            if (commits == null) return "";

            String candidateNoreply = "";
            for (var el : commits) {
                if (!el.isJsonObject()) continue;
                JsonObject cObj = el.getAsJsonObject();

                // If commit has author login, check if it matches targetUser
                if (targetUsername != null && !targetUsername.isBlank() && cObj.has("author") && !cObj.get("author").isJsonNull()) {
                    JsonObject authorObj = cObj.getAsJsonObject("author");
                    if (authorObj.has("login") && !authorObj.get("login").isJsonNull()) {
                        String login = authorObj.get("login").getAsString();
                        if (!login.equalsIgnoreCase(targetUsername)) {
                            continue;
                        }
                    }
                }

                if (cObj.has("commit") && cObj.get("commit").isJsonObject()) {
                    JsonObject commitData = cObj.getAsJsonObject("commit");
                    // Check author
                    if (commitData.has("author") && commitData.get("author").isJsonObject()) {
                        JsonObject author = commitData.getAsJsonObject("author");
                        if (author.has("email") && !author.get("email").isJsonNull()) {
                            String email = author.get("email").getAsString().trim();
                            if (isValidEmail(email)) {
                                if (!email.toLowerCase().contains("noreply.github.com")) {
                                    return email;
                                } else if (candidateNoreply.isEmpty()) {
                                    candidateNoreply = email;
                                }
                            }
                        }
                    }
                    // Check committer
                    if (commitData.has("committer") && commitData.get("committer").isJsonObject()) {
                        JsonObject committer = commitData.getAsJsonObject("committer");
                        if (committer.has("email") && !committer.get("email").isJsonNull()) {
                            String email = committer.get("email").getAsString().trim();
                            if (isValidEmail(email)) {
                                if (!email.toLowerCase().contains("noreply.github.com")) {
                                    return email;
                                }
                            }
                        }
                    }
                }
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    boolean isValidEmail(String email) {
        return email != null 
                && email.contains("@") 
                && email.contains(".") 
                && email.length() >= 5 
                && !email.toLowerCase().contains("noreply.github.com");
    }
}

