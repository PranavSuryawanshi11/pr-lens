package com.bot.bot.web;

import com.bot.bot.actions.TokenService;
import com.bot.bot.email.ThresholdAlertService;
import com.bot.bot.persistence.PrAnalysis;
import com.bot.bot.service.ReviewOrchestrator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REST API for on-demand PR triage.
 * Allows triaging any pull request on any GitHub account using GitHub REST API
 * without requiring pre-configured GitHub App installation.
 */
@Slf4j
@RestController
@RequestMapping("/api/triage")
public class TriageController {

    private static final Pattern PR_URL_PATTERN =
            Pattern.compile("^(?:https?://github\\.com/)?([^/]+)/([^/]+)(?:/pull/|#)(\\d+).*$");

    private final ReviewOrchestrator reviewOrchestrator;
    private final ThresholdAlertService thresholdAlertService;
    private final TokenService tokenService;

    public TriageController(ReviewOrchestrator reviewOrchestrator) {
        this(reviewOrchestrator, null, null);
    }

    @Autowired
    public TriageController(ReviewOrchestrator reviewOrchestrator,
                            @Autowired(required = false) ThresholdAlertService thresholdAlertService,
                            @Autowired(required = false) TokenService tokenService) {
        this.reviewOrchestrator = reviewOrchestrator;
        this.thresholdAlertService = thresholdAlertService;
        this.tokenService = tokenService;
    }

    public record TriageRequest(
            String url,
            String owner,
            String repo,
            Integer prNumber,
            String title,
            String author,
            String diff
    ) {
        public TriageRequest(String url, String owner, String repo, Integer prNumber) {
            this(url, owner, repo, prNumber, null, null, null);
        }
    }

    public record TriageResponse(
            String status,
            String owner,
            String repo,
            int prNumber,
            String tier,
            Boolean securityFlag,
            String summary,
            String actionTaken,
            String message,
            String approveUrl,
            String rejectUrl
    ) {}

    @PostMapping
    public ResponseEntity<?> triagePrPost(@RequestBody TriageRequest request) {
        return executeTriage(request.url(), request.owner(), request.repo(), request.prNumber(),
                request.title(), request.author(), request.diff());
    }

    @GetMapping
    public ResponseEntity<?> triagePrGet(
            @RequestParam(value = "url", required = false) String url,
            @RequestParam(value = "owner", required = false) String owner,
            @RequestParam(value = "repo", required = false) String repo,
            @RequestParam(value = "pr", required = false) Integer prNumber) {
        return executeTriage(url, owner, repo, prNumber, null, null, null);
    }

    private ResponseEntity<?> executeTriage(String url, String owner, String repo, Integer prNumber,
                                           String title, String author, String diff) {
        String targetOwner = owner;
        String targetRepo = repo;
        Integer targetPrNumber = prNumber;

        if (url != null && !url.isBlank()) {
            Matcher matcher = PR_URL_PATTERN.matcher(url.trim());
            if (matcher.find()) {
                targetOwner = matcher.group(1);
                targetRepo = matcher.group(2);
                targetPrNumber = Integer.parseInt(matcher.group(3));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Invalid PR URL. Expected format: https://github.com/owner/repo/pull/123"
                ));
            }
        }

        if (targetOwner == null || targetOwner.isBlank()
                || targetRepo == null || targetRepo.isBlank()
                || targetPrNumber == null || targetPrNumber <= 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Missing owner, repo, or PR number. Provide either 'url' or 'owner', 'repo', 'pr'."
            ));
        }

        try {
            PrAnalysis analysis;
            if (diff != null && !diff.isBlank()) {
                log.info("Executing direct session diff triage for {}/{}/PR#{}", targetOwner, targetRepo, targetPrNumber);
                analysis = reviewOrchestrator.triagePullRequestWithDiff(targetOwner, targetRepo, targetPrNumber, title, author, diff).block();
            } else {
                log.info("Executing on-demand triage for {}/{}/PR#{}", targetOwner, targetRepo, targetPrNumber);
                analysis = reviewOrchestrator.triagePullRequest(targetOwner, targetRepo, targetPrNumber).block();
            }

            if (analysis != null) {
                if (thresholdAlertService != null) {
                    try {
                        thresholdAlertService.sendTriageReport(analysis, null);
                    } catch (Exception e) {
                        log.warn("Could not dispatch triage report email: {}", e.getMessage());
                    }
                }
                String approve = tokenService != null ? tokenService.buildActionUrl(analysis.getOwner(), analysis.getRepo(), analysis.getPrNumber(), "approve") : "";
                String reject  = tokenService != null ? tokenService.buildActionUrl(analysis.getOwner(), analysis.getRepo(), analysis.getPrNumber(), "reject") : "";

                return ResponseEntity.ok(new TriageResponse(
                        "SUCCESS",
                        analysis.getOwner(),
                        analysis.getRepo(),
                        analysis.getPrNumber(),
                        analysis.getTier(),
                        analysis.getSecurityFlag(),
                        analysis.getSummary(),
                        String.valueOf(analysis.getActionTaken()),
                        "PR triaged successfully. View details on the dashboard at /",
                        approve,
                        reject
                ));
            } else {
                return ResponseEntity.ok(Map.of(
                        "status", "ALREADY_ANALYZED",
                        "message", "PR was already analyzed and commit SHA has not changed."
                ));
            }
        } catch (Exception e) {
            log.error("Failed on-demand triage for {}/{}/PR#{}", targetOwner, targetRepo, targetPrNumber, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "status", "ERROR",
                    "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
            ));
        }
    }
}
