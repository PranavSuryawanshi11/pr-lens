package com.bot.bot.web;

import com.bot.bot.actions.TokenService;
import com.bot.bot.email.ThresholdAlertService;
import com.bot.bot.github.GitHubApiClient;
import com.bot.bot.persistence.PrAnalysis;
import com.bot.bot.persistence.PrAnalysisRepository;
import com.bot.bot.persistence.UserProfile;
import com.bot.bot.persistence.UserProfileRepository;
import com.bot.bot.service.ProfilePrDetectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ProfileApiController {

    private final UserProfileRepository userProfileRepository;
    private final ProfilePrDetectorService detectorService;
    private final PrAnalysisRepository prAnalysisRepository;
    private final GitHubApiClient gitHubApiClient;
    private final ThresholdAlertService thresholdAlertService;
    private final TokenService tokenService;

    public ProfileApiController(
            UserProfileRepository userProfileRepository,
            ProfilePrDetectorService detectorService,
            PrAnalysisRepository prAnalysisRepository,
            GitHubApiClient gitHubApiClient,
            ThresholdAlertService thresholdAlertService,
            @Autowired(required = false) TokenService tokenService) {
        this.userProfileRepository = userProfileRepository;
        this.detectorService = detectorService;
        this.prAnalysisRepository = prAnalysisRepository;
        this.gitHubApiClient = gitHubApiClient;
        this.thresholdAlertService = thresholdAlertService;
        this.tokenService = tokenService;
    }

    public record ProfileDto(
            String githubUsername,
            String notificationEmail,
            Boolean autoTriageEnabled,
            String monitoredRepos,
            Instant lastSyncAt
    ) {}

    @GetMapping("/profile")
    public ResponseEntity<?> getProfile(@RequestParam(value = "username", required = false) String username) {
        if (username != null && !username.isBlank()) {
            String u = username.trim();
            UserProfile user = userProfileRepository.findByGithubUsernameIgnoreCase(u)
                    .orElseGet(() -> {
                        UserProfile p = new UserProfile();
                        p.setGithubUsername(u);
                        p.setAutoTriageEnabled(true);
                        p.setCreatedAt(Instant.now());
                        p.setUpdatedAt(Instant.now());
                        return userProfileRepository.save(p);
                    });
            if (user != null && (user.getNotificationEmail() == null || user.getNotificationEmail().isBlank())) {
                try {
                    String em = gitHubApiClient.resolveUserEmail(user.getGithubUsername()).block();
                    if (em != null && !em.isBlank()) {
                        user.setNotificationEmail(em);
                        user = userProfileRepository.save(user);
                    }
                } catch (Exception ignored) {}
            }
            return ResponseEntity.ok(user);
        }

        // When no username is specified, return an empty profile to avoid pre-filling on startup
        UserProfile empty = new UserProfile();
        empty.setGithubUsername("");
        empty.setNotificationEmail("");
        empty.setAutoTriageEnabled(true);
        empty.setMonitoredRepos("");
        return ResponseEntity.ok(empty);
    }

    @PostMapping("/profile")
    public ResponseEntity<?> saveProfile(@RequestBody ProfileDto dto) {
        if (dto.githubUsername() == null || dto.githubUsername().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "githubUsername is required"));
        }

        String username = dto.githubUsername().trim();
        UserProfile profile = userProfileRepository.findByGithubUsernameIgnoreCase(username)
                .orElseGet(() -> {
                    UserProfile p = new UserProfile();
                    p.setGithubUsername(username);
                    p.setCreatedAt(Instant.now());
                    return p;
                });

        // 1. If client explicitly supplied an email, prioritize that
        boolean hasExplicitEmail = dto.notificationEmail() != null
                && !dto.notificationEmail().isBlank()
                && !dto.notificationEmail().contains("Resolving")
                && !dto.notificationEmail().contains("No public email")
                && !dto.notificationEmail().contains("Auto-detected");

        if (hasExplicitEmail) {
            profile.setNotificationEmail(dto.notificationEmail().trim());
            log.info("[Profile] Prioritizing user-provided email '{}' for '{}'", dto.notificationEmail().trim(), username);
        } else {
            // Directly resolve and take the email linked to this GitHub account
            try {
                String resolved = gitHubApiClient.resolveUserEmail(username).block();
                if (resolved != null && !resolved.isBlank()) {
                    profile.setNotificationEmail(resolved.trim());
                    log.info("[Profile] Directly took GitHub linked email '{}' for '{}'", resolved, username);
                }
            } catch (Exception e) {
                log.debug("Could not resolve GitHub email for {}: {}", username, e.getMessage());
            }
        }

        if (dto.autoTriageEnabled() != null) {
            profile.setAutoTriageEnabled(dto.autoTriageEnabled());
        }
        if (dto.monitoredRepos() != null) {
            profile.setMonitoredRepos(dto.monitoredRepos().trim());
        }
        profile.setUpdatedAt(Instant.now());

        UserProfile saved = userProfileRepository.save(profile);
        log.info("[Profile] Saved profile for '{}' (autoTriage: {}, email: {})",
                saved.getGithubUsername(), saved.getAutoTriageEnabled(), saved.getNotificationEmail());

        // If enabled, trigger a background sync check
        if (Boolean.TRUE.equals(saved.getAutoTriageEnabled())) {
            new Thread(detectorService::triggerSync).start();
        }

        return ResponseEntity.ok(saved);
    }

    @PostMapping("/profile/sync")
    public ResponseEntity<?> syncNow() {
        int triaged = detectorService.triggerSync();
        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "triagedCount", triaged,
                "message", "Checked all active profiles for new PRs. Triaged " + triaged + " new PR(s)."
        ));
    }

    @GetMapping("/profile/resolve-email")
    public ResponseEntity<?> resolveEmail(@RequestParam("username") String username) {
        if (username == null || username.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "username is required"));
        }
        String email = "";
        try {
            email = gitHubApiClient.resolveUserEmail(username.trim()).block();
        } catch (Exception e) {
            log.debug("Failed to resolve email for {}: {}", username, e.getMessage());
        }
        if (email == null) email = "";
        return ResponseEntity.ok(Map.of(
                "username", username.trim(),
                "email", email,
                "found", !email.isBlank()
        ));
    }

    @PostMapping("/profile/test-email")
    public ResponseEntity<?> sendTestEmail(
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "email", required = false) String emailParam) {

        String targetEmail = (emailParam != null && !emailParam.isBlank()
                && !emailParam.contains("Resolving")
                && !emailParam.contains("No public email")) ? emailParam.trim() : null;

        UserProfile profile = null;
        String requestedUser = (username != null && !username.isBlank()) ? username.trim() : null;
        if (requestedUser != null) {
            profile = userProfileRepository.findByGithubUsernameIgnoreCase(requestedUser).orElse(null);
            if (profile == null) {
                profile = new UserProfile();
                profile.setGithubUsername(requestedUser);
                profile.setAutoTriageEnabled(true);
                profile.setCreatedAt(Instant.now());
                profile.setUpdatedAt(Instant.now());
                profile = userProfileRepository.save(profile);
            }
        } else {
            profile = userProfileRepository.findAll().stream().findFirst().orElse(null);
        }

        if (targetEmail == null || targetEmail.isBlank()) {
            if (profile != null && profile.getNotificationEmail() != null && !profile.getNotificationEmail().isBlank()
                    && !profile.getNotificationEmail().contains("Resolving")
                    && !profile.getNotificationEmail().contains("No public email")) {
                targetEmail = profile.getNotificationEmail().trim();
            }
        }

        if (targetEmail == null || targetEmail.isBlank()) {
            String u = (profile != null && profile.getGithubUsername() != null && !profile.getGithubUsername().isBlank())
                    ? profile.getGithubUsername()
                    : (username != null ? username.trim() : "");
            if (!u.isBlank()) {
                try {
                    targetEmail = gitHubApiClient.resolveUserEmail(u).block();
                    if (targetEmail != null && !targetEmail.isBlank() && profile != null) {
                        profile.setNotificationEmail(targetEmail);
                        userProfileRepository.save(profile);
                    }
                } catch (Exception ignored) {}
            }
        }

        if (targetEmail == null || targetEmail.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "message", "No destination email found. Please enter your email in the top bar or Profile Settings so triage alerts can be delivered."
            ));
        }

        if (profile != null && (profile.getNotificationEmail() == null || profile.getNotificationEmail().isBlank())) {
            profile.setNotificationEmail(targetEmail);
            userProfileRepository.save(profile);
        }

        PrAnalysis latest = prAnalysisRepository.findAll(
                PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "createdAt"))
        ).getContent().stream().findFirst().orElse(null);

        if (latest == null) {
            latest = new PrAnalysis();
            latest.setOwner((profile != null && profile.getGithubUsername() != null && !profile.getGithubUsername().isBlank()) ? profile.getGithubUsername() : "owner");
            latest.setRepo("Placement-Preparation-Hub");
            latest.setPrNumber(1);
            latest.setCommitSha("test-" + System.currentTimeMillis());
            latest.setStatus("COMPLETED");
            latest.setTitle("feat: Automated Pull Request Verification");
            latest.setAuthor((profile != null && profile.getGithubUsername() != null && !profile.getGithubUsername().isBlank()) ? profile.getGithubUsername() : "developer");
            latest.setTier("YELLOW");
            latest.setSecurityFlag(false);
            latest.setSummary("Title: feat: Automated Pull Request Verification\nPurpose: Verifying single-click Approve / Reject decision links delivered straight to your GitHub-linked Gmail.\nScope: 1 file modified.\nRisk: MEDIUM - Routine pull request verification.\nRecommendation: Ready for review.");
            latest.setCreatedAt(Instant.now());
            latest.setActionTaken(false);
            latest = prAnalysisRepository.save(latest);
        }

        try {
            var result = thresholdAlertService.sendTriageReport(latest, List.of(targetEmail), true);
            if (result.success()) {
                return ResponseEntity.ok(Map.of(
                        "status", "SUCCESS",
                        "email", targetEmail,
                        "message", "Triage decision email sent directly to " + String.join(", ", result.recipients()) + " with Approve & Reject action buttons!"
                ));
            } else {
                return ResponseEntity.status(500).body(Map.of(
                        "status", "ERROR",
                        "email", targetEmail,
                        "message", "Failed to deliver email: " + result.message()
                ));
            }
        } catch (Exception e) {
            log.error("Failed to send test email: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "status", "ERROR",
                    "email", targetEmail,
                    "message", "Failed to send email: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/history")
    public ResponseEntity<?> getHistory(
            @RequestParam(value = "user", required = false) String user,
            @RequestParam(value = "tier", required = false) String tier,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {

        List<PrAnalysis> all;
        if (user != null && !user.isBlank()) {
            all = prAnalysisRepository.findByUser(
                    user.trim(),
                    PageRequest.of(0, Math.min(limit, 100))
            );
        } else if (search != null && !search.isBlank()) {
            // Allows extension or global search across repos
            all = prAnalysisRepository.findAll(
                    PageRequest.of(0, Math.min(limit, 100), Sort.by(Sort.Direction.DESC, "createdAt"))
            ).getContent();
        } else {
            // Empty list on initial load when no user is specified
            all = Collections.emptyList();
        }

        List<PrAnalysis> filtered = all.stream().filter(pr -> {
            if (tier != null && !tier.isBlank() && !"ALL".equalsIgnoreCase(tier)) {
                if ("SECURITY".equalsIgnoreCase(tier)) {
                    if (!Boolean.TRUE.equals(pr.getSecurityFlag())) return false;
                } else if (!tier.equalsIgnoreCase(pr.getTier())) {
                    return false;
                }
            }
            if (search != null && !search.isBlank()) {
                String q = search.toLowerCase().trim();
                String target = (pr.getOwner() + "/" + pr.getRepo() + "#" + pr.getPrNumber()
                        + " " + (pr.getTitle() != null ? pr.getTitle() : "")
                        + " " + (pr.getAuthor() != null ? pr.getAuthor() : "")
                        + " " + (pr.getSummary() != null ? pr.getSummary() : "")).toLowerCase();
                return target.contains(q);
            }
            return true;
        }).toList();

        List<Map<String, Object>> responseList = filtered.stream().map(pr -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", pr.getId());
            map.put("owner", pr.getOwner());
            map.put("repo", pr.getRepo());
            map.put("prNumber", pr.getPrNumber());
            map.put("title", pr.getTitle() != null ? pr.getTitle() : "PR #" + pr.getPrNumber());
            map.put("author", pr.getAuthor() != null ? pr.getAuthor() : "unknown");
            map.put("tier", pr.getTier());
            map.put("securityFlag", Boolean.TRUE.equals(pr.getSecurityFlag()));
            map.put("bugFlag", pr.getFindingsJson() != null && pr.getFindingsJson().contains("\"category\":\"BUG_DETECTION\""));
            map.put("summary", pr.getSummary() != null ? pr.getSummary() : "");
            map.put("fullSummary", pr.getSummary() != null ? pr.getSummary() : "");
            map.put("findingsJson", pr.getFindingsJson() != null ? pr.getFindingsJson() : "[]");
            map.put("filesChangedCount", pr.getFilesChangedCount() != null ? pr.getFilesChangedCount() : 0);
            map.put("status", pr.getStatus() != null ? pr.getStatus() : "NEW");
            map.put("actionTaken", Boolean.TRUE.equals(pr.getActionTaken()));
            boolean isClosed = Boolean.TRUE.equals(pr.getClosed())
                    || "CLOSED".equalsIgnoreCase(pr.getStatus())
                    || "close".equalsIgnoreCase(pr.getActionType())
                    || "reject".equalsIgnoreCase(pr.getActionType());
            map.put("closed", isClosed);
            map.put("actionType", pr.getActionType() != null ? pr.getActionType() : (isClosed ? "close" : ""));
            String rep = pr.getAuthorReputation();
            String repDetail = pr.getAuthorReputationDetail();
            String prAuthor = pr.getAuthor();
            long priorCount = (prAuthor != null && !prAuthor.isBlank())
                    ? prAnalysisRepository.countPriorPrsByAuthor(pr.getOwner(), pr.getRepo(), prAuthor, pr.getPrNumber())
                    : 0;

            if (priorCount > 0 && !"TRUSTED_MAINTAINER".equalsIgnoreCase(rep)) {
                rep = "RETURNING_CONTRIBUTOR";
                repDetail = "Returning Contributor (" + (priorCount + 1) + " contributions to this repository)";
            } else if (rep == null || rep.isBlank()) {
                rep = "FIRST_TIME_CONTRIBUTOR";
                repDetail = "External Contributor (standard review priority)";
            }

            map.put("authorReputation", rep);
            map.put("authorReputationDetail", repDetail);
            map.put("repoContext", pr.getRepoContext() != null ? pr.getRepoContext() : "");
            map.put("changeSummaryBefore", pr.getChangeSummaryBefore() != null ? pr.getChangeSummaryBefore() : "");
            map.put("changeSummaryAfter", pr.getChangeSummaryAfter() != null ? pr.getChangeSummaryAfter() : "");
            if (tokenService != null) {
                map.put("approveUrl", tokenService.buildActionUrl(pr.getOwner(), pr.getRepo(), pr.getPrNumber(), "approve"));
                map.put("changesUrl", tokenService.buildActionUrl(pr.getOwner(), pr.getRepo(), pr.getPrNumber(), "request-changes"));
                map.put("closeUrl", tokenService.buildActionUrl(pr.getOwner(), pr.getRepo(), pr.getPrNumber(), "close"));
            } else {
                map.put("approveUrl", "");
                map.put("changesUrl", "");
                map.put("closeUrl", "");
            }
            return map;
        }).toList();

        return ResponseEntity.ok(responseList);
    }

    @GetMapping("/history/{id}")
    public ResponseEntity<?> getHistoryItem(@PathVariable("id") Long id) {
        return prAnalysisRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/stats")
    public ResponseEntity<?> getStats(@RequestParam(value = "user", required = false) String user) {
        List<PrAnalysis> all = (user != null && !user.isBlank())
                ? prAnalysisRepository.findAllByUser(user.trim())
                : Collections.emptyList();
        long red = all.stream().filter(p -> "RED".equalsIgnoreCase(p.getTier())).count();
        long yellow = all.stream().filter(p -> "YELLOW".equalsIgnoreCase(p.getTier())).count();
        long green = all.stream().filter(p -> "GREEN".equalsIgnoreCase(p.getTier())).count();
        long security = all.stream().filter(p -> Boolean.TRUE.equals(p.getSecurityFlag())).count();
        long actioned = all.stream().filter(p -> Boolean.TRUE.equals(p.getActionTaken())).count();
        long closed = all.stream().filter(p ->
                Boolean.TRUE.equals(p.getClosed())
                || "CLOSED".equalsIgnoreCase(p.getStatus())
                || "close".equalsIgnoreCase(p.getActionType())
                || "reject".equalsIgnoreCase(p.getActionType())
        ).count();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", all.size());
        stats.put("totalCount", all.size());
        stats.put("red", red);
        stats.put("redCount", red);
        stats.put("yellow", yellow);
        stats.put("yellowCount", yellow);
        stats.put("green", green);
        stats.put("greenCount", green);
        stats.put("security", security);
        stats.put("securityCount", security);
        stats.put("actioned", actioned);
        stats.put("actionedCount", actioned);
        stats.put("closed", closed);
        stats.put("closedCount", closed);
        stats.put("open", all.size() - closed);
        stats.put("openCount", all.size() - closed);

        return ResponseEntity.ok(stats);
    }
}
