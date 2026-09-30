package com.bot.bot.web;

import com.bot.bot.actions.TokenService;
import com.bot.bot.persistence.PrAnalysis;
import com.bot.bot.persistence.PrAnalysisRepository;
import com.bot.bot.persistence.UserProfile;
import com.bot.bot.persistence.UserProfileRepository;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

import com.bot.bot.github.GitHubApiClient;
import org.springframework.beans.factory.annotation.Autowired;
import com.bot.bot.email.EmailTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final PrAnalysisRepository prAnalysisRepository;
    private final TokenService tokenService;
    private final UserProfileRepository userProfileRepository;
    private final EmailTemplate emailTemplate;
    private final GitHubApiClient gitHubApiClient;

    public DashboardController(PrAnalysisRepository prAnalysisRepository, TokenService tokenService) {
        this(prAnalysisRepository, tokenService, null, null, null);
    }

    public DashboardController(PrAnalysisRepository prAnalysisRepository,
                               TokenService tokenService,
                               UserProfileRepository userProfileRepository,
                               EmailTemplate emailTemplate) {
        this(prAnalysisRepository, tokenService, userProfileRepository, emailTemplate, null);
    }

    @Autowired
    public DashboardController(PrAnalysisRepository prAnalysisRepository,
                               TokenService tokenService,
                               @Autowired(required = false) UserProfileRepository userProfileRepository,
                               @Autowired(required = false) EmailTemplate emailTemplate,
                               @Autowired(required = false) GitHubApiClient gitHubApiClient) {
        this.prAnalysisRepository = prAnalysisRepository;
        this.tokenService = tokenService;
        this.userProfileRepository = userProfileRepository;
        this.emailTemplate = emailTemplate != null ? emailTemplate : new EmailTemplate(tokenService);
        this.gitHubApiClient = gitHubApiClient;
    }

    @GetMapping("/")
    public String dashboard(Model model, @org.springframework.web.bind.annotation.RequestParam(value = "user", required = false) String user) {
        String activeUser = (user != null && !user.isBlank()) ? user.trim() : null;

        UserProfile profile = null;
        if (userProfileRepository != null && activeUser != null) {
            profile = userProfileRepository.findByGithubUsernameIgnoreCase(activeUser).orElse(null);
        }

        List<PrAnalysis> all;
        List<PrAnalysis> allForStats;
        if (activeUser != null) {
            all = prAnalysisRepository.findByUser(activeUser, PageRequest.of(0, 100));
            allForStats = prAnalysisRepository.findAllByUser(activeUser);
        } else {
            // When user first opens the project with no account specified:
            if (userProfileRepository == null) {
                // Compatibility for unit tests that run without a profile repository
                all = prAnalysisRepository.findAll(
                        PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "createdAt"))
                ).getContent();
                allForStats = all;
            } else {
                // Production: do NOT load all PRs across the database on initial empty launch!
                all = List.of();
                allForStats = List.of();
            }
        }

        if (profile == null) {
            profile = new UserProfile();
            profile.setGithubUsername(activeUser != null ? activeUser : "");
            profile.setNotificationEmail("");
            profile.setAutoTriageEnabled(true);
            profile.setMonitoredRepos("");

            if (activeUser != null && gitHubApiClient != null) {
                try {
                    String email = gitHubApiClient.resolveUserEmail(activeUser).block();
                    if (email != null && !email.isBlank()) {
                        profile.setNotificationEmail(email);
                    }
                    if (userProfileRepository != null) {
                        profile = userProfileRepository.save(profile);
                    }
                } catch (Exception ignored) {}
            }
        } else if ((profile.getNotificationEmail() == null || profile.getNotificationEmail().isBlank()) && gitHubApiClient != null && activeUser != null) {
            try {
                String email = gitHubApiClient.resolveUserEmail(profile.getGithubUsername()).block();
                if (email != null && !email.isBlank()) {
                    profile.setNotificationEmail(email);
                    if (userProfileRepository != null) {
                        profile = userProfileRepository.save(profile);
                    }
                }
            } catch (Exception ignored) {}
        }

        List<Row> rows = all.stream().map(this::toRow).collect(Collectors.toList());

        long redCount = allForStats.stream().filter(p -> "RED".equalsIgnoreCase(p.getTier())).count();
        long yellowCount = allForStats.stream().filter(p -> "YELLOW".equalsIgnoreCase(p.getTier())).count();
        long greenCount = allForStats.stream().filter(p -> "GREEN".equalsIgnoreCase(p.getTier())).count();
        long securityCount = allForStats.stream().filter(p -> Boolean.TRUE.equals(p.getSecurityFlag())).count();
        long actionedCount = allForStats.stream().filter(p -> Boolean.TRUE.equals(p.getActionTaken())).count();

        model.addAttribute("rows", rows);
        model.addAttribute("empty", rows.isEmpty());
        model.addAttribute("totalCount", allForStats.size());
        model.addAttribute("redCount", redCount);
        model.addAttribute("yellowCount", yellowCount);
        model.addAttribute("greenCount", greenCount);
        model.addAttribute("securityCount", securityCount);
        model.addAttribute("actionedCount", actionedCount);
        model.addAttribute("profile", profile);
        model.addAttribute("activeUser", activeUser != null ? activeUser : "");

        return "dashboard";
    }

    public String dashboard(Model model) {
        return dashboard(model, null);
    }

    private Row toRow(PrAnalysis a) {
        String approve = tokenService.buildActionUrl(a.getOwner(), a.getRepo(), a.getPrNumber(), "approve");
        String changes = tokenService.buildActionUrl(a.getOwner(), a.getRepo(), a.getPrNumber(), "request-changes");
        String close   = tokenService.buildActionUrl(a.getOwner(), a.getRepo(), a.getPrNumber(), "close");

        String timeStr = a.getCreatedAt() != null ? DATE_FMT.format(a.getCreatedAt()) : "";
        String title = (a.getTitle() != null && !a.getTitle().isBlank()) ? a.getTitle() : "PR #" + a.getPrNumber();
        String author = a.getAuthor() != null ? a.getAuthor() : "unknown";

        String rep = a.getAuthorReputation();
        String repDetail = a.getAuthorReputationDetail();
        long priorCount = (a.getAuthor() != null && !a.getAuthor().isBlank())
                ? prAnalysisRepository.countPriorPrsByAuthor(a.getOwner(), a.getRepo(), a.getAuthor(), a.getPrNumber())
                : 0;

        if (priorCount > 0 && !"TRUSTED_MAINTAINER".equalsIgnoreCase(rep)) {
            rep = "RETURNING_CONTRIBUTOR";
            repDetail = "Returning Contributor (" + (priorCount + 1) + " contributions to this repository)";
        } else if (rep == null || rep.isBlank()) {
            rep = "FIRST_TIME_CONTRIBUTOR";
            repDetail = "External Contributor (standard review priority)";
        }

        boolean hasBug = a.getFindingsJson() != null && a.getFindingsJson().contains("\"category\":\"BUG_DETECTION\"");

        return new Row(
                a.getId(),
                a.getOwner(),
                a.getRepo(),
                a.getPrNumber(),
                title,
                author,
                a.getOwner() + "/" + a.getRepo() + " #" + a.getPrNumber(),
                a.getTier(),
                Boolean.TRUE.equals(a.getSecurityFlag()),
                hasBug,
                tierEmoji(a),
                snippet(a.getSummary()),
                a.getSummary() != null ? a.getSummary() : "",
                a.getFindingsJson() != null ? a.getFindingsJson() : "[]",
                a.getFilesChangedCount() != null ? a.getFilesChangedCount() : 0,
                a.getStatus(),
                Boolean.TRUE.equals(a.getActionTaken()),
                approve,
                changes,
                close,
                timeStr,
                rep,
                repDetail,
                a.getChangeSummaryBefore() != null ? a.getChangeSummaryBefore() : "",
                a.getChangeSummaryAfter() != null ? a.getChangeSummaryAfter() : "",
                a.getRepoContext() != null ? a.getRepoContext() : "");
    }

    private String tierEmoji(PrAnalysis a) {
        if (Boolean.TRUE.equals(a.getSecurityFlag())) return "🔒";
        return switch (a.getTier() == null ? "" : a.getTier().toUpperCase()) {
            case "RED" -> "🔴";
            case "YELLOW" -> "🟡";
            case "GREEN" -> "🟢";
            default -> "⚪";
        };
    }

    private String snippet(String s) {
        if (s == null) return "";
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    public record Row(
            Long id,
            String owner,
            String repo,
            int prNumber,
            String title,
            String author,
            String pr,
            String tier,
            boolean security,
            boolean bugFlag,
            String emoji,
            String summary,
            String fullSummary,
            String findingsJson,
            int filesCount,
            String status,
            boolean actioned,
            String approveUrl,
            String changesUrl,
            String closeUrl,
            String createdAt,
            String authorReputation,
            String authorReputationDetail,
            String changeSummaryBefore,
            String changeSummaryAfter,
            String repoContext) {
    }

    @GetMapping("/email/preview")
    public String emailPreview(Model model, @org.springframework.web.bind.annotation.RequestParam(value = "user", required = false) String user) {
        String emailHtml = null;
        String subject = "[Triage Alert] Pull Request Requiring Review";
        String prTarget = "No PR specified";
        // In preview mode, do not display anyone's email address
        String recipient = "—";

        PrAnalysis latest = null;
        if (user != null && !user.isBlank()) {
            latest = prAnalysisRepository.findByUser(user.trim(), PageRequest.of(0, 1)).stream().findFirst().orElse(null);
        }
        if (latest == null) {
            latest = prAnalysisRepository.findAll(
                    PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "createdAt"))
            ).getContent().stream().findFirst().orElse(null);
        }

        if (latest != null) {
            prTarget = latest.getOwner() + "/" + latest.getRepo() + " #" + latest.getPrNumber();
            String tier = latest.getTier() != null ? latest.getTier() : "INFO";
            subject = "[" + tier + "] Action Required: " + prTarget;
            if (emailTemplate != null) {
                emailHtml = emailTemplate.renderAlert(latest);
            }
        }

        if (emailHtml == null) {
            try {
                java.nio.file.Path p = java.nio.file.Paths.get("data", "latest_email.html");
                if (java.nio.file.Files.exists(p)) {
                    emailHtml = java.nio.file.Files.readString(p);
                }
            } catch (Exception ignored) {}
        }

        model.addAttribute("hasEmail", emailHtml != null && !emailHtml.isBlank());
        model.addAttribute("emailHtml", emailHtml != null ? emailHtml : "");
        model.addAttribute("subject", subject);
        model.addAttribute("prTarget", prTarget);
        model.addAttribute("recipient", recipient);

        return "email_preview";
    }
}
