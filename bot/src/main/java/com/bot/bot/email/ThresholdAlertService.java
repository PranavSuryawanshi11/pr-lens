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
        mailService.sendEmail(to,
                buildSubject(analysis),
                emailTemplate.renderAlert(analysis));
        analysis.setAlerted(true);
        prAnalysisRepository.save(analysis);
        log.info("Alerted maintainers for urgent PR {}/{}#{} to {}",
                analysis.getOwner(), analysis.getRepo(), analysis.getPrNumber(), to);
    }

    public void sendTriageReport(PrAnalysis analysis, List<String> recipients) {
        if (analysis == null) return;
        if (Boolean.TRUE.equals(analysis.getAlerted())) {
            log.debug("PR {}/{}#{} was already alerted - skipping duplicate report",
                    analysis.getOwner(), analysis.getRepo(), analysis.getPrNumber());
            return;
        }
        List<String> to = resolveRecipients(analysis, recipients);
        mailService.sendEmail(to, buildSubject(analysis), emailTemplate.renderAlert(analysis));
        analysis.setAlerted(true);
        prAnalysisRepository.save(analysis);
    }

    /**
     * Automatically resolves the recipient email(s) directly from the registered GitHub account,
     * repository owner, or user profile without requiring manual email input.
     */
    public List<String> resolveRecipients(PrAnalysis analysis, List<String> explicitRecipients) {
        if (explicitRecipients != null && !explicitRecipients.isEmpty()) {
            List<String> validExplicit = explicitRecipients.stream()
                    .filter(e -> e != null && !e.isBlank() && !e.contains("Resolving") && !e.contains("No public email"))
                    .map(String::trim)
                    .toList();
            if (!validExplicit.isEmpty()) {
                return validExplicit;
            }
        }

        List<String> recipients = new ArrayList<>();
        String owner = (analysis != null && analysis.getOwner() != null) ? analysis.getOwner().trim() : null;

        // 1. Check UserProfile repository strictly for THIS specific repository owner
        UserProfile ownerProfile = null;
        if (owner != null && userProfileRepository != null) {
            ownerProfile = userProfileRepository.findByGithubUsernameIgnoreCase(owner).orElse(null);
            if (ownerProfile != null && ownerProfile.getNotificationEmail() != null
                    && !ownerProfile.getNotificationEmail().isBlank()
                    && !ownerProfile.getNotificationEmail().contains("Resolving")
                    && !ownerProfile.getNotificationEmail().contains("No public email")) {
                recipients.add(ownerProfile.getNotificationEmail().trim());
                log.info("Resolved recipient from saved owner profile ({}): {}", owner, ownerProfile.getNotificationEmail().trim());
            }
        }

        // 2. Resolve repository owner GitHub linked email directly (e.g. from git commits or profile)
        if (recipients.isEmpty() && gitHubApiClient != null && owner != null) {
            try {
                String repo = analysis != null ? analysis.getRepo() : null;
                Integer pr = analysis != null ? analysis.getPrNumber() : null;
                String discoveredEmail = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        return gitHubApiClient.resolveUserEmail(owner, repo, pr).block();
                    } catch (Exception ex) {
                        return "";
                    }
                }).get(10, java.util.concurrent.TimeUnit.SECONDS);

                if (discoveredEmail != null && !discoveredEmail.isBlank()) {
                    recipients.add(discoveredEmail.trim());
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

        // 3. Fallback to authenticated GitHub token user email ONLY if no owner was specified
        if (recipients.isEmpty() && gitHubApiClient != null && (owner == null || owner.isBlank())) {
            try {
                String authEmail = gitHubApiClient.resolveAuthenticatedUserEmail().block();
                if (authEmail != null && !authEmail.isBlank()) {
                    recipients.add(authEmail.trim());
                }
            } catch (Exception e) {
                log.debug("Could not resolve authenticated user email: {}", e.getMessage());
            }
        }

        // 4. Fallback to maintainer emails from configuration
        if (recipients.isEmpty() && configService != null) {
            String instId = (analysis != null && analysis.getInstallationId() != null) ? analysis.getInstallationId() : "";
            recipients.addAll(configService.resolve(instId).maintainerEmails());
        }

        return recipients;
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
        return riskPrefix + " [PR-Triage] " + analysis.getOwner() + "/" + analysis.getRepo()
                + "#" + analysis.getPrNumber();
    }

    private boolean isUrgent(PrAnalysis a, String thresholdTier) {
        if (Boolean.TRUE.equals(a.getSecurityFlag())) {
            return true;
        }
        if (a.getTier() == null) {
            return false;
        }
        // Alert when the PR's tier ranks at or above the configured threshold.
        return rank(a.getTier()) >= rank(thresholdTier);
    }

    private int rank(String tier) {
        int i = TIER_RANK.indexOf(tier == null ? "" : tier.toUpperCase());
        return i < 0 ? rank("YELLOW") : i; // unknown tier treated as YELLOW
    }
}
