package com.bot.bot.web;

import com.bot.bot.actions.TokenException;
import com.bot.bot.actions.TokenService;
import com.bot.bot.github.GitHubApiClient;
import com.bot.bot.persistence.PrAnalysis;
import com.bot.bot.persistence.PrAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ActionControllerExtendedTest {

    private TokenService tokenService;
    private PrAnalysisRepository repository;
    private GitHubApiClient gitHubApiClient;
    private ActionController controller;

    @BeforeEach
    void setUp() {
        tokenService = mock(TokenService.class);
        repository = mock(PrAnalysisRepository.class);
        gitHubApiClient = mock(GitHubApiClient.class);
        controller = new ActionController(tokenService, repository, gitHubApiClient);
    }

    @Test
    @DisplayName("Returns 410 when token was already used")
    void returns410WhenTokenAlreadyUsed() {
        when(tokenService.verify("used-token"))
                .thenThrow(new TokenException("token already used"));

        ResponseEntity<String> resp = controller.handleAction("used-token", "approve");
        assertEquals(HttpStatus.GONE, resp.getStatusCode());
        assertTrue(resp.getBody().contains("Invalid or expired link"));
    }

    @Test
    @DisplayName("Returns 400 when token verification fails with other TokenException")
    void returns400WhenTokenInvalid() {
        when(tokenService.verify("invalid-token"))
                .thenThrow(new TokenException("bad signature"));

        ResponseEntity<String> resp = controller.handleAction("invalid-token", "approve");
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().contains("Invalid or expired link"));
    }

    @Test
    @DisplayName("Returns 400 when action parameter does not match token action")
    void returns400WhenActionMismatch() {
        TokenService.TokenPayload payload = new TokenService.TokenPayload("owner", "repo", 1, "approve", 999999L);
        when(tokenService.verify("token")).thenReturn(payload);

        ResponseEntity<String> resp = controller.handleAction("token", "close");
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().contains("Action mismatch"));
    }

    @Test
    @DisplayName("Returns 404 when PR analysis record does not exist in repository")
    void returns404WhenPrNotFound() {
        TokenService.TokenPayload payload = new TokenService.TokenPayload("owner", "repo", 404, "approve", 999999L);
        when(tokenService.verify("token")).thenReturn(payload);
        when(repository.findLatest("owner", "repo", 404)).thenReturn(Optional.empty());

        ResponseEntity<String> resp = controller.handleAction("token", "approve");
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertTrue(resp.getBody().contains("PR not found"));
    }

    @Test
    @DisplayName("Returns 410 when PR action has already been taken")
    void returns410WhenActionAlreadyTaken() {
        TokenService.TokenPayload payload = new TokenService.TokenPayload("owner", "repo", 1, "approve", 999999L);
        when(tokenService.verify("token")).thenReturn(payload);

        PrAnalysis analysis = new PrAnalysis();
        analysis.setActionTaken(true);
        when(repository.findLatest("owner", "repo", 1)).thenReturn(Optional.of(analysis));

        ResponseEntity<String> resp = controller.handleAction("token", "approve");
        assertEquals(HttpStatus.GONE, resp.getStatusCode());
        assertTrue(resp.getBody().contains("Action already taken"));
    }

    @Test
    @DisplayName("Successfully executes approve and merge action")
    void successfullyExecutesApprove() {
        TokenService.TokenPayload payload = new TokenService.TokenPayload("owner", "repo", 1, "approve", 999999L);
        when(tokenService.verify("token")).thenReturn(payload);

        PrAnalysis analysis = new PrAnalysis();
        analysis.setActionTaken(false);
        analysis.setInstallationId("12345");
        when(repository.findLatest("owner", "repo", 1)).thenReturn(Optional.of(analysis));

        when(gitHubApiClient.submitReview(anyString(), anyString(), anyInt(), anyString(), anyString(), any(), anyLong()))
                .thenReturn(Mono.empty());
        when(gitHubApiClient.mergePullRequest(anyString(), anyString(), anyInt(), anyString(), anyString(), anyLong()))
                .thenReturn(Mono.empty());

        ResponseEntity<String> resp = controller.handleAction("token", "approve");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().contains("Action completed"));
        assertTrue(analysis.getActionTaken());
        assertEquals("ACTIONED", analysis.getStatus());
        verify(repository).save(analysis);
    }

    @Test
    @DisplayName("Successfully executes request-changes action")
    void successfullyExecutesRequestChanges() {
        TokenService.TokenPayload payload = new TokenService.TokenPayload("owner", "repo", 2, "request-changes", 999999L);
        when(tokenService.verify("token")).thenReturn(payload);

        PrAnalysis analysis = new PrAnalysis();
        analysis.setActionTaken(false);
        analysis.setInstallationId("12345");
        when(repository.findLatest("owner", "repo", 2)).thenReturn(Optional.of(analysis));

        when(gitHubApiClient.submitReview(anyString(), anyString(), anyInt(), anyString(), anyString(), any(), anyLong()))
                .thenReturn(Mono.empty());

        ResponseEntity<String> resp = controller.handleAction("token", "request-changes");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(analysis.getActionTaken());
        verify(gitHubApiClient).submitReview(eq("owner"), eq("repo"), eq(2), anyString(), eq("REQUEST_CHANGES"), any(), eq(12345L));
    }

    @Test
    @DisplayName("Successfully executes close/reject action")
    void successfullyExecutesClose() {
        TokenService.TokenPayload payload = new TokenService.TokenPayload("owner", "repo", 3, "close", 999999L);
        when(tokenService.verify("token")).thenReturn(payload);

        PrAnalysis analysis = new PrAnalysis();
        analysis.setActionTaken(false);
        analysis.setInstallationId("0");
        when(repository.findLatest("owner", "repo", 3)).thenReturn(Optional.of(analysis));

        when(gitHubApiClient.postComment(anyString(), anyString(), anyInt(), anyString(), anyLong()))
                .thenReturn(Mono.empty());
        when(gitHubApiClient.closePullRequest(anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(Mono.empty());

        ResponseEntity<String> resp = controller.handleAction("token", "close");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(analysis.getActionTaken());
        verify(gitHubApiClient).closePullRequest(eq("owner"), eq("repo"), eq(3), eq(0L));
    }
}
