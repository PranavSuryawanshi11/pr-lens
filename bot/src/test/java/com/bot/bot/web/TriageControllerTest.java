package com.bot.bot.web;

import com.bot.bot.persistence.PrAnalysis;
import com.bot.bot.service.ReviewOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TriageControllerTest {

    @Test
    void triagePrPostParsesGithubUrlAndReturnsAnalysis() {
        ReviewOrchestrator orchestrator = mock(ReviewOrchestrator.class);
        PrAnalysis analysis = new PrAnalysis();
        analysis.setOwner("facebook");
        analysis.setRepo("react");
        analysis.setPrNumber(12345);
        analysis.setTier("GREEN");
        analysis.setSecurityFlag(false);
        analysis.setSummary("All heuristics passed cleanly.");
        analysis.setStatus("COMPLETED");
        analysis.setCreatedAt(Instant.now());

        when(orchestrator.triagePullRequest(eq("facebook"), eq("react"), eq(12345)))
                .thenReturn(Mono.just(analysis));

        TriageController controller = new TriageController(orchestrator);

        ResponseEntity<?> response = controller.triagePrPost(
                new TriageController.TriageRequest("https://github.com/facebook/react/pull/12345", null, null, null)
        );

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        TriageController.TriageResponse body = (TriageController.TriageResponse) response.getBody();
        assertNotNull(body);
        assertEquals("facebook", body.owner());
        assertEquals("react", body.repo());
        assertEquals(12345, body.prNumber());
        assertEquals("GREEN", body.tier());
    }

    @Test
    void triagePrGetRejectsInvalidUrl() {
        ReviewOrchestrator orchestrator = mock(ReviewOrchestrator.class);
        TriageController controller = new TriageController(orchestrator);

        ResponseEntity<?> response = controller.triagePrGet("invalid-url-here", null, null, null);
        assertNotNull(response);
        assertEquals(400, response.getStatusCode().value());
    }
}
