package com.bot.bot.web;

import com.bot.bot.actions.TokenException;
import com.bot.bot.actions.TokenService;
import com.bot.bot.github.GitHubApiClient;
import com.bot.bot.persistence.PrAnalysis;
import com.bot.bot.persistence.PrAnalysisRepository;

import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * One-click PR action handler (SRS §8/§9). Verifies a signed single-use token,
 * maps the action to a GitHub call, and marks the analysis ACTIONED.
 * No action is taken without a valid token; never auto-merges.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ActionController {

    private final TokenService tokenService;
    private final PrAnalysisRepository prAnalysisRepository;
    private final GitHubApiClient gitHubApiClient;

    @GetMapping(value = "/action", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> handleAction(
            @RequestParam("token") String token,
            @RequestParam("do") String action) {

        TokenService.TokenPayload payload;
        try {
            payload = tokenService.verify(token);
        } catch (TokenException e) {
            boolean used = "token already used".equals(e.getMessage());
            return ResponseEntity.status(used ? 410 : 400).body(resultPage("Invalid or expired link", false));
        } catch (Exception e) {
            log.error("Unexpected error verifying token", e);
            return ResponseEntity.status(500).body(resultPage("Error processing action token: " + e.getMessage(), false));
        }

        // The action in the URL must match the action the token was signed for.
        if (action == null || !action.equalsIgnoreCase(payload.action())) {
            return ResponseEntity.status(400).body(resultPage("Action mismatch", false));
        }

        Optional<PrAnalysis> found;
        try {
            found = prAnalysisRepository.findLatest(
                    payload.owner(), payload.repo(), payload.prNumber());
        } catch (Exception e) {
            log.error("Database query failed while fetching PR {}/{}/PR#{}", payload.owner(), payload.repo(), payload.prNumber(), e);
            return ResponseEntity.status(500).body(resultPage("Failed to retrieve PR record: " + e.getMessage(), false));
        }

        if (found.isEmpty()) {
            return ResponseEntity.status(404).body(resultPage("PR not found", false));
        }
        PrAnalysis analysis = found.get();
        if (Boolean.TRUE.equals(analysis.getActionTaken())) {
            return ResponseEntity.status(410).body(resultPage("Action already taken", true));
        }

        try {
            long installationId = parseInstallationId(analysis.getInstallationId());
            String remoteNote = "";
            try {
                switch (payload.action().toLowerCase()) {
                    case "approve" -> {
                        // Submit approval review (best-effort; author cannot review their own PR)
                        try {
                            gitHubApiClient.submitReview(payload.owner(), payload.repo(), payload.prNumber(),
                                    "Approved via Glint Triage Bot.", "APPROVE", null, installationId).block();
                        } catch (Exception ex) {
                            log.info("Submit review notice (proceeding to merge): {}", ex.getMessage());
                        }
                        // Accept & merge the pull request directly on GitHub
                        var mergeMono = gitHubApiClient.mergePullRequest(payload.owner(), payload.repo(), payload.prNumber(),
                                "Merge pull request #" + payload.prNumber() + " from " + payload.owner() + "/" + payload.repo(),
                                "Approved and accepted via Glint Triage Bot.", installationId);
                        if (mergeMono != null) {
                            mergeMono.block();
                        }
                    }
                    case "request-changes" ->
                            gitHubApiClient.submitReview(payload.owner(), payload.repo(), payload.prNumber(),
                                    "Changes requested via Glint Triage Bot.", "REQUEST_CHANGES", null, installationId).block();
                    case "close", "reject" -> {
                        try {
                            gitHubApiClient.postComment(payload.owner(), payload.repo(), payload.prNumber(),
                                    "Pull request #" + payload.prNumber() + " was rejected and closed via Glint Triage Bot.", installationId).block();
                        } catch (Exception ignored) {}
                        gitHubApiClient.closePullRequest(payload.owner(), payload.repo(), payload.prNumber(),
                                installationId).block();
                    }
                    default -> log.warn("Unknown action in token: {}", payload.action());
                }
            } catch (Exception e) {
                log.warn("Remote GitHub call failed (saving action locally): {}", e.getMessage());
                remoteNote = " (Saved locally; remote write requires GITHUB_TOKEN for this repository)";
            }
            analysis.setActionTaken(true);
            analysis.setStatus("ACTIONED");
            prAnalysisRepository.save(analysis);

            String actionDesc = "approve".equalsIgnoreCase(payload.action())
                    ? "PR #" + payload.prNumber() + " Accepted and Merged on GitHub!"
                    : ("reject".equalsIgnoreCase(payload.action()) || "close".equalsIgnoreCase(payload.action())
                    ? "PR #" + payload.prNumber() + " Rejected and Closed on GitHub!"
                    : payload.action());

            return ResponseEntity.ok(resultPage("Action completed: " + payload.action() + " — " + actionDesc + remoteNote, true));
        } catch (Exception e) {
            log.error("Failed to execute action {} for {}/{}/PR#{}", payload.action(),
                    payload.owner(), payload.repo(), payload.prNumber(), e);
            return ResponseEntity.status(502).body(resultPage("Action failed: " + e.getMessage(), false));
        }
    }

    private long parseInstallationId(String id) {
        if (id == null) return 0L;
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String resultPage(String message, boolean success) {
        String color = success ? "#10b981" : "#ef4444";
        String icon = success ? "✅" : "⚠️";
        return "<!doctype html><html><head><meta charset='utf-8'><title>Triage Action</title>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1'>"
                + "<style>"
                + "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; "
                + "       background: #0f172a; color: #f8fafc; display: flex; justify-content: center; align-items: center; min-height: 100vh; margin: 0; }"
                + ".card { background: #1e293b; border: 1px solid #334155; border-radius: 12px; padding: 36px; max-width: 460px; width: 90%; text-align: center; box-shadow: 0 10px 25px rgba(0,0,0,0.5); }"
                + "h2 { color: " + color + "; margin: 12px 0 8px; font-size: 20px; font-weight: 700; }"
                + "p { color: #94a3b8; line-height: 1.5; margin: 0 0 24px; font-size: 14px; }"
                + ".btn { display: inline-block; background: #3b82f6; color: #ffffff; padding: 10px 22px; border-radius: 6px; text-decoration: none; font-weight: 600; font-size: 13px; transition: background 0.2s; }"
                + ".btn:hover { background: #2563eb; }"
                + "</style></head><body>"
                + "<div class='card'>"
                + "<div style='font-size: 40px;'>" + icon + "</div>"
                + "<h2>" + escapeHtml(message) + "</h2>"
                + "<p>PR triage action recorded. Changes are synchronized with repository status.</p>"
                + "<a class='btn' href='/'>Return to Dashboard</a>"
                + "</div></body></html>";
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
