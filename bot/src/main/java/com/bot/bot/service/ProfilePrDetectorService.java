package com.bot.bot.service;

import com.bot.bot.email.ThresholdAlertService;
import com.bot.bot.github.GitHubApiClient;
import com.bot.bot.persistence.PrAnalysis;
import com.bot.bot.persistence.PrAnalysisRepository;
import com.bot.bot.persistence.UserProfile;
import com.bot.bot.persistence.UserProfileRepository;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Background Automatic PR Detection Service.
 * Periodically detects new or updated pull requests for all permanently registered
 * GitHub profiles without requiring manual webhook setup, ngrok tunnels, or custom DNS.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfilePrDetectorService {

    private final UserProfileRepository userProfileRepository;
    private final GitHubApiClient gitHubApiClient;
    private final ReviewOrchestrator reviewOrchestrator;
    private final PrAnalysisRepository prAnalysisRepository;
    private final ThresholdAlertService thresholdAlertService;

    /**
     * Periodic background scanner runs every 60 seconds.
     */
    @Scheduled(fixedDelay = 60000, initialDelay = 10000)
    public void runScheduledDetection() {
        try {
            triggerSync();
        } catch (Exception e) {
            log.error("[Auto-Detector] Error during scheduled PR detection", e);
        }
    }

    /**
     * Executes PR detection for all active profiles.
     *
     * @return Number of newly triaged pull requests.
     */
    public synchronized int triggerSync() {
        List<UserProfile> activeProfiles = userProfileRepository.findByAutoTriageEnabledTrue();
        if (activeProfiles.isEmpty()) {
            return 0;
        }

        int totalTriaged = 0;
        for (UserProfile profile : activeProfiles) {
            try {
                totalTriaged += checkProfile(profile);
                profile.setLastSyncAt(Instant.now());
                profile.setUpdatedAt(Instant.now());
                userProfileRepository.save(profile);
            } catch (Exception e) {
                log.warn("[Auto-Detector] Error checking profile {}: {}", profile.getGithubUsername(), e.getMessage());
            }
        }
        return totalTriaged;
    }

    private int checkProfile(UserProfile profile) {
        String username = profile.getGithubUsername();
        if (username == null || username.isBlank()) {
            return 0;
        }

        List<String> targetRepos = resolveRepos(profile);
        int triagedCount = 0;

        for (String repoEntry : targetRepos) {
            String clean = repoEntry.trim();
            // Strip scheme and domain if user pasted full URL (e.g. https://github.com/owner/repo)
            clean = clean.replaceAll("^(?i)https?://(?:www\\.)?github\\.com/", "");
            clean = clean.replaceAll("^/+", "").replaceAll("/+$", "");

            String owner = username;
            String repo = clean;

            if (clean.contains("/")) {
                String[] parts = clean.split("/", 2);
                owner = parts[0].trim();
                repo = parts[1].trim();
            }

            if (repo.isBlank()) continue;

            try {
                List<JsonObject> openPulls = gitHubApiClient.fetchOpenPullRequests(owner, repo).block();
                if (openPulls == null || openPulls.isEmpty()) {
                    continue;
                }

                for (JsonObject pr : openPulls) {
                    if (!pr.has("number")) continue;
                    int prNumber = pr.get("number").getAsInt();

                    String headSha = "";
                    if (pr.has("head") && pr.get("head").isJsonObject()) {
                        JsonObject head = pr.getAsJsonObject("head");
                        if (head.has("sha") && !head.get("sha").isJsonNull()) {
                            headSha = head.get("sha").getAsString();
                        }
                    }

                    // Check if already analyzed for this commit
                    if (!headSha.isBlank() && prAnalysisRepository.existsByOwnerRepoPrSha(owner, repo, prNumber, headSha)) {
                        continue;
                    }

                    log.info("[Auto-Detector] Detected new/updated PR #{} on {}/{} for user '{}'",
                            prNumber, owner, repo, username);

                    PrAnalysis analysis = reviewOrchestrator.triagePullRequest(owner, repo, prNumber).block();
                    if (analysis != null) {
                        if (analysis.getTargetUser() == null || analysis.getTargetUser().isBlank()) {
                            analysis.setTargetUser(username);
                            prAnalysisRepository.save(analysis);
                        }
                        triagedCount++;
                        // Dispatch email alert directly to GitHub linked email
                        String email = profile.getNotificationEmail();
                        if (email == null || email.isBlank()) {
                            try {
                                email = gitHubApiClient.resolveUserEmail(username, owner, repo, prNumber).block();
                                if (email != null && !email.isBlank()) {
                                    profile.setNotificationEmail(email);
                                    userProfileRepository.save(profile);
                                }
                            } catch (Exception ex) {
                                log.debug("[Auto-Detector] Could not resolve email from GitHub: {}", ex.getMessage());
                            }
                        }

                        try {
                            List<String> recipients = (email != null && !email.isBlank()) ? List.of(email.trim()) : null;
                            thresholdAlertService.sendTriageReport(analysis, recipients);
                            log.info("[Auto-Detector] Sent triage report email to {}", recipients != null ? recipients : "maintainers");
                        } catch (Exception ex) {
                            log.warn("[Auto-Detector] Failed to send email alert: {}", ex.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[Auto-Detector] Could not check repository {}/{}: {}", owner, repo, e.getMessage());
            }
        }
        return triagedCount;
    }

    private List<String> resolveRepos(UserProfile profile) {
        String configured = profile.getMonitoredRepos();
        if (configured != null && !configured.isBlank()) {
            return Arrays.stream(configured.split("[,;\\n]"))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
        }

        // Auto-discover repositories for the profile
        try {
            List<String> discovered = gitHubApiClient.fetchUserRepositories(profile.getGithubUsername()).block();
            return (discovered != null) ? discovered : List.of();
        } catch (Exception e) {
            log.debug("[Auto-Detector] Auto-discovery error for {}: {}", profile.getGithubUsername(), e.getMessage());
            return List.of();
        }
    }
}
