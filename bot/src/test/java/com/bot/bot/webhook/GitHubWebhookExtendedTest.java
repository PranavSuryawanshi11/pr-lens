package com.bot.bot.webhook;

import com.bot.bot.service.ReviewOrchestrator;
import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class GitHubWebhookExtendedTest {

    private WebhookSignatureVerifier verifier;
    private ReviewOrchestrator orchestrator;
    private Gson gson;
    private GitHubWebhookController controller;

    @BeforeEach
    void setUp() {
        verifier = mock(WebhookSignatureVerifier.class);
        orchestrator = mock(ReviewOrchestrator.class);
        gson = new Gson();
        controller = new GitHubWebhookController(verifier, orchestrator, gson);
    }

    @Test
    @DisplayName("GET /webhook/health returns 200 OK")
    void healthReturnsOk() {
        ResponseEntity<String> resp = controller.health();
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("OK", resp.getBody());
    }

    @Test
    @DisplayName("Returns 401 Unauthorized when signature is missing")
    void rejectsMissingSignature() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> resp = controller.handleGitHubWebhook(
                request, null, "pull_request", "delivery-1"
        );

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertEquals("Invalid signature", resp.getBody());
        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("Returns 401 Unauthorized when signature verifier fails")
    void rejectsInvalidSignature() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));

        when(verifier.verifySignature(anyString(), anyString())).thenReturn(false);

        ResponseEntity<String> resp = controller.handleGitHubWebhook(
                request, "sha256=invalidsig", "pull_request", "delivery-2"
        );

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("Ignores non-PR events like 'push', 'ping', 'release' with 200 OK")
    void ignoresNonPrEvents() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));

        when(verifier.verifySignature(anyString(), anyString())).thenReturn(true);

        ResponseEntity<String> pushResp = controller.handleGitHubWebhook(
                request, "sha256=validsig", "push", "del-push"
        );
        assertEquals(HttpStatus.OK, pushResp.getStatusCode());
        assertEquals("Event ignored", pushResp.getBody());

        ResponseEntity<String> pingResp = controller.handleGitHubWebhook(
                request, "sha256=validsig", "ping", "del-ping"
        );
        assertEquals(HttpStatus.OK, pingResp.getStatusCode());
        assertEquals("Event ignored", pingResp.getBody());
    }

    @Test
    @DisplayName("Returns 400 Bad Request when PR payload has missing action field")
    void rejectsMissingActionField() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("{\"repository\": {\"full_name\": \"owner/repo\"}}".getBytes(StandardCharsets.UTF_8));

        when(verifier.verifySignature(anyString(), anyString())).thenReturn(true);

        ResponseEntity<String> resp = controller.handleGitHubWebhook(
                request, "sha256=valid", "pull_request", "del-action"
        );

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("Missing action field", resp.getBody());
    }

    @Test
    @DisplayName("Ignores PR action 'closed' and 'labeled' with 200 OK")
    void ignoresUnsupportedPrActions() throws IOException {
        when(verifier.verifySignature(anyString(), anyString())).thenReturn(true);

        MockHttpServletRequest reqClosed = new MockHttpServletRequest();
        reqClosed.setContent("{\"action\": \"closed\"}".getBytes(StandardCharsets.UTF_8));
        ResponseEntity<String> respClosed = controller.handleGitHubWebhook(
                reqClosed, "sha256=valid", "pull_request", "del-closed"
        );
        assertEquals(HttpStatus.OK, respClosed.getStatusCode());
        assertEquals("Action ignored", respClosed.getBody());

        MockHttpServletRequest reqLabeled = new MockHttpServletRequest();
        reqLabeled.setContent("{\"action\": \"labeled\"}".getBytes(StandardCharsets.UTF_8));
        ResponseEntity<String> respLabeled = controller.handleGitHubWebhook(
                reqLabeled, "sha256=valid", "pull_request", "del-labeled"
        );
        assertEquals(HttpStatus.OK, respLabeled.getStatusCode());
        assertEquals("Action ignored", respLabeled.getBody());

        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("Starts async review processing on 'opened', 'synchronize', 'reopened'")
    void processesSupportedPrActions() throws IOException {
        when(verifier.verifySignature(anyString(), anyString())).thenReturn(true);

        String[] actions = {"opened", "synchronize", "reopened"};
        for (String action : actions) {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.setContent(String.format("{\"action\": \"%s\", \"repository\": {\"full_name\": \"test/repo\"}}", action)
                    .getBytes(StandardCharsets.UTF_8));

            ResponseEntity<String> resp = controller.handleGitHubWebhook(
                    req, "sha256=valid", "pull_request", "del-" + action
            );

            assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());
            assertEquals("Processing started", resp.getBody());
        }

        verify(orchestrator, times(3)).processPullRequest(any());
    }

    @Test
    @DisplayName("Returns 500 Internal Server Error when parsing or processing throws")
    void handlesUnexpectedProcessingException() throws IOException {
        when(verifier.verifySignature(anyString(), anyString())).thenReturn(true);
        doThrow(new RuntimeException("Crash")).when(orchestrator).processPullRequest(any());

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setContent("{\"action\": \"opened\"}".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> resp = controller.handleGitHubWebhook(
                req, "sha256=valid", "pull_request", "del-err"
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertEquals("Error processing webhook", resp.getBody());
    }
}
