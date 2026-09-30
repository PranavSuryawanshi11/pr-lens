package com.bot.bot.web;

import com.bot.bot.actions.TokenService;
import com.bot.bot.email.ThresholdAlertService;
import com.bot.bot.persistence.PrAnalysis;
import com.bot.bot.service.ReviewOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TriageControllerExtendedTest {

    private ReviewOrchestrator orchestrator;
    private ThresholdAlertService alertService;
    private TokenService tokenService;
    private TriageController controller;

    @BeforeEach
    void setUp() {
        orchestrator = mock(ReviewOrchestrator.class);
        alertService = mock(ThresholdAlertService.class);
        tokenService = mock(TokenService.class);
        controller = new TriageController(orchestrator, alertService, tokenService);
    }

    @Test
    @DisplayName("Parses standard full GitHub PR URL")
    void parsesStandardGitHubPrUrl() {
        PrAnalysis analysis = new PrAnalysis();
        analysis.setOwner("facebook");
        analysis.setRepo("react");
        analysis.setPrNumber(1234);
        analysis.setTier("GREEN");
        analysis.setSecurityFlag(false);

        when(orchestrator.triagePullRequest("facebook", "react", 1234)).thenReturn(Mono.just(analysis));
        when(tokenService.buildActionUrl(anyString(), anyString(), anyInt(), eq("approve"))).thenReturn("http://approve");
        when(tokenService.buildActionUrl(anyString(), anyString(), anyInt(), eq("reject"))).thenReturn("http://reject");

        TriageController.TriageRequest request = new TriageController.TriageRequest(
                "https://github.com/facebook/react/pull/1234", null, null, null
        );

        ResponseEntity<?> resp = controller.triagePrPost(request);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody() instanceof TriageController.TriageResponse);
        TriageController.TriageResponse tr = (TriageController.TriageResponse) resp.getBody();
        assertEquals("facebook", tr.owner());
        assertEquals("react", tr.repo());
        assertEquals(1234, tr.prNumber());
        assertEquals("http://approve", tr.approveUrl());
        assertEquals("http://reject", tr.rejectUrl());
    }

    @Test
    @DisplayName("Parses PR URL with hash syntax (e.g. owner/repo#42)")
    void parsesHashPrUrl() {
        PrAnalysis analysis = new PrAnalysis();
        analysis.setOwner("org");
        analysis.setRepo("repo");
        analysis.setPrNumber(42);
        analysis.setTier("YELLOW");

        when(orchestrator.triagePullRequest("org", "repo", 42)).thenReturn(Mono.just(analysis));

        ResponseEntity<?> resp = controller.triagePrGet("org/repo#42", null, null, null);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(orchestrator).triagePullRequest("org", "repo", 42);
    }

    @Test
    @DisplayName("Rejects malformed PR URL with 400 Bad Request")
    void rejectsMalformedPrUrl() {
        TriageController.TriageRequest request = new TriageController.TriageRequest(
                "https://notgithub.com/no-pr-number", null, null, null
        );

        ResponseEntity<?> resp = controller.triagePrPost(request);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().toString().contains("Invalid PR URL"));
    }

    @Test
    @DisplayName("Rejects when owner, repo, or PR number is missing or zero")
    void rejectsMissingParameters() {
        ResponseEntity<?> resp1 = controller.triagePrGet(null, "", "repo", 1);
        assertEquals(HttpStatus.BAD_REQUEST, resp1.getStatusCode());

        ResponseEntity<?> resp2 = controller.triagePrGet(null, "owner", "", 1);
        assertEquals(HttpStatus.BAD_REQUEST, resp2.getStatusCode());

        ResponseEntity<?> resp3 = controller.triagePrGet(null, "owner", "repo", 0);
        assertEquals(HttpStatus.BAD_REQUEST, resp3.getStatusCode());

        ResponseEntity<?> resp4 = controller.triagePrGet(null, "owner", "repo", -5);
        assertEquals(HttpStatus.BAD_REQUEST, resp4.getStatusCode());
    }

    @Test
    @DisplayName("Direct diff triage triggers triagePullRequestWithDiff")
    void executesDirectDiffTriage() {
        PrAnalysis analysis = new PrAnalysis();
        analysis.setOwner("owner");
        analysis.setRepo("repo");
        analysis.setPrNumber(7);
        analysis.setTier("RED");
        analysis.setSecurityFlag(true);

        when(orchestrator.triagePullRequestWithDiff("owner", "repo", 7, "Custom Title", "dev", "diff content"))
                .thenReturn(Mono.just(analysis));

        TriageController.TriageRequest request = new TriageController.TriageRequest(
                null, "owner", "repo", 7, "Custom Title", "dev", "diff content"
        );

        ResponseEntity<?> resp = controller.triagePrPost(request);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(orchestrator).triagePullRequestWithDiff("owner", "repo", 7, "Custom Title", "dev", "diff content");
        verify(alertService).sendTriageReport(analysis, null);
    }

    @Test
    @DisplayName("Returns ALREADY_ANALYZED when analysis is null (no change since last triage)")
    void returnsAlreadyAnalyzedWhenNoChanges() {
        when(orchestrator.triagePullRequest("owner", "repo", 10)).thenReturn(Mono.empty());

        ResponseEntity<?> resp = controller.triagePrGet(null, "owner", "repo", 10);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals("ALREADY_ANALYZED", body.get("status"));
    }

    @Test
    @DisplayName("Returns 500 Internal Server Error when orchestrator throws an unhandled exception")
    void returns500OnOrchestratorException() {
        when(orchestrator.triagePullRequest("owner", "repo", 10))
                .thenThrow(new RuntimeException("GitHub API unavailable"));

        ResponseEntity<?> resp = controller.triagePrGet(null, "owner", "repo", 10);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals("ERROR", body.get("status"));
        assertEquals("GitHub API unavailable", body.get("error"));
    }

    @Test
    @DisplayName("1-arg constructor defaults alertService and tokenService to null")
    void singleArgConstructorDefaults() {
        TriageController simpleController = new TriageController(orchestrator);
        assertNotNull(simpleController);
    }
}
