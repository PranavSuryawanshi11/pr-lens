package com.bot.bot.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bot.bot.config.ConfigService;
import com.bot.bot.github.GitHubApiClient;
import com.bot.bot.persistence.PrAnalysis;
import com.bot.bot.persistence.PrAnalysisRepository;
import com.bot.bot.persistence.UserProfile;
import com.bot.bot.persistence.UserProfileRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * Sends an immediate email alert when a PR analysis lands at or above the
 * installation's configured threshold tier or carries a security flag. Deduped
 * via the {@code alerted} column so each PR is alerted at most once. No-op when
 * the installation has email disabled or the PR is not urgent.
 */
@Service
public class ThresholdAlertService {
    private static final Logger log = LoggerFactory.getLogger(ThresholdAlertService.class);

    private static final List<String> TIER_RANK = List.of("GREEN", "YELLOW", "RED");

    private final PrAnalysisRepository prAnalysisRepository;
    private final MailService mailService;
    private final EmailTemplate emailTemplate;
    private final ConfigService configService;
    private final GitHubApiClient gitHubApiClient;
    private final UserProfileRepository userProfileRepository;

    public ThresholdAlertService(PrAnalysisRepository prAnalysisRepository, MailService mailService,
                                 ConfigService configService, EmailTemplate emailTemplate) {
        this(prAnalysisRepository, mailService, configService, emailTemplate, null, null);
    }

    @Autowired
    public ThresholdAlertService(PrAnalysisRepository prAnalysisRepository, MailService mailService,
                                 ConfigService configService, EmailTemplate emailTemplate,
                                 @Autowired(required = false) GitHubApiClient gitHubApiClient,
                                 @Autowired(required = false) UserProfileRepository userProfileRepository) {
        this.prAnalysisRepository = prAnalysisRepository;
        this.mailService = mailService;
        this.configService = configService;
        this.emailTemplate = emailTemplate;
        this.gitHubApiClient = gitHubApiClient;
        this.userProfileRepository = userProfileRepository;
    }

    public void maybeAlert(PrAnalysis analysis) {
        ConfigService.ResolvedConfig cfg = configService.resolve(
                analysis.getInstallationId() == null ? "" : analysis.getInstallationId());
        if (!cfg.emailEnabled()) {
            return;
        }
        if (!isUrgent(analysis, cfg.thresholdTier())) {
            return;
        }
        if (Boolean.TRUE.equals(analysis.getAlerted())) {
            return;
        }
        List<String> to = resolveRecipients(analysis, null);
        MailService.SendResult result = mailService.sendEmailWithStatus(to,
                buildSubject(analysis),
                emailTemplate.renderAlert(analysis));
        if (result.success()) {
            analysis.setAlerted(true);
            prAnalysisRepository.save(analysis);
            log.info("Alerted maintainers for urgent PR {}/{}#{} to {}",
                    analysis.getOwner(), analysis.getRepo(), analysis.getPrNumber(), to);
        } else {
            log.warn("Failed to dispatch alert email for PR {}/{}#{}: {}",
                    analysis.getOwner(), analysis.getRepo(), analysis.getPrNumber(), result.message());
        }
    }

    public void sendTriageReport(PrAnalysis analysis, List<String> recipients) {
        sendTriageReport(analysis, recipients, false);
    }

    public MailService.SendResult sendTriageReport(PrAnalysis analysis, List<String> recipients, boolean force) {
        if (analysis == null) {
            return new MailService.SendResult(false, "No analysis provided", List.of());
        }
        if (!force && Boolean.TRUE.equals(analysis.getAlerted())) {
            log.debug("PR {}/{}#{} was already alerted - skipping duplicate report",
                    analysis.getOwner(), analysis.getRepo(), analysis.getPrNumber());
            return new MailService.SendResult(false, "Already alerted", recipients != null ? recipients : List.of());
        }
        List<String> to = resolveRecipients(analysis, recipients);
        MailService.SendResult result = mailService.sendEmailWithStatus(to, buildSubject(analysis), emailTemplate.renderAlert(analysis));
        if (result.success()) {
            analysis.setAlerted(true);
            prAnalysisRepository.save(analysis);
            log.info("Successfully sent triage report email for {}/{}#{} to {}",
                    analysis.getOwner(), analysis.getRepo(), analysis.getPrNumber(), to);
        } else {
            log.warn("Failed to send triage report email for {}/{}#{}: {}",
                    analysis.getOwner(), analysis.getRepo(), analysis.getPrNumber(), result.message());
        }
        return result;
    }

    /**
     * Automatically resolves the recipient email(s) directly from the registered GitHub account,
     * repository owner, or user profile without requiring manual email input.
     * Always ensures maintainers configured in application settings also receive triage reports.
     */
    public List<String> resolveRecipients(PrAnalysis analysis, List<String> explicitRecipients) {
        java.util.Set<String> recipientSet = new java.util.LinkedHashSet<>();

        // 1. Explicit recipients provided by caller
        if (explicitRecipients != null) {
            for (String e : explicitRecipients) {
                if (isValidEmail(e)) {
                    recipientSet.add(e.trim().toLowerCase());
                }
            }
        }

        String owner = (analysis != null && analysis.getOwner() != null) ? analysis.getOwner().trim() : null;

        // 2. Check UserProfile repository for repository owner
        UserProfile ownerProfile = null;
        if (owner != null && userProfileRepository != null) {
            ownerProfile = userProfileRepository.findByGithubUsernameIgnoreCase(owner).orElse(null);
            if (ownerProfile != null && isValidEmail(ownerProfile.getNotificationEmail())) {
                recipientSet.add(ownerProfile.getNotificationEmail().trim().toLowerCase());
                log.info("Resolved recipient from saved owner profile ({}): {}", owner, ownerProfile.getNotificationEmail().trim());
            }
        }

        // 3. Resolve repository owner GitHub linked email directly (e.g. from git commits or profile)
        if (gitHubApiClient != null && owner != null) {
            try {
                String repo = analysis != null ? analysis.getRepo() : null;
                Integer pr = analysis != null ? analysis.getPrNumber() : null;
                String discoveredEmail = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        return gitHubApiClient.resolveUserEmail(owner, repo, pr).block();
                    } catch (Exception ex) {
                        return "";
                    }
                }).get(6, java.util.concurrent.TimeUnit.SECONDS);

                if (isValidEmail(discoveredEmail)) {
                    recipientSet.add(discoveredEmail.trim().toLowerCase());
                    log.info("Discovered owner GitHub-linked email for {}: {}", owner, discoveredEmail.trim());
                    // Cache in UserProfile for this specific owner so future queries and dashboard have it immediately
                    if (userProfileRepository != null) {
                        if (ownerProfile == null) {
                            ownerProfile = new UserProfile();
                            ownerProfile.setGithubUsername(owner);
                            ownerProfile.setAutoTriageEnabled(true);
                            ownerProfile.setCreatedAt(java.time.Instant.now());
                        }
                        ownerProfile.setNotificationEmail(discoveredEmail.trim());
                        ownerProfile.setUpdatedAt(java.time.Instant.now());
                        userProfileRepository.save(ownerProfile);
                    }
                }
            } catch (Exception e) {
                log.debug("Could not resolve owner email from GitHub: {}", e.getMessage());
            }
        }

        // 4. Maintainer emails from configuration ALWAYS included so maintainers receive notifications
        if (configService != null) {
            String instId = (analysis != null && analysis.getInstallationId() != null) ? analysis.getInstallationId() : "";
            List<String> maintainers = configService.resolve(instId).maintainerEmails();
            if (maintainers != null) {
                for (String m : maintainers) {
                    if (isValidEmail(m)) {
                        recipientSet.add(m.trim().toLowerCase());
                    }
                }
            }
        }

        // 5. Fallback to authenticated GitHub token user email ONLY if still empty
        if (recipientSet.isEmpty() && gitHubApiClient != null) {
            try {
                String authEmail = gitHubApiClient.resolveAuthenticatedUserEmail().block();
                if (isValidEmail(authEmail)) {
                    recipientSet.add(authEmail.trim().toLowerCase());
                }
            } catch (Exception e) {
                log.debug("Could not resolve authenticated user email: {}", e.getMessage());
            }
        }

        // 6. Hard safety net fallback to default maintainer
        if (recipientSet.isEmpty()) {
            recipientSet.add("pranavsuryawanshi955@gmail.com");
        }

        return new ArrayList<>(recipientSet);
    }

    private boolean isValidEmail(String email) {
        if (email == null) return false;
        String trimmed = email.trim();
        return !trimmed.isBlank()
                && trimmed.contains("@")
                && trimmed.contains(".")
                && !trimmed.contains("Resolving")
                && !trimmed.contains("No public email")
                && !trimmed.contains("example.com")
                && !trimmed.endsWith("@noreply.github.com");
    }

    private String buildSubject(PrAnalysis analysis) {
        String tier = analysis.getTier() != null ? analysis.getTier().toUpperCase() : "TRIAGE";
        String riskPrefix = switch (tier) {
            case "RED" -> "[HIGH RISK]";
            case "YELLOW" -> "[MEDIUM RISK]";
            case "GREEN" -> "[LOW RISK]";
            default -> "[" + tier + "]";
        };
        if (Boolean.TRUE.equals(analysis.getSecurityFlag())) {
            riskPrefix = "[SECURITY ALERT]";
        }
        return riskPrefix + " [PR-Lens] " + analysis.getOwner() + "/" + analysis.getRepo()
                + "#" + analysis.getPrNumber();
    }

    private boolean isUrgent(PrAnalysis a, String thresholdTier) {
        if (Boolean.TRUE.equals(a.getSecurityFlag())) {
            return true;
        }
        if (a.getTier() == null) {
            return true;
        }
        // Alert when the PR's tier ranks at or above the configured threshold.
        return rank(a.getTier()) >= rank(thresholdTier);
    }

    private int rank(String tier) {
        int i = TIER_RANK.indexOf(tier == null ? "" : tier.toUpperCase());
        return i < 0 ? rank("YELLOW") : i; // unknown tier treated as YELLOW
    }
}
