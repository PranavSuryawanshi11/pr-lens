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
import org.springframework.web.bind.annotation.RequestParam;
import com.bot.bot.config.GitHubProperties;
import com.bot.bot.config.AppProperties;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Controller
public class DashboardController {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final PrAnalysisRepository prAnalysisRepository;
    private final TokenService tokenService;
    private final UserProfileRepository userProfileRepository;
    private final EmailTemplate emailTemplate;
    private final GitHubApiClient gitHubApiClient;
    private final GitHubProperties gitHubProperties;
    private final AppProperties appProperties;

    public DashboardController(PrAnalysisRepository prAnalysisRepository, TokenService tokenService) {
        this(prAnalysisRepository, tokenService, null, null, null, null, null);
    }

    public DashboardController(PrAnalysisRepository prAnalysisRepository,
                               TokenService tokenService,
                               UserProfileRepository userProfileRepository,
                               EmailTemplate emailTemplate) {
        this(prAnalysisRepository, tokenService, userProfileRepository, emailTemplate, null, null, null);
    }

    public DashboardController(PrAnalysisRepository prAnalysisRepository,
                               TokenService tokenService,
                               UserProfileRepository userProfileRepository,
                               EmailTemplate emailTemplate,
                               GitHubApiClient gitHubApiClient) {
        this(prAnalysisRepository, tokenService, userProfileRepository, emailTemplate, gitHubApiClient, null, null);
    }

    @Autowired
    public DashboardController(PrAnalysisRepository prAnalysisRepository,
                               TokenService tokenService,
                               @Autowired(required = false) UserProfileRepository userProfileRepository,
                               @Autowired(required = false) EmailTemplate emailTemplate,
                               @Autowired(required = false) GitHubApiClient gitHubApiClient,
                               @Autowired(required = false) GitHubProperties gitHubProperties,
                               @Autowired(required = false) AppProperties appProperties) {
        this.prAnalysisRepository = prAnalysisRepository;
        this.tokenService = tokenService;
        this.userProfileRepository = userProfileRepository;
        this.emailTemplate = emailTemplate != null ? emailTemplate : new EmailTemplate(tokenService);
        this.gitHubApiClient = gitHubApiClient;
        this.gitHubProperties = gitHubProperties;
        this.appProperties = appProperties;
    }

    @GetMapping("/setup")
    public String handleSetup(@RequestParam(value = "installation_id", required = false) String installationId,
                              @RequestParam(value = "setup_action", required = false) String setupAction,
                              @RequestParam(value = "code", required = false) String code) {
        log.info("GitHub App setup callback: installation_id={}, setup_action={}, codePresent={}",
                installationId, setupAction, (code != null && !code.isBlank()));

        if (code != null && !code.isBlank()) {
            try {
                java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create("https://api.github.com/app-manifests/" + code.trim() + "/conversions"))
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "PR-Lens-Bot")
                        .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                        .build();

                java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 201 || resp.statusCode() == 200) {
                    com.fasterxml.jackson.databind.JsonNode node =
                            new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp.body());
                    String slug = node.path("slug").asText();
                    String appId = node.path("id").asText();
                    String pem = node.path("pem").asText();

                    log.info("Successfully converted GitHub App Manifest: slug={}, appId={}", slug, appId);

                    if (gitHubProperties != null) {
                        if (slug != null && !slug.isBlank()) gitHubProperties.setAppSlug(slug);
                        if (appId != null && !appId.isBlank()) gitHubProperties.setAppId(appId);
                    }

                    if (pem != null && !pem.isBlank()) {
                        try {
                            java.nio.file.Path certPath = java.nio.file.Paths.get("certs", "github-app.pem");
                            if (certPath.getParent() != null) {
                                java.nio.file.Files.createDirectories(certPath.getParent());
                            }
                            java.nio.file.Files.writeString(certPath, pem);
                            log.info("Persisted GitHub App private key to {}", certPath.toAbsolutePath());
                        } catch (Exception ex) {
                            log.warn("Could not save GitHub App PEM: {}", ex.getMessage());
                        }
                    }

                    persistAppSlugToEnv(slug, appId);

                    // Redirect immediately to installation for this newly created user-owned app!
                    return "redirect:https://github.com/apps/" + slug + "/installations/new";
                } else {
                    log.error("Failed to convert manifest code: status={}, body={}", resp.statusCode(), resp.body());
                }
            } catch (Exception e) {
                log.error("Error exchanging manifest code: {}", e.getMessage(), e);
            }
        }

        return "redirect:/?installed=true" + (installationId != null ? "&installation_id=" + installationId : "");
    }

    private void persistAppSlugToEnv(String slug, String appId) {
        if (slug == null || slug.isBlank()) return;
        try {
            java.nio.file.Path envPath = java.nio.file.Paths.get("bot", ".env");
            if (!java.nio.file.Files.exists(envPath)) {
                envPath = java.nio.file.Paths.get(".env");
            }
            if (java.nio.file.Files.exists(envPath)) {
                List<String> lines = java.nio.file.Files.readAllLines(envPath);
                List<String> newLines = new java.util.ArrayList<>();
                boolean foundSlug = false;
                boolean foundAppId = false;
                for (String line : lines) {
                    if (line.startsWith("GITHUB_APP_SLUG=")) {
                        newLines.add("GITHUB_APP_SLUG=" + slug);
                        foundSlug = true;
                    } else if (line.startsWith("GITHUB_APP_ID=") || line.startsWith("# GITHUB_APP_ID=")) {
                        newLines.add("GITHUB_APP_ID=" + (appId != null ? appId : ""));
                        foundAppId = true;
                    } else {
                        newLines.add(line);
                    }
                }
                if (!foundSlug) newLines.add("GITHUB_APP_SLUG=" + slug);
                if (!foundAppId && appId != null && !appId.isBlank()) newLines.add("GITHUB_APP_ID=" + appId);
                java.nio.file.Files.write(envPath, newLines);
                log.info("Persisted GITHUB_APP_SLUG={} and GITHUB_APP_ID={} to {}", slug, appId, envPath.toAbsolutePath());
            }
        } catch (Exception e) {
            log.warn("Could not persist GitHub App slug to .env: {}", e.getMessage());
        }
    }

    @GetMapping("/")
    public String dashboard(Model model,
                            @RequestParam(value = "user", required = false) String user,
                            @RequestParam(value = "installed", required = false) String installed) {
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
        long closedCount = allForStats.stream().filter(p ->
                Boolean.TRUE.equals(p.getClosed())
                || "CLOSED".equalsIgnoreCase(p.getStatus())
                || "close".equalsIgnoreCase(p.getActionType())
                || "reject".equalsIgnoreCase(p.getActionType())
        ).count();

        model.addAttribute("rows", rows);
        model.addAttribute("empty", rows.isEmpty());
        model.addAttribute("totalCount", allForStats.size());
        model.addAttribute("redCount", redCount);
        model.addAttribute("yellowCount", yellowCount);
        model.addAttribute("greenCount", greenCount);
        model.addAttribute("securityCount", securityCount);
        model.addAttribute("actionedCount", actionedCount);
        model.addAttribute("closedCount", closedCount);
        model.addAttribute("openCount", allForStats.size() - closedCount);
        model.addAttribute("profile", profile);
        model.addAttribute("activeUser", activeUser != null ? activeUser : "");

        boolean appConfigured = gitHubProperties != null && gitHubProperties.hasAppCredentials();
        String appSlug = (gitHubProperties != null && gitHubProperties.getAppSlug() != null && !gitHubProperties.getAppSlug().isBlank())
                ? gitHubProperties.getAppSlug().trim() : "";
        String installUrl = (gitHubProperties != null) ? gitHubProperties.getInstallUrl() : null;
        String publicAppUrl = (gitHubProperties != null) ? gitHubProperties.getPublicAppUrl() : null;
        String baseUrl = (appProperties != null && appProperties.getBaseUrl() != null && !appProperties.getBaseUrl().isBlank())
                ? appProperties.getBaseUrl().trim() : "http://localhost:8080";
        String webhookUrl = baseUrl.replaceAll("/+$", "") + "/api/webhook";

        model.addAttribute("githubAppConfigured", appConfigured);
        model.addAttribute("githubAppSlug", appSlug);
        model.addAttribute("githubAppId", (gitHubProperties != null && gitHubProperties.getAppId() != null) ? gitHubProperties.getAppId() : "");
        model.addAttribute("githubAppInstallUrl", installUrl);
        model.addAttribute("githubPublicAppUrl", publicAppUrl);
        model.addAttribute("baseUrl", baseUrl);
        model.addAttribute("webhookUrl", webhookUrl);
        model.addAttribute("installed", "true".equalsIgnoreCase(installed) || (installed != null && !installed.isBlank()));

        return "dashboard";
    }

    public String dashboard(Model model, String user) {
        return dashboard(model, user, null);
    }

    public String dashboard(Model model) {
        return dashboard(model, null, null);
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
        boolean isClosed = Boolean.TRUE.equals(a.getClosed())
                || "CLOSED".equalsIgnoreCase(a.getStatus())
                || "close".equalsIgnoreCase(a.getActionType())
                || "reject".equalsIgnoreCase(a.getActionType());
        String actionType = a.getActionType() != null ? a.getActionType() : (isClosed ? "close" : "");

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
                isClosed,
                actionType,
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
            boolean closed,
            String actionType,
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
        String subject = "[PR-Lens Alert] Pull Request Requiring Review";
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
